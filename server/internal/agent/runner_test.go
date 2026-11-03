package agent_test

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"
	"unicode/utf8"

	"notes/server/internal/agent"
	"notes/server/internal/llm"
	"notes/server/internal/notes"
)

// fakeModel answers each chat with the next scripted step. A step sees the
// request, so it can act between the agent's tool calls, as a user would.
type fakeModel struct {
	mu       sync.Mutex
	steps    []func(req llm.ChatRequest) (*llm.ChatResponse, error)
	requests []llm.ChatRequest
}

func (m *fakeModel) Chat(_ context.Context, _ llm.Settings, req llm.ChatRequest) (*llm.ChatResponse, error) {
	m.mu.Lock()
	m.requests = append(m.requests, req)
	var step func(llm.ChatRequest) (*llm.ChatResponse, error)
	if len(m.steps) > 0 {
		step, m.steps = m.steps[0], m.steps[1:]
	}
	m.mu.Unlock()
	if step == nil {
		return say("No change: nothing scripted."), nil
	}
	return step(req)
}

func (m *fakeModel) calls() int { m.mu.Lock(); defer m.mu.Unlock(); return len(m.requests) }

func call(name, args string) func(llm.ChatRequest) (*llm.ChatResponse, error) {
	return func(llm.ChatRequest) (*llm.ChatResponse, error) { return toolReply(name, args), nil }
}

func toolReply(name, args string) *llm.ChatResponse {
	return &llm.ChatResponse{Model: "fake", Usage: llm.Usage{PromptTokens: 100, CompletionTokens: 10},
		Message: llm.Message{Role: "assistant", ToolCalls: []llm.ToolCall{{ID: "c" + name, Type: "function",
			Function: llm.ToolCallFunction{Name: name, Arguments: args}}}}}
}

func say(text string) *llm.ChatResponse {
	return &llm.ChatResponse{Model: "fake", Usage: llm.Usage{PromptTokens: 100, CompletionTokens: 10},
		Message: llm.Message{Role: "assistant", Content: text}}
}

func final(text string) func(llm.ChatRequest) (*llm.ChatResponse, error) {
	return func(llm.ChatRequest) (*llm.ChatResponse, error) { return say(text), nil }
}

// lastToolResult is what the agent was told by its last tool call.
func lastToolResult(req llm.ChatRequest) string {
	for i := len(req.Messages) - 1; i >= 0; i-- {
		if req.Messages[i].Role == "tool" {
			return req.Messages[i].Content
		}
	}
	return ""
}

type harness struct {
	*fixture
	model    *fakeModel
	runner   *agent.Runner
	settings llm.Settings
	setupErr error
	editor   *fakeEditor
	onRun    func(agent.RunUpdate)
}

func newHarness(t *testing.T) *harness {
	f := newFixture(t)
	h := &harness{fixture: f, model: &fakeModel{},
		settings: llm.Settings{BaseURL: "http://fake", APIKey: "k", Model: "m", DailyTokenCap: llm.DefaultDailyTokenCap}}
	h.runner = agent.NewRunner(agent.RunnerConfig{
		Accounts: func(context.Context) ([]int64, error) { return []int64{1}, nil },
		Open: func(context.Context, int64) (agent.Account, error) {
			a := agent.Account{Notes: f.d.Notes, Agent: f.d.Agent,
				SpaceName: func(_ context.Context, id string) string { return "space " + id }}
			if h.editor != nil {
				a.Editors = h.editor
			}
			a.OnRun = func(u agent.RunUpdate) {
				if h.onRun != nil {
					h.onRun(u)
				}
			}
			return a, nil
		},
		Settings: func(context.Context) (llm.Settings, error) { return h.settings, h.setupErr },
		Provider: h.model,
		Log:      slog.New(slog.NewTextHandler(io.Discard, nil)),
		Now:      func() time.Time { f.mu.Lock(); defer f.mu.Unlock(); return f.clock },
	})
	return h
}

// next runs the next due run, failing the test if there's none.
func (h *harness) next() run {
	h.t.Helper()
	ok, err := h.runner.RunNext(ctx, 1)
	if err != nil || !ok {
		h.t.Fatalf("no run: %v", err)
	}
	runs := h.runs()
	for _, r := range runs {
		if r.Status == agent.StatusRunning {
			h.t.Fatalf("still running: %+v", r)
		}
	}
	var latest run
	var started int64
	for _, r := range h.allRuns() {
		if r.started >= started {
			latest, started = r.run, r.started
		}
	}
	return latest
}

type fullRun struct {
	run
	started                  int64
	outcome, message, model  string
	toolCalls                int
	promptTokens, completion int64
}

func (h *harness) allRuns() []fullRun {
	rows, err := h.db.R.Query(`SELECT status, trigger, space_id, note_id, failure, coalesce(started_at, 0),
		outcome, message, model, tool_calls, prompt_tokens, completion_tokens FROM agent_runs ORDER BY created_at, rowid`)
	if err != nil {
		h.t.Fatal(err)
	}
	defer rows.Close()
	var out []fullRun
	for rows.Next() {
		var r fullRun
		if err := rows.Scan(&r.Status, &r.Trigger, &r.Space, &r.Note, &r.Failure, &r.started,
			&r.outcome, &r.message, &r.model, &r.toolCalls, &r.promptTokens, &r.completion); err != nil {
			h.t.Fatal(err)
		}
		out = append(out, r)
	}
	return out
}

func (h *harness) only() fullRun {
	h.t.Helper()
	all := h.allRuns()
	if len(all) != 1 {
		h.t.Fatalf("want one run, got %+v", all)
	}
	return all[0]
}

func (h *harness) content(id string) string {
	h.t.Helper()
	n, err := h.d.Notes.Get(ctx, id)
	if err != nil {
		h.t.Fatal(err)
	}
	return n.Content
}

// done queues a "new note done" run on a new note in work.
func (h *harness) done(id, content string) {
	h.t.Helper()
	h.create(id, "work", content)
	h.advance(agent.IdleAfter)
	if h.close() != 1 {
		h.t.Fatal("no run queued")
	}
}

func TestAutoTagging(t *testing.T) {
	h := newHarness(t)
	h.create("old", "work", "Bread\n\n#groceries")
	h.agent("work", agent.NewNoteDone)
	if _, err := h.d.Agent.SetTimeZone(ctx, "Asia/Tokyo"); err != nil {
		t.Fatal(err)
	}
	h.done("n1", "Milk\nEggs")
	h.model.steps = append(h.model.steps,
		call("list_tags", `{}`),
		call("insert", `{"at": "end", "text": "#groceries"}`),
		final("Added #groceries."),
	)
	h.next()

	if got := h.content("n1"); got != "Milk\nEggs\n\n#groceries\n" {
		t.Fatalf("content: %q", got)
	}
	r := h.only()
	if r.Status != agent.StatusDone || r.outcome != agent.OutcomeEdited || r.message != "Added #groceries." ||
		r.toolCalls != 2 || r.promptTokens != 300 || r.completion != 30 || r.model != "fake" {
		t.Fatalf("run: %+v", r)
	}

	// What the model was told.
	req := h.model.requests[0]
	system, user := req.Messages[0].Content, req.Messages[1].Content
	for _, want := range []string{"Space.", "Do newNoteDone.", "Asia/Tokyo", "2023-11-15"} {
		if !strings.Contains(system, want) {
			t.Errorf("system prompt lacks %q:\n%s", want, system)
		}
	}
	if !strings.Contains(user, `"content": "Milk\nEggs"`) || !strings.Contains(user, `"trigger": "newNoteDone"`) {
		t.Errorf("event: %s", user)
	}
	if len(req.Tools) == 0 {
		t.Error("no tools offered")
	}
	if got := lastToolResult(h.model.requests[1]); !strings.Contains(got, `"tag":"#groceries"`) {
		t.Errorf("list_tags: %s", got)
	}

	// The agent's write starts no session and queues nothing.
	if h.sessions() != 0 {
		t.Fatal("the agent's write started a session")
	}
	h.advance(agent.IdleAfter)
	h.close()
	if len(h.allRuns()) != 1 {
		t.Fatal("the agent's write queued a run")
	}

	// Its change is recorded, with the note before it.
	var before, after string
	var beforeRev, afterRev int64
	if err := h.db.R.QueryRow(`SELECT before_content, before_revision, after_content, after_revision FROM agent_run_edits WHERE note_id = 'n1'`).
		Scan(&before, &beforeRev, &after, &afterRev); err != nil {
		t.Fatal(err)
	}
	if before != "Milk\nEggs" || beforeRev != 1 || after != "Milk\nEggs\n\n#groceries\n" || afterRev != 2 {
		t.Fatalf("edit: %q %d %q %d", before, beforeRev, after, afterRev)
	}
	var used int64
	if err := h.db.R.QueryRow(`SELECT prompt_tokens + completion_tokens FROM agent_usage`).Scan(&used); err != nil || used != 330 {
		t.Fatalf("usage: %d %v", used, err)
	}
}

func TestToolsRefuseAndExplain(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.create("elsewhere", "home", "Private")
	h.done("n1", "a b a")
	h.model.steps = append(h.model.steps,
		call("edit_note", `{"find": "a", "replace": "x"}`),
		call("edit_note", `{"find": "zzz", "replace": "x"}`),
		call("get_note", `{"id": "elsewhere"}`),
		call("insert", `{"at": "middle", "text": "x"}`),
		call("insert", `{"at": "cursor", "text": "x"}`),
		call("delete_note", `{}`),
		call("edit_note", `not json`),
		final("No change."),
	)
	h.next()
	wants := []string{"appears 2 times", "doesn't appear", "isn't in this space", "at must be", "no cursor", `no tool called`, "aren't a JSON object"}
	for i, want := range wants {
		if got := lastToolResult(h.model.requests[i+1]); !strings.Contains(got, want) {
			t.Errorf("call %d: want %q, got %s", i, want, got)
		}
	}
	if r := h.only(); r.Status != agent.StatusDone || r.outcome != agent.OutcomeNoChange {
		t.Fatalf("run: %+v", r)
	}
	if h.content("n1") != "a b a" || h.content("elsewhere") != "Private" {
		t.Fatal("a refused call changed a note")
	}
}

func TestSkippedWithoutCallingTheModel(t *testing.T) {
	for _, tc := range []struct {
		name    string
		prepare func(h *harness)
		failure string
	}{
		{"turned off", func(h *harness) {
			_, _ = h.d.Agent.Put(ctx, agent.Config{SpaceID: "work", Triggers: map[agent.Trigger]string{agent.NewNoteDone: "x"}})
		}, agent.FailAgentOff},
		{"deleted", func(h *harness) { h.delete("n1") }, agent.FailNoteGone},
		{"moved", func(h *harness) {
			h.update("n1", "a", func(in *notes.UpdateInput) { in.SpaceID = ptr("home") })
		}, agent.FailNoteMoved},
		{"locked", func(h *harness) {
			h.update("n1", "notesd:locked:v1:"+strings.Repeat("A", 60), nil)
		}, agent.FailNoteLocked},
		{"daily cap", func(h *harness) { h.settings.DailyTokenCap = 1; h.useTokens(5) }, agent.FailDailyCap},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h := newHarness(t)
			h.agent("work", agent.NewNoteDone)
			h.done("n1", "a")
			tc.prepare(h)
			// Locking skips the note's queued runs then and there, so there may be none left to run.
			if _, err := h.runner.RunNext(ctx, 1); err != nil {
				t.Fatal(err)
			}
			r := h.allRuns()[0]
			if r.Status != agent.StatusSkipped || r.Failure != tc.failure || h.model.calls() != 0 {
				t.Fatalf("run: %+v, model calls %d", r, h.model.calls())
			}
		})
	}
}

func (h *harness) useTokens(n int64) {
	h.t.Helper()
	day := h.clock.UTC().Format(time.DateOnly)
	if _, err := h.db.W.Exec(`INSERT INTO agent_usage (day, prompt_tokens) VALUES (?, ?)`, day, n); err != nil {
		h.t.Fatal(err)
	}
}

func TestLimits(t *testing.T) {
	t.Run("tool calls", func(t *testing.T) {
		h := newHarness(t)
		h.agent("work", agent.NewNoteDone)
		h.done("n1", "a")
		for range agent.MaxToolCalls + 5 {
			h.model.steps = append(h.model.steps, call("get_note", `{}`))
		}
		h.next()
		if r := h.only(); r.Status != agent.StatusFailed || r.Failure != agent.FailToolCap || h.model.calls() != agent.MaxToolCalls+1 {
			t.Fatalf("run: %+v, calls %d", r, h.model.calls())
		}
	})
	t.Run("tokens", func(t *testing.T) {
		h := newHarness(t)
		h.agent("work", agent.NewNoteDone)
		h.done("n1", "a")
		h.model.steps = append(h.model.steps, func(llm.ChatRequest) (*llm.ChatResponse, error) {
			r := toolReply("get_note", `{}`)
			r.Usage.PromptTokens = agent.MaxRunTokens + 1
			return r, nil
		})
		h.next()
		if r := h.only(); r.Status != agent.StatusFailed || r.Failure != agent.FailTokenCap {
			t.Fatalf("run: %+v", r)
		}
	})
}

func TestDailyCapDuringARun(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "a")
	h.settings.DailyTokenCap = 150
	h.model.steps = append(h.model.steps, call("get_note", `{}`), call("get_note", `{}`), final("No change."))
	h.next()
	if r := h.only(); r.Status != agent.StatusFailed || r.Failure != agent.FailDailyCap || h.model.calls() != 2 {
		t.Fatalf("run: %+v, calls %d", r, h.model.calls())
	}
}

func TestAgentSeesOnlyUnlockedNotes(t *testing.T) {
	h := newHarness(t)
	h.create("open", "work", "Readable")
	for _, id := range []string{"l1", "l2", "l3"} {
		h.advance(time.Second)
		h.create(id, "work", "notesd:locked:v1:"+strings.Repeat("A", 60))
	}
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "a")
	h.model.steps = append(h.model.steps, call("list_notes", `{"limit": 2}`), final("No change."))
	h.next()
	got := lastToolResult(h.model.requests[1])
	if !strings.Contains(got, `"id":"n1"`) || !strings.Contains(got, `"id":"open"`) || strings.Contains(got, `"l1"`) {
		t.Fatalf("list_notes: %s", got)
	}
}

func TestInsertKeepsTheOtherEnd(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "\n\nBody\n\n")
	h.model.steps = append(h.model.steps, call("insert", `{"at": "start", "text": "# Title"}`),
		call("insert", `{"at": "end", "text": "#tag"}`), final("ok"))
	h.next()
	if got := h.content("n1"); got != "# Title\n\nBody\n\n#tag\n" {
		t.Fatalf("content: %q", got)
	}
}

func TestLongMessageIsCutOnACharacter(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "a")
	h.model.steps = append(h.model.steps, final("a"+strings.Repeat("é", 3000)))
	h.next()
	if m := h.only().message; len(m) > 4<<10 || !utf8.ValidString(m) {
		t.Fatalf("message: %d bytes, valid %v", len(m), utf8.ValidString(m))
	}
}

func TestNoteChangedUnderTheRun(t *testing.T) {
	userTypes := func(h *harness, text string) func(llm.ChatRequest) (*llm.ChatResponse, error) {
		return func(llm.ChatRequest) (*llm.ChatResponse, error) {
			h.update("n1", text, nil)
			return toolReply("insert", `{"at": "end", "text": "#tag"}`), nil
		}
	}
	t.Run("starts over once", func(t *testing.T) {
		h := newHarness(t)
		h.agent("work", agent.NewNoteDone)
		h.done("n1", "a")
		h.model.steps = append(h.model.steps, userTypes(h, "ab"), call("insert", `{"at": "end", "text": "#tag"}`), final("Tagged."))
		h.next()
		if got := h.content("n1"); got != "ab\n\n#tag\n" {
			t.Fatalf("content: %q", got)
		}
		if r := h.only(); r.Status != agent.StatusDone || r.outcome != agent.OutcomeEdited {
			t.Fatalf("run: %+v", r)
		}
		// The second start saw the user's text.
		if !strings.Contains(h.model.requests[1].Messages[1].Content, `"content": "ab"`) {
			t.Fatalf("restart: %s", h.model.requests[1].Messages[1].Content)
		}
	})
	t.Run("then gives up", func(t *testing.T) {
		h := newHarness(t)
		h.agent("work", agent.NewNoteDone)
		h.done("n1", "a")
		h.model.steps = append(h.model.steps, userTypes(h, "ab"), userTypes(h, "abc"))
		h.next()
		if r := h.only(); r.Status != agent.StatusFailed || r.Failure != agent.FailNoteChanged {
			t.Fatalf("run: %+v", r)
		}
		if h.content("n1") != "abc" {
			t.Fatal("the agent wrote over the user")
		}
	})
}

func TestProviderFailureRetriesOnce(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "a")
	down := func(llm.ChatRequest) (*llm.ChatResponse, error) {
		return nil, &llm.ProviderError{Status: 503, Message: "overloaded"}
	}
	h.model.steps = append(h.model.steps, call("get_note", `{}`), down, down)
	h.next()
	// What the first attempt spent stays counted.
	if r := h.only(); r.Status != agent.StatusQueued || r.message != "overloaded" || r.toolCalls != 1 || r.promptTokens != 100 {
		t.Fatalf("after one failure: %+v", r)
	}
	// Not before the retry is due.
	if ok, _ := h.runner.RunNext(ctx, 1); ok {
		t.Fatal("retried at once")
	}
	h.advance(agent.RetryProvider)
	h.next()
	if r := h.only(); r.Status != agent.StatusFailed || r.Failure != agent.FailProvider || r.message != "overloaded" {
		t.Fatalf("after two: %+v", r)
	}
}

func TestRetryKeepsCountingTowardTheCaps(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "a")
	for range agent.MaxToolCalls - 2 {
		h.model.steps = append(h.model.steps, call("get_note", `{}`))
	}
	h.model.steps = append(h.model.steps, func(llm.ChatRequest) (*llm.ChatResponse, error) {
		return nil, &llm.ProviderError{Status: 503, Message: "overloaded"}
	})
	h.next()
	h.advance(agent.RetryProvider)
	for range 5 {
		h.model.steps = append(h.model.steps, call("get_note", `{}`))
	}
	h.next()
	if r := h.only(); r.Status != agent.StatusFailed || r.Failure != agent.FailToolCap || r.toolCalls != agent.MaxToolCalls+1 {
		t.Fatalf("run: %+v", r)
	}
}

func TestPausedWithoutAI(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "a")
	h.setupErr = llm.ErrNotConfigured
	if ok, err := h.runner.RunNext(ctx, 1); ok || err != nil {
		t.Fatalf("ran without AI: %v", err)
	}
	if r := h.only(); r.Status != agent.StatusQueued {
		t.Fatalf("run: %+v", r)
	}
	h.setupErr = nil
	h.next()
	if r := h.only(); r.Status != agent.StatusDone {
		t.Fatalf("run: %+v", r)
	}
}

func TestDeletedAndMovedOutGetTheirSnapshot(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NoteDeleted, agent.MovedOut)
	h.create("n1", "work", "Gone soon")
	h.create("n2", "work", "Leaving")
	h.delete("n1")
	h.update("n2", "Leaving", func(in *notes.UpdateInput) { in.SpaceID = ptr("home") })
	h.model.steps = append(h.model.steps, call("get_note", `{}`), final("No change."))
	h.next()
	user := h.model.requests[0].Messages[1].Content
	if !strings.Contains(user, `"content": "Gone soon"`) || !strings.Contains(user, "deleted") {
		t.Fatalf("deleted event: %s", user)
	}
	if got := lastToolResult(h.model.requests[1]); !strings.Contains(got, "no note") {
		t.Fatalf("read a deleted note: %s", got)
	}
	h.next()
	user = h.model.requests[2].Messages[1].Content
	if !strings.Contains(user, `"content": "Leaving"`) || !strings.Contains(user, `"movedTo": "space home"`) {
		t.Fatalf("moved out event: %s", user)
	}
	// Once run, an event keeps no body, and a deleted note's no title, as the note itself.
	runs := h.runs()
	if runs[0].Event.Content != nil || runs[0].Event.Title != nil || runs[1].Event.Content != nil || runs[1].Event.Title == nil {
		t.Fatalf("events kept: %+v", runs)
	}
}

func TestCreateNote(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "a")
	h.model.steps = append(h.model.steps, call("create_note", `{"title": "Digest", "content": "## Themes"}`), final("Made Digest."))
	h.next()
	if r := h.only(); r.outcome != agent.OutcomeCreated {
		t.Fatalf("run: %+v", r)
	}
	var id string
	var result map[string]any
	_ = json.Unmarshal([]byte(lastToolResult(h.model.requests[1])), &result)
	id, _ = result["id"].(string)
	n, err := h.d.Notes.Get(ctx, id)
	if err != nil || n.Title != "Digest" || n.SpaceID != "work" {
		t.Fatalf("created: %+v %v", n, err)
	}
	if h.sessions() != 0 {
		t.Fatal("the agent's note started a session")
	}
}

func TestInterruptedRunsGoBackOnce(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "a")
	if _, err := h.db.W.Exec(`UPDATE agent_runs SET status = 'running', attempts = 1`); err != nil {
		t.Fatal(err)
	}
	if err := h.d.Agent.RecoverRuns(ctx); err != nil {
		t.Fatal(err)
	}
	if r := h.only(); r.Status != agent.StatusQueued {
		t.Fatalf("first interruption: %+v", r)
	}
	if _, err := h.db.W.Exec(`UPDATE agent_runs SET status = 'running', attempts = 2`); err != nil {
		t.Fatal(err)
	}
	if err := h.d.Agent.RecoverRuns(ctx); err != nil {
		t.Fatal(err)
	}
	if r := h.only(); r.Status != agent.StatusFailed || r.Failure != agent.FailInterrupted {
		t.Fatalf("second interruption: %+v", r)
	}
}

func TestInterruptedRunDropsADeletedNotesText(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NoteDeleted)
	h.create("n1", "work", "Secret")
	h.delete("n1")
	if _, err := h.db.W.Exec(`UPDATE agent_runs SET status = 'running', attempts = 2`); err != nil {
		t.Fatal(err)
	}
	if err := h.d.Agent.RecoverRuns(ctx); err != nil {
		t.Fatal(err)
	}
	if runs := h.runs(); len(runs) != 1 || runs[0].Status != agent.StatusFailed || runs[0].Event.Content != nil || runs[0].Event.Title != nil {
		t.Fatalf("runs: %+v", runs)
	}
}

func TestPurge(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "a")
	h.model.steps = append(h.model.steps, call("insert", `{"at": "end", "text": "x"}`), final("ok"))
	h.next()
	h.advance(agent.Keep - time.Minute)
	if err := h.d.Agent.Purge(ctx); err != nil || len(h.allRuns()) != 1 {
		t.Fatalf("purged early: %v", err)
	}
	h.advance(2 * time.Minute)
	if err := h.d.Agent.Purge(ctx); err != nil || len(h.allRuns()) != 0 {
		t.Fatalf("not purged: %v", err)
	}
	var n int
	if err := h.db.R.QueryRow(`SELECT count(*) FROM agent_run_edits`).Scan(&n); err != nil || n != 0 {
		t.Fatalf("edits left: %d %v", n, err)
	}
}

func TestRunnerRetriesListingAccounts(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "a")
	h.model.steps = append(h.model.steps, final("No change."))
	agent.SetStartRetry(t, 10*time.Millisecond)
	var mu sync.Mutex
	fails := 2
	runner := agent.NewRunner(agent.RunnerConfig{
		Accounts: func(context.Context) ([]int64, error) {
			mu.Lock()
			defer mu.Unlock()
			if fails > 0 {
				fails--
				return nil, io.ErrUnexpectedEOF
			}
			return []int64{1}, nil
		},
		Open: func(context.Context, int64) (agent.Account, error) {
			return agent.Account{Notes: h.d.Notes, Agent: h.d.Agent, SpaceName: func(context.Context, string) string { return "" }}, nil
		},
		Settings: func(context.Context) (llm.Settings, error) { return h.settings, nil },
		Provider: h.model,
		Log:      slog.New(slog.NewTextHandler(io.Discard, nil)),
		Now:      func() time.Time { h.mu.Lock(); defer h.mu.Unlock(); return h.clock },
	})
	runCtx, stop := context.WithCancel(ctx)
	done := make(chan struct{})
	go func() { defer close(done); runner.Run(runCtx) }()
	defer func() { stop(); <-done }()
	deadline := time.Now().Add(5 * time.Second)
	for h.only().Status != agent.StatusDone {
		if time.Now().After(deadline) {
			t.Fatal("the runner never found the account")
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestWorkersRunTheQueue(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "a")
	h.model.steps = append(h.model.steps, final("No change."))
	runCtx, stop := context.WithCancel(ctx)
	done := make(chan struct{})
	go func() { defer close(done); h.runner.Run(runCtx) }()
	defer func() { stop(); <-done }()
	deadline := time.Now().Add(5 * time.Second)
	for h.only().Status != agent.StatusDone {
		if time.Now().After(deadline) {
			t.Fatal("the worker didn't run the queue")
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestListTagsLeavesLockedNotesOut(t *testing.T) {
	h := newHarness(t)
	space := "work"
	if _, err := h.d.Notes.Create(ctx, notes.CreateInput{OperationID: h.op(), ID: "l1", Title: "Plans #secret", SpaceID: &space,
		Content: "notesd:locked:v1:" + strings.Repeat("A", 60)}); err != nil {
		t.Fatal(err)
	}
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "a #open")
	h.model.steps = append(h.model.steps, call("list_tags", `{}`), final("No change."))
	h.next()
	if got := lastToolResult(h.model.requests[1]); !strings.Contains(got, "open") || strings.Contains(got, "secret") {
		t.Fatalf("list_tags: %s", got)
	}
}

func TestEditorRunsDontWaitBehindOthers(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone, agent.EditStarted, agent.Menu)
	h.done("n1", "a")
	h.create("n2", "work", "b")
	release := make(chan struct{})
	h.model.steps = append(h.model.steps, func(llm.ChatRequest) (*llm.ChatResponse, error) {
		<-release
		return say("No change."), nil
	})
	runCtx, stop := context.WithCancel(ctx)
	done := make(chan struct{})
	go func() { defer close(done); h.runner.Run(runCtx) }()
	defer func() { stop(); <-done }()
	status := func(trigger agent.Trigger, note string) string {
		for _, r := range h.runs() {
			if r.Trigger == string(trigger) && r.Note == note {
				return r.Status
			}
		}
		return ""
	}
	waitFor := func(what string, ok func() bool) {
		t.Helper()
		deadline := time.Now().Add(5 * time.Second)
		for !ok() {
			if time.Now().After(deadline) {
				t.Fatalf("waiting for %s: %+v", what, h.runs())
			}
			time.Sleep(5 * time.Millisecond)
		}
	}
	waitFor("n1's run to start", func() bool { return status(agent.NewNoteDone, "n1") == agent.StatusRunning })

	// The other note's: done while n1's still runs.
	if _, err := h.d.Agent.RequestRun(ctx, agent.RunRequest{Trigger: agent.EditStarted, NoteID: "n2"}); err != nil {
		t.Fatal(err)
	}
	h.runner.Wake(1)
	waitFor("n2's run", func() bool { return status(agent.EditStarted, "n2") == agent.StatusDone })

	// The same note's: it waits for n1's to end.
	if _, err := h.d.Agent.RequestRun(ctx, agent.RunRequest{Trigger: agent.Menu, NoteID: "n1"}); err != nil {
		t.Fatal(err)
	}
	h.runner.Wake(1)
	time.Sleep(50 * time.Millisecond)
	if s := status(agent.Menu, "n1"); s != agent.StatusQueued {
		t.Fatalf("ran beside another run on its note: %s", s)
	}
	close(release)
	waitFor("n1's menu run", func() bool { return status(agent.Menu, "n1") == agent.StatusDone })
}
