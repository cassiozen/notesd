package agent_test

import (
	"context"
	"errors"
	"strings"
	"testing"

	"notes/server/internal/agent"
	"notes/server/internal/llm"
)

// fakeEditor is an editor with notes open, as the socket's would answer.
type fakeEditor struct {
	notes   map[string]*agent.EditorNote
	gone    bool
	changes []agent.EditorChange
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

func (e *fakeEditor) Apply(_ context.Context, id, _ string, ch agent.EditorChange) error {
	if e.gone {
		return agent.ErrEditorGone
	}
	n, ok := e.notes[id]
	switch {
	case !ok:
		return agent.ErrNotOpen
	case n.Title != ch.Before.Title || n.Content != ch.Before.Content:
		return agent.ErrEditorChanged
	}
	n.Title, n.Content, n.Revision = ch.After.Title, ch.After.Content, nil
	e.changes = append(e.changes, ch)
	return nil
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

	// The editor saves exactly the change: linked, and not the user writing.
	h.update("n1", after, nil)
	if h.sessions() != 0 {
		t.Fatal("the editor's save of the agent's change started a session")
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
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "Milk")
	n := h.openInEditor("n1", "Milk\n", nil)
	h.model.steps = append(h.model.steps, func(llm.ChatRequest) (*llm.ChatResponse, error) {
		n.Content = "Milk\nTea\n"
		return toolReply("insert", `{"at": "end", "text": "#tag"}`), nil
	}, call("insert", `{"at": "end", "text": "#tag"}`), final("Tagged."))
	h.next()
	if len(h.editor.changes) != 1 || n.Content != "Milk\nTea\n\n#tag\n" {
		t.Fatalf("changes %+v, editor %q", h.editor.changes, n.Content)
	}
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
