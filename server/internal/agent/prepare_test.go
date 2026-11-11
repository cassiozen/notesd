package agent_test

import (
	"errors"
	"strings"
	"testing"
	"time"

	"notes/server/internal/agent"
	"notes/server/internal/llm"
	"notes/server/internal/notes"
)

var home = agent.UserHome(1)

// aheadHarness has the work space's agent act on new notes, and hears what makes its results stale.
func aheadHarness(t *testing.T, refs ...string) *harness {
	h := newHarness(t)
	c := agent.Config{SpaceID: "work", Enabled: true, Prompt: "Space.", ReferenceNotes: refs,
		Triggers: map[agent.Trigger]string{agent.NoteCreated: "Start it."}}
	if _, err := h.d.Agent.Put(ctx, c); err != nil {
		t.Fatal(err)
	}
	h.d.Agent.OnChange(func(c agent.Change) { h.runner.Changed(home, c) })
	return h
}

func (h *harness) prepare(steps ...func(llm.ChatRequest) (*llm.ChatResponse, error)) (agent.Prepared, bool) {
	h.t.Helper()
	h.model.steps = append(h.model.steps, steps...)
	h.runner.PrepareNow(ctx, home, "work", 1)
	return h.runner.PreparedFor(home, "work", 1)
}

func (h *harness) ready() bool {
	_, ok := h.runner.PreparedFor(home, "work", 1)
	return ok
}

func (h *harness) usage() (tokens int64) {
	h.t.Helper()
	if err := h.db.R.QueryRow(`SELECT coalesce(sum(prompt_tokens + completion_tokens), 0) FROM agent_usage`).Scan(&tokens); err != nil {
		h.t.Fatal(err)
	}
	return tokens
}

func (h *harness) createTitled(id, title, content string) {
	h.t.Helper()
	space := "work"
	if _, err := h.d.Notes.Create(ctx, notes.CreateInput{OperationID: h.op(), ID: id, Title: title, Content: content, SpaceID: &space}); err != nil {
		h.t.Fatal(err)
	}
}

func TestNewNoteRunDoneAhead(t *testing.T) {
	h := aheadHarness(t)
	p, ok := h.prepare(call("insert", `{"at": "start", "text": "## Today", "done": "Added a heading."}`))
	if !ok || p.Title != "" || p.Content != "## Today\n" || len(p.Overlays) != 0 {
		t.Fatalf("prepared %+v %v", p, ok)
	}
	if ev := h.model.requests[0].Messages[1].Content; !strings.Contains(ev, `"content": ""`) {
		t.Fatalf("the run wasn't told of an empty note: %s", ev)
	}
	// Nothing written down until it's used; its tokens count at once.
	if runs := h.allRuns(); len(runs) != 0 || h.usage() != 110 {
		t.Fatalf("runs %+v, usage %d", runs, h.usage())
	}
	if len(h.told) != 1 {
		t.Fatalf("told %+v", h.told)
	}

	if err := h.runner.UsePrepared(ctx, home, p.ID, "n1", "work", 1); err != nil {
		t.Fatal(err)
	}
	r := h.only()
	if r.Status != agent.StatusDone || r.Trigger != string(agent.NoteCreated) || r.Note != "n1" || r.outcome != agent.OutcomeEdited ||
		r.message != "Added a heading." || r.promptTokens+r.completion != 110 || r.model != "fake" || h.runs()[0].Event.By != 1 {
		t.Fatalf("run %+v", r)
	}
	if h.ready() || h.usage() != 110 {
		t.Fatal("still ready, or counted twice")
	}
	if err := h.runner.UsePrepared(ctx, home, p.ID, "n2", "work", 1); !errors.Is(err, agent.ErrPreparedGone) {
		t.Fatalf("used twice: %v", err)
	}

	// The editor opened with it and saves it: the agent's alone, linked, and undone from Activity.
	h.createTitled("n1", "", "## Today\n")
	d, err := h.d.Agent.Run(ctx, p.ID)
	if err != nil || len(d.Edits) != 1 || d.Edits[0].Blocked != "" {
		t.Fatalf("detail %+v %v", d, err)
	}
	h.advance(agent.IdleAfter)
	if h.close() != 0 {
		t.Fatal("the agent's text ended a writing session")
	}
	if _, err := h.undo(p.ID); err != nil {
		t.Fatal(err)
	}
	if got := h.content("n1"); got != "" {
		t.Fatalf("undo left %q", got)
	}
}

func TestUsingAResultSavedAlready(t *testing.T) {
	h := aheadHarness(t)
	p, _ := h.prepare(call("set_title", `{"title": "Monday", "done": "Titled."}`))
	// The note's first save got there before the socket's message.
	h.createTitled("n1", "Monday", "")
	if err := h.runner.UsePrepared(ctx, home, p.ID, "n1", "work", 1); err != nil {
		t.Fatal(err)
	}
	d, err := h.d.Agent.Run(ctx, p.ID)
	if err != nil || len(d.Edits) != 1 || d.Edits[0].Blocked != "" {
		t.Fatalf("detail %+v %v", d, err)
	}
}

func TestAheadCardsShowOnTheNewNote(t *testing.T) {
	h := aheadHarness(t)
	p, _ := h.prepare(call("show_card", `{"body": "What's on your mind?", "done": "Asked."}`))
	if len(p.Overlays) != 1 || p.Overlays[0].NoteID != "" || p.Overlays[0].RunID != p.ID || p.Content != "" {
		t.Fatalf("prepared %+v", p)
	}
	if err := h.runner.UsePrepared(ctx, home, p.ID, "n1", "work", 1); err != nil {
		t.Fatal(err)
	}
	open := h.open("n1")
	if len(open) != 1 || open[0].ID != p.Overlays[0].ID || len(h.shown["n1"]) != 1 {
		t.Fatalf("open %+v, shown %+v", open, h.shown)
	}
	if r := h.only(); r.outcome != agent.OutcomeNoChange {
		t.Fatalf("run %+v", r)
	}
}

func TestAheadIsThrownAwayOnlyWhenWhatItWentByChanges(t *testing.T) {
	h := aheadHarness(t, "Digest")
	h.createTitled("x", "Shopping", "Milk")
	h.prepare(final("No change."))

	h.update("x", "Milk and eggs", nil) // no tags, not a reference, not read
	h.createTitled("y", "Other", "")
	if !h.ready() {
		t.Fatal("thrown away by a note it didn't go by")
	}
	h.update("x", "Milk #food", nil) // the space's tags
	if h.ready() {
		t.Fatal("kept past a tag change")
	}

	h.prepare(final("No change."))
	h.createTitled("d", "Digest", "So far") // a reference note, there now
	if h.ready() {
		t.Fatal("kept past a reference note")
	}

	h.prepare(call("get_note", `{"id": "x"}`), final("No change."))
	h.update("y", "unrelated", nil)
	if !h.ready() {
		t.Fatal("thrown away by a note it didn't read")
	}
	h.update("x", "Milk #food, bread", nil)
	if h.ready() {
		t.Fatal("kept past a note it read")
	}

	h.prepare(call("search_notes", `{"query": "milk"}`), final("No change."))
	h.update("y", "unrelated again", nil)
	if h.ready() {
		t.Fatal("kept past a write, after searching")
	}

	h.prepare(final("No change."))
	h.agent("work", agent.NoteCreated)
	if h.ready() {
		t.Fatal("kept past the agent's setup")
	}
}

func TestAheadStillUsableJustAfterItsThrownAway(t *testing.T) {
	h := aheadHarness(t, "Digest")
	p, _ := h.prepare(final("No change."))
	h.createTitled("d", "Digest", "")
	if h.ready() {
		t.Fatal("kept")
	}
	if err := h.runner.UsePrepared(ctx, home, p.ID, "n1", "work", 1); err != nil {
		t.Fatalf("a new note asked for as it went: %v", err)
	}

	p, _ = h.prepare(final("No change."))
	h.createTitled("d2", "Digest", "")
	h.advance(2 * time.Minute)
	if err := h.runner.UsePrepared(ctx, home, p.ID, "n2", "work", 1); !errors.Is(err, agent.ErrPreparedGone) {
		t.Fatalf("used long after: %v", err)
	}
}

func TestAheadExpires(t *testing.T) {
	h := aheadHarness(t)
	p, _ := h.prepare(final("No change."))
	if want := h.clock.Add(agent.AheadFor).UnixMilli(); p.ExpiresAt != want {
		t.Fatalf("expires %d, want %d", p.ExpiresAt, want)
	}
	h.advance(agent.AheadFor)
	h.runner.PrepareNow(ctx, home, "work", 1)
	if h.model.calls() != 2 {
		t.Fatal("an expired result was kept")
	}
}

func TestAheadNeverPastMidnight(t *testing.T) {
	h := aheadHarness(t)
	// 23:50 in the account's zone, UTC.
	h.clock = time.Date(2026, 11, 11, 23, 50, 0, 0, time.UTC)
	p, _ := h.prepare(final("No change."))
	if want := time.Date(2026, 11, 12, 0, 0, 0, 0, time.UTC).UnixMilli(); p.ExpiresAt != want {
		t.Fatalf("expires %d, want %d", p.ExpiresAt, want)
	}
}

func TestARuleThatChangesOtherNotesRunsLive(t *testing.T) {
	h := aheadHarness(t)
	h.createTitled("x", "Log", "")
	if _, ok := h.prepare(call("insert", `{"id": "x", "at": "end", "text": "A new note."}`)); ok {
		t.Fatal("ready")
	}
	// Known now: not even tried.
	if _, ok := h.prepare(); ok || h.model.calls() != 1 {
		t.Fatal("tried again")
	}
	h.agent("work", agent.NoteCreated)
	if _, ok := h.prepare(call("create_note", `{"title": "A", "content": "B"}`)); ok {
		t.Fatal("ready")
	}
	if h.content("x") != "" || len(h.allRuns()) != 0 || h.model.calls() != 2 {
		t.Fatalf("x %q, runs %+v, calls %d", h.content("x"), h.allRuns(), h.model.calls())
	}
	// Set up again, it's tried again.
	h.agent("work", agent.NoteCreated)
	h.prepare(final("No change."))
	if !h.ready() {
		t.Fatal("not tried again")
	}
}

func TestAheadNeedsTheRule(t *testing.T) {
	h := aheadHarness(t)
	h.agent("work", agent.EditStarted)
	if _, ok := h.prepare(final("No change.")); ok || h.model.calls() != 0 {
		t.Fatal("prepared for a space that doesn't act on new notes")
	}
}

func TestUsingAheadIsTheUsersOwn(t *testing.T) {
	h := aheadHarness(t)
	p, _ := h.prepare(final("No change."))
	if err := h.runner.UsePrepared(ctx, home, p.ID, "n1", "work", 2); !errors.Is(err, agent.ErrPreparedGone) {
		t.Fatalf("another user's: %v", err)
	}
	if err := h.runner.UsePrepared(ctx, home, p.ID, "n1", "home", 1); !errors.Is(err, agent.ErrPreparedGone) {
		t.Fatalf("another space's: %v", err)
	}
}

func TestDropAhead(t *testing.T) {
	h := aheadHarness(t)
	p, _ := h.prepare(final("No change."))
	h.runner.DropAhead()
	if err := h.runner.UsePrepared(ctx, home, p.ID, "n1", "work", 1); !errors.Is(err, agent.ErrPreparedGone) {
		t.Fatalf("used after the provider changed: %v", err)
	}
}
