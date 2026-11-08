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
	removed  bool
}

func newHarness(t *testing.T) *harness {
	f := newFixture(t)
	h := &harness{fixture: f, model: &fakeModel{},
		settings: llm.Settings{BaseURL: "http://fake", APIKey: "k", Model: "m", DailyTokenCap: llm.DefaultDailyTokenCap}}
	h.runner = agent.NewRunner(agent.RunnerConfig{
		Accounts: func(context.Context) ([]int64, error) { return []int64{1}, nil },
		Exists:   func(context.Context, int64) (bool, error) { return !h.removed, nil },
		Open: func(context.Context, int64) (agent.Account, error) {
			return agent.Account{UserID: 1, Notes: f.d.Notes, Agent: f.d.Agent,
				SpaceName: func(_ context.Context, id string) string { return "space " + id }}, nil
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
	// The account's edit, made by its agent: "Edited by" never names someone else (§7.9).
	if n, err := h.d.Notes.Get(ctx, "n1"); err != nil || n.UpdatedBy == nil || *n.UpdatedBy != 1 {
		t.Fatalf("updated by: %v %v", n.UpdatedBy, err)
	}
	r := h.only()
	if r.Status != agent.StatusDone || r.outcome != agent.OutcomeEdited || r.message != "Added #groceries." ||
		r.toolCalls != 2 || r.promptTokens != 300 || r.completion != 30 || r.model != "fake" {
		t.Fatalf("run: %+v", r)
	}

	// What the model was told.
	req := h.model.requests[0]
	system, user := req.Messages[0].Content, req.Messages[1].Content
	for _, want := range []string{"Space.", "Do newNoteDone."} {
		if !strings.Contains(system, want) {
			t.Errorf("system prompt lacks %q:\n%s", want, system)
		}
	}
	// The time goes with the event, so the system prompt stays the same from run to run.
	for _, want := range []string{"Asia/Tokyo", `"today": "2023-11-15"`} {
		if !strings.Contains(user, want) || strings.Contains(system, want) {
			t.Errorf("event lacks %q:\n%s", want, user)
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
		call("insert", `{"at": "cursor", "text": "x"}`),
		call("delete_note", `{}`),
		call("edit_note", `not json`),
		final("No change."),
	)
	h.next()
	wants := []string{"appears 2 times", "doesn't appear", "isn't in this space", "at must be", `no tool called`, "aren't a JSON object"}
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
			h.next()
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
	t.Run("tokens spent before a crash", func(t *testing.T) {
		h := newHarness(t)
		h.agent("work", agent.NewNoteDone)
		h.done("n1", "a")
		if _, err := h.db.W.Exec(`UPDATE agent_runs SET prompt_tokens = ?`, agent.MaxRunTokens); err != nil {
			t.Fatal(err)
		}
		h.next()
		if r := h.only(); r.Status != agent.StatusFailed || r.Failure != agent.FailTokenCap || h.model.calls() != 0 {
			t.Fatalf("run: %+v, calls %d", r, h.model.calls())
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

func TestDailyCapWinsATie(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "a")
	h.settings.DailyTokenCap = agent.MaxRunTokens
	h.model.steps = append(h.model.steps, func(llm.ChatRequest) (*llm.ChatResponse, error) {
		r := toolReply("get_note", `{}`)
		r.Usage.PromptTokens = agent.MaxRunTokens + 1
		return r, nil
	})
	h.next()
	if r := h.only(); r.Status != agent.StatusFailed || r.Failure != agent.FailDailyCap {
		t.Fatalf("run: %+v", r)
	}
}

func TestRemovedAccountRunsNothing(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "a")
	h.removed = true
	if ok, err := h.runner.RunNext(ctx, 1); ok || err != nil {
		t.Fatalf("ran: %v", err)
	}
	if r := h.only(); r.Status != agent.StatusQueued || h.model.calls() != 0 {
		t.Fatalf("run: %+v, calls %d", r, h.model.calls())
	}
}

func TestSearchExcerptIsPlainText(t *testing.T) {
	h := newHarness(t)
	h.create("q", "work", `Say "hi" & <wave>`)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "a")
	h.model.steps = append(h.model.steps, call("search_notes", `{"query": "hi"}`), final("No change."))
	h.next()
	if got := lastToolResult(h.model.requests[1]); !strings.Contains(got, `"excerpt":"Say \"hi\" \u0026 \u003cwave\u003e"`) {
		t.Fatalf("search_notes: %s", got)
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
	t.Run("the change is made to the note as it is now", func(t *testing.T) {
		h := newHarness(t)
		h.agent("work", agent.NewNoteDone)
		h.done("n1", "a")
		h.model.steps = append(h.model.steps, userTypes(h, "ab"), final("Tagged."))
		h.next()
		if got := h.content("n1"); got != "ab\n\n#tag\n" {
			t.Fatalf("content: %q", got)
		}
		// Without starting over: no model call again.
		if r := h.only(); r.Status != agent.StatusDone || r.outcome != agent.OutcomeEdited || h.model.calls() != 2 {
			t.Fatalf("run: %+v after %d calls", r, h.model.calls())
		}
	})
	t.Run("stops when the note can't be read again", func(t *testing.T) {
		for _, tc := range []struct {
			name    string
			change  func(h *harness)
			failure string
		}{
			{"deleted", func(h *harness) { h.delete("n1") }, agent.FailNoteGone},
			{"moved", func(h *harness) {
				h.update("n1", "ab", func(in *notes.UpdateInput) { in.SpaceID = ptr("home") })
			}, agent.FailNoteMoved},
			{"locked", func(h *harness) {
				h.update("n1", "notesd:locked:v1:"+strings.Repeat("A", 60), nil)
			}, agent.FailNoteLocked},
		} {
			t.Run(tc.name, func(t *testing.T) {
				h := newHarness(t)
				h.agent("work", agent.NewNoteDone)
				h.done("n1", "a")
				h.model.steps = append(h.model.steps, func(llm.ChatRequest) (*llm.ChatResponse, error) {
					tc.change(h)
					return toolReply("insert", `{"at": "end", "text": "#tag"}`), nil
				})
				h.next()
				if r := h.only(); r.Status != agent.StatusSkipped || r.Failure != tc.failure || r.promptTokens != 100 || h.model.calls() != 1 {
					t.Fatalf("run: %+v, calls %d", r, h.model.calls())
				}
			})
		}
	})
	t.Run("a find the user changed comes back with the note as it is now", func(t *testing.T) {
		h := newHarness(t)
		h.agent("work", agent.NewNoteDone)
		h.done("n1", "a b")
		h.model.steps = append(h.model.steps, func(llm.ChatRequest) (*llm.ChatResponse, error) {
			h.update("n1", "a c", nil)
			return toolReply("edit_note", `{"find": "b", "replace": "B"}`), nil
		}, final("No change."))
		h.next()
		if h.content("n1") != "a c" {
			t.Fatal("the agent wrote over the user")
		}
		if got := lastToolResult(h.model.requests[1]); !strings.Contains(got, `"error"`) || !strings.Contains(got, `"content":"a c"`) {
			t.Fatalf("told: %s", got)
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
	// Once run, a deleted note's text is gone from its event, as from the note.
	runs := h.runs()
	if runs[0].Event.Content != nil || runs[0].Event.Title != nil || runs[1].Event.Content == nil {
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
		Exists: func(context.Context, int64) (bool, error) { return true, nil },
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

func TestTimeoutDuringAModelCallIsATimeout(t *testing.T) {
	timeout := agent.RunTimeout
	agent.RunTimeout = 20 * time.Millisecond
	t.Cleanup(func() { agent.RunTimeout = timeout })
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "a")
	// A provider that answers past the deadline: its tokens still count.
	h.model.steps = append(h.model.steps, func(llm.ChatRequest) (*llm.ChatResponse, error) {
		time.Sleep(50 * time.Millisecond)
		return say("Done."), nil
	})
	if r := h.next(); r.Status != agent.StatusFailed || r.Failure != agent.FailTimeout {
		t.Fatalf("run: %+v", r)
	}
	if r := h.only(); r.promptTokens != 100 || r.completion != 10 {
		t.Fatalf("tokens: %+v", r)
	}
}

func TestWorkersEndIdleSessionsWhenWoken(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.d.Agent.OnScheduled(func() { h.runner.Wake(1) })
	h.model.steps = append(h.model.steps, final("No change."))
	runCtx, stop := context.WithCancel(ctx)
	done := make(chan struct{})
	go func() { defer close(done); h.runner.Run(runCtx) }()
	defer func() { stop(); <-done }()

	h.create("n1", "work", "a")
	h.advance(agent.IdleAfter)
	// The next session's start wakes the worker, which ends the first one's and runs it.
	h.create("n2", "work", "b")
	deadline := time.Now().Add(5 * time.Second)
	for {
		runs := h.runs()
		if len(runs) == 1 && runs[0].Note == "n1" && runs[0].Status == agent.StatusDone {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("runs: %+v", runs)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestDoneEndsTheRunWithItsLastChange(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "Milk")
	h.model.steps = append(h.model.steps, call("insert", `{"at": "end", "text": "#groceries", "done": "Tagged it."}`))
	h.next()
	r := h.only()
	if r.Status != agent.StatusDone || r.message != "Tagged it." || r.outcome != agent.OutcomeEdited || h.model.calls() != 1 ||
		h.content("n1") != "Milk\n\n#groceries\n" {
		t.Fatalf("run %+v after %d calls: %q", r, h.model.calls(), h.content("n1"))
	}

	// A refused change: the model hears why, so the run goes on.
	h.done("n2", "Eggs")
	h.model.steps = append(h.model.steps, call("edit_note", `{"find": "Ham", "replace": "Jam", "done": "Fixed it."}`), final("No change: no ham."))
	h.next()
	runs := h.allRuns()
	if r := runs[len(runs)-1]; r.message != "No change: no ham." || h.model.calls() != 3 {
		t.Fatalf("run %+v after %d calls", r, h.model.calls())
	}
}

func TestSetContentRewritesANoteItKeeps(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	// Imported: no writing session of its own.
	if _, err := h.d.Notes.Create(ctx, notes.CreateInput{OperationID: h.op(), ID: "digest", Title: "Digest", SpaceID: ptr("work"),
		Content: "## Themes\n- Old\n\n## Open questions\n- One\n", Origin: "import"}); err != nil {
		t.Fatal(err)
	}
	h.done("n1", "Milk")
	h.model.steps = append(h.model.steps, call("set_content", `{"id": "digest", "content": "## Themes\n- Shopping\n\n## Open questions\n- One\n", "done": "Updated the Digest."}`))
	h.next()
	if got := h.content("digest"); got != "## Themes\n- Shopping\n\n## Open questions\n- One\n" || h.model.calls() != 1 {
		t.Fatalf("digest %q after %d calls", got, h.model.calls())
	}

	// Changed since it was read: replacing all of it would wipe that out, so the model gets it back.
	h.done("n2", "Eggs")
	h.model.steps = append(h.model.steps, call("get_note", `{"id": "digest"}`), func(llm.ChatRequest) (*llm.ChatResponse, error) {
		h.update("digest", "## Themes\n- Shopping\n- Typed by the user\n", nil)
		return toolReply("set_content", `{"id": "digest", "content": "## Themes\n- Eggs\n"}`), nil
	}, final("No change."))
	h.next()
	if got := h.content("digest"); got != "## Themes\n- Shopping\n- Typed by the user\n" {
		t.Fatalf("digest %q", got)
	}
	if got := lastToolResult(h.model.requests[len(h.model.requests)-1]); !strings.Contains(got, "Typed by the user") || !strings.Contains(got, `"error"`) {
		t.Fatalf("told: %s", got)
	}
}

func TestWritesAgentsMayNotMake(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "aaa")
	locked := "notesd:locked:v1:" + strings.Repeat("A", 60)
	h.model.steps = append(h.model.steps,
		call("set_content", `{"content": "`+locked+`"}`),
		call("create_note", `{"title": "Secret", "content": "`+locked+`"}`),
		// "aa" is in "aaa" twice, overlapping: it doesn't say which.
		call("edit_note", `{"find": "aa", "replace": "b"}`),
		final("No change."))
	h.next()
	for i, want := range []string{"never lock a note", "never lock a note", "appears 2 times"} {
		if got := lastToolResult(h.model.requests[i+1]); !strings.Contains(got, want) {
			t.Errorf("call %d told %s", i, got)
		}
	}
	if h.content("n1") != "aaa" || len(h.allRuns()) != 1 {
		t.Fatalf("content %q, %d runs", h.content("n1"), len(h.allRuns()))
	}
}

func TestNegativeUsageIsntGivenBack(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "a")
	h.model.steps = append(h.model.steps, func(llm.ChatRequest) (*llm.ChatResponse, error) {
		r := say("No change.")
		r.Usage = llm.Usage{PromptTokens: -1000, CompletionTokens: -10}
		return r, nil
	})
	h.next()
	if r := h.only(); r.promptTokens != 0 || r.completion != 0 {
		t.Fatalf("tokens: %+v", r)
	}
}
