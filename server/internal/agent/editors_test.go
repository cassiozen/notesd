package agent_test

import (
	"context"
	"errors"
	"strings"
	"testing"

	"notes/server/internal/agent"
	"notes/server/internal/llm"
	"notes/server/internal/notes"
)

// fakeEditor is an editor with notes open, as the socket's would answer.
type fakeEditor struct {
	notes   map[string]*agent.EditorNote
	gone    bool
	changes []agent.EditorChange
	// rewrite is the editor's Markdown writing some text its own way.
	rewrite func(string) string
}

func (h *harness) openInEditor(id, content string, cursor *int) *agent.EditorNote {
	if h.editor == nil {
		h.editor = &fakeEditor{notes: map[string]*agent.EditorNote{}}
	}
	n := &agent.EditorNote{SpaceID: "work", Title: "T", Content: content, Cursor: cursor}
	h.editor.notes[id] = n
	return n
}

func (e *fakeEditor) Read(_ context.Context, id, _ string) (agent.EditorNote, error) {
	if e.gone {
		return agent.EditorNote{}, agent.ErrEditorGone
	}
	n, ok := e.notes[id]
	if !ok {
		return agent.EditorNote{}, agent.ErrNotOpen
	}
	return *n, nil
}

func (e *fakeEditor) Apply(_ context.Context, id, _ string, ch agent.EditorChange) (agent.EditorApplied, error) {
	if e.gone {
		return agent.EditorApplied{}, agent.ErrEditorGone
	}
	n, ok := e.notes[id]
	if !ok {
		return agent.EditorApplied{}, agent.ErrNotOpen
	}
	before := agent.Fields{Title: n.Title, Content: n.Content}
	after, ok := ch.MadeTo(before, n.Cursor)
	if !ok {
		return agent.EditorApplied{}, agent.ErrEditorChanged
	}
	revision := n.Revision
	n.Title, n.Content, n.Revision = after.Title, after.Content, nil
	if e.rewrite != nil {
		n.Content = e.rewrite(n.Content)
	}
	e.changes = append(e.changes, ch)
	return agent.EditorApplied{Before: before, Revision: revision, Shows: agent.Fields{Title: n.Title, Content: n.Content}}, nil
}

func ip(n int) *int { return &n }

func TestEditsLandInTheOpenEditor(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone, agent.EditDone)
	h.done("n1", "Milk")
	// The user typed on; the editor hasn't saved it.
	h.openInEditor("n1", "Milk\nEggs\n", nil)
	h.model.steps = append(h.model.steps, call("insert", `{"at": "end", "text": "#groceries"}`), final("Tagged."))
	h.next()
	if user := h.model.requests[0].Messages[1].Content; !strings.Contains(user, "Eggs") {
		t.Fatalf("the run didn't see the editor's text: %s", user)
	}
	after := "Milk\nEggs\n\n#groceries\n"
	if len(h.editor.changes) != 1 || h.editor.changes[0].After.Content != after || h.content("n1") != "Milk" {
		t.Fatalf("changes %+v, stored %q", h.editor.changes, h.content("n1"))
	}
	id := h.lastRunID()
	d, _ := h.d.Agent.Run(ctx, id)
	if d.Outcome != agent.OutcomeEdited || d.Edits[0].Blocked != agent.BlockedNotSaved {
		t.Fatalf("before the save: %+v", d.Run)
	}

	// The editor saves exactly the change: linked. With the user's unsaved
	// line in it, it's their writing too.
	h.update("n1", after, nil)
	if h.sessions() != 1 {
		t.Fatal("the user's line didn't count")
	}
	if _, err := h.undo(id); err != nil {
		t.Fatal(err)
	}
	if got := h.content("n1"); got != "Milk\nEggs\n" {
		t.Fatalf("undo put back %q; the user's unsaved line belongs there", got)
	}
}

func TestEditorSaveWithTypingIsntLinked(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone, agent.EditDone)
	h.done("n1", "Milk")
	h.openInEditor("n1", "Milk\n", nil)
	h.model.steps = append(h.model.steps, call("insert", `{"at": "end", "text": "#groceries"}`), final("Tagged."))
	h.next()
	h.update("n1", "Milk\n\n#groceries\nand bread", nil)
	if h.sessions() != 1 {
		t.Fatal("the user's typing didn't count")
	}
	if res, err := h.undo(h.lastRunID()); !errors.Is(err, agent.ErrNothingToUndo) || res.Skipped[0].Reason != agent.BlockedNotSaved {
		t.Fatalf("undo: %+v %v", res, err)
	}
}

func TestEditorChangedUnderTheRun(t *testing.T) {
	t.Run("the change is made to what it shows now", func(t *testing.T) {
		h := newHarness(t)
		h.agent("work", agent.NewNoteDone)
		h.done("n1", "Milk")
		n := h.openInEditor("n1", "Milk\n", nil)
		h.model.steps = append(h.model.steps, func(llm.ChatRequest) (*llm.ChatResponse, error) {
			n.Content = "Milk\nTea\n"
			return toolReply("insert", `{"at": "end", "text": "#tag"}`), nil
		}, final("Tagged."))
		h.next()
		if len(h.editor.changes) != 1 || n.Content != "Milk\nTea\n\n#tag\n" || h.model.calls() != 2 {
			t.Fatalf("changes %+v, editor %q, %d calls", h.editor.changes, n.Content, h.model.calls())
		}
		// Recorded from what the editor showed, the user's typing included.
		var before string
		if err := h.db.R.QueryRow(`SELECT before_content FROM agent_run_edits WHERE note_id = 'n1'`).Scan(&before); err != nil || before != "Milk\nTea\n" {
			t.Fatalf("recorded before %q: %v", before, err)
		}
	})
	t.Run("a find the user changed comes back with the note as it is now", func(t *testing.T) {
		h := newHarness(t)
		h.agent("work", agent.NewNoteDone)
		h.done("n1", "Milk")
		n := h.openInEditor("n1", "Milk\n", nil)
		h.model.steps = append(h.model.steps, func(llm.ChatRequest) (*llm.ChatResponse, error) {
			n.Content = "Oat milk\n"
			return toolReply("edit_note", `{"find": "Milk", "replace": "Milk, 1 l"}`), nil
		}, final("No change."))
		h.next()
		if len(h.editor.changes) != 0 || n.Content != "Oat milk\n" {
			t.Fatalf("changes %+v, editor %q", h.editor.changes, n.Content)
		}
		if got := lastToolResult(h.model.requests[1]); !strings.Contains(got, `"content":"Oat milk\n"`) {
			t.Fatalf("told: %s", got)
		}
	})
}

func TestEditorGoneMidChange(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "Milk")
	h.openInEditor("n1", "Milk\n", nil)
	h.model.steps = append(h.model.steps, func(llm.ChatRequest) (*llm.ChatResponse, error) {
		h.editor.gone = true
		return toolReply("insert", `{"at": "end", "text": "#tag"}`), nil
	}, final("Tagged."))
	h.next()
	if r := h.only(); r.Status != agent.StatusFailed || r.Failure != agent.FailEditorGone {
		t.Fatalf("run: %+v", r)
	}
	// Not written to the store instead, under the user's unsaved typing.
	if got := h.content("n1"); got != "Milk" {
		t.Fatalf("stored: %q", got)
	}
}

func TestInsertAtTheCursor(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "Dear Sam,")
	h.openInEditor("n1", "Dear Sam,\n\nBest\n", ip(len([]rune("Dear Sam,\n"))))
	h.model.steps = append(h.model.steps, call("get_cursor", `{}`), call("insert", `{"at": "cursor", "text": "Hello!"}`), final("Done."))
	h.next()
	if got := lastToolResult(h.model.requests[1]); got != `{"after":"\nBest\n","before":"Dear Sam,\n"}` {
		t.Fatalf("get_cursor: %s", got)
	}
	if got := h.editor.notes["n1"].Content; got != "Dear Sam,\nHello!\nBest\n" {
		t.Fatalf("editor: %q", got)
	}
}

func TestEditorSaveOfOnlyTheAgentsChange(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone, agent.EditDone)
	h.done("n1", "Milk")
	h.openInEditor("n1", "Milk", nil)
	h.model.steps = append(h.model.steps,
		call("insert", `{"at": "end", "text": "#groceries"}`),
		call("set_title", `{"title": "Shopping"}`),
		final("Tagged."))
	h.next()
	// Two changes, one save: the agent's alone, so no writing session.
	h.update("n1", "Milk\n\n#groceries\n", func(in *notes.UpdateInput) { in.Title = "Shopping" })
	if h.sessions() != 0 {
		t.Fatal("the editor's save of the agent's change started a session")
	}
	if _, err := h.undo(h.lastRunID()); err != nil || h.content("n1") != "Milk" {
		t.Fatalf("undo: %v %q", err, h.content("n1"))
	}
}

func TestClosingTheEditorAfterItSavedTheAgentsChange(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone, agent.EditDone)
	h.done("n1", "Milk")
	// The user's writing: a session.
	h.update("n1", "Milk\nEggs", nil)
	h.openInEditor("n1", "Milk\nEggs", nil)
	h.model.steps = append(h.model.steps, call("insert", `{"at": "end", "text": "#groceries"}`), final("Tagged."))
	h.next()
	h.update("n1", "Milk\nEggs\n\n#groceries\n", nil)
	n, err := h.d.Notes.Get(ctx, "n1")
	if err != nil {
		t.Fatal(err)
	}
	// The editor has all the server has: the session ends now, not when idle.
	if err := h.d.Agent.EditorClosed(ctx, "n1", n.Revision); err != nil || h.sessions() != 0 {
		t.Fatalf("sessions %d, %v", h.sessions(), err)
	}
	h.wantRuns("newNoteDone:n1@work", "editDone:n1@work")
}

func TestLockingOrDeletingDropsTheEditorsHash(t *testing.T) {
	for _, tc := range []struct {
		name string
		do   func(h *harness)
	}{
		{"locked", func(h *harness) { h.update("n1", "notesd:locked:v1:"+strings.Repeat("A", 60), nil) }},
		{"deleted", func(h *harness) { h.delete("n1") }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h := newHarness(t)
			h.agent("work", agent.NewNoteDone)
			h.done("n1", "Milk")
			h.openInEditor("n1", "Milk", nil)
			h.model.steps = append(h.model.steps, call("insert", `{"at": "end", "text": "#tag"}`), final("Tagged."))
			h.next()
			hashes := func() (n int) {
				if err := h.db.R.QueryRow(`SELECT count(*) FROM agent_run_edits WHERE applied_from IS NOT NULL`).Scan(&n); err != nil {
					t.Fatal(err)
				}
				return n
			}
			if hashes() != 1 {
				t.Fatal("no hash to drop")
			}
			tc.do(h)
			if n := hashes(); n != 0 {
				t.Fatalf("%d hashes left", n)
			}
		})
	}
}
