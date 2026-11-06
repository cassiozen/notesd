package agent_test

import (
	"errors"
	"strings"
	"testing"

	"notes/server/internal/agent"
	"notes/server/internal/llm"
	"notes/server/internal/notes"
)

func TestRequestRun(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.EditStarted, agent.NoteCreated, agent.NewNoteDone)
	h.create("n1", "work", "Milk")
	h.create("sealed", "work", "notesd:locked:v1:"+strings.Repeat("A", 60))
	h.create("h1", "home", "x")
	req := func(tr agent.Trigger, note, space string) error {
		_, err := h.d.Agent.RequestRun(ctx, agent.RunRequest{Trigger: tr, NoteID: note, SpaceID: space})
		return err
	}
	var in *agent.InputError
	for _, tc := range []struct {
		name string
		err  error
		want error
	}{
		{"not an editor trigger", req(agent.NoteDeleted, "n1", ""), nil},
		{"menu not set up", req(agent.Menu, "n1", ""), agent.ErrTriggerOff},
		{"no agent there", req(agent.EditStarted, "h1", ""), agent.ErrAgentOff},
		{"locked", req(agent.EditStarted, "sealed", ""), agent.ErrNoteLocked},
		{"unknown note", req(agent.EditStarted, "nope", ""), agent.ErrNoteGone},
		{"a draft needs its space", req(agent.NoteCreated, "draft", ""), agent.ErrNoteGone},
		{"a draft in a space that isn't", req(agent.NoteCreated, "draft", "gone"), agent.ErrSpaceNotFound},
	} {
		if tc.want == nil {
			if !errors.As(tc.err, &in) || in.Field != "trigger" {
				t.Errorf("%s: %v", tc.name, tc.err)
			}
		} else if !errors.Is(tc.err, tc.want) {
			t.Errorf("%s: %v", tc.name, tc.err)
		}
	}
	// Queued before the store has a note to check it against.
	if err := req(agent.NoteCreated, "a/b", "work"); !errors.As(err, &in) || in.Field != "noteId" {
		t.Errorf("not a note id: %v", err)
	}

	// A session's trigger first, then two asks for one run: it's one, and goes ahead.
	h.advance(agent.IdleAfter)
	if h.close() != 1 {
		t.Fatal("n1's session")
	}
	first, err := h.d.Agent.RequestRun(ctx, agent.RunRequest{Trigger: agent.EditStarted, NoteID: "n1", ConnectionID: "c_1"})
	again, _ := h.d.Agent.RequestRun(ctx, agent.RunRequest{Trigger: agent.EditStarted, NoteID: "n1"})
	if err != nil || first != again {
		t.Fatalf("asked twice: %s %s %v", first, again, err)
	}
	if r := h.next(); r.Trigger != string(agent.EditStarted) {
		t.Fatalf("ran first: %+v", r)
	}
}

func TestNoteCreatedOnADraft(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NoteCreated, agent.NewNoteDone)
	var updates []agent.RunUpdate
	h.onRun = func(u agent.RunUpdate) { updates = append(updates, u) }
	question := "## What went well today?\n"
	// The agent asks in a new, blank note, which the editor then saves.
	asks := func(id string) {
		t.Helper()
		h.openInEditor(id, "", ip(0)).Title = ""
		if _, err := h.d.Agent.RequestRun(ctx, agent.RunRequest{Trigger: agent.NoteCreated, NoteID: id, SpaceID: "work"}); err != nil {
			t.Fatal(err)
		}
		h.model.steps = append(h.model.steps, call("insert", `{"at": "start", "text": "## What went well today?"}`), final("Asked."))
		h.next()
		if got := h.editor.notes[id].Content; got != question {
			t.Fatalf("editor: %q", got)
		}
		space := "work"
		if _, err := h.d.Notes.Create(ctx, notes.CreateInput{OperationID: h.op(), ID: id, Content: question, SpaceID: &space}); err != nil {
			t.Fatal(err)
		}
	}
	asks("left")
	if len(updates) != 2 || updates[0].Status != agent.StatusRunning || updates[1].Status != agent.StatusDone ||
		updates[1].Outcome != agent.OutcomeEdited || updates[1].NoteID != "left" {
		t.Fatalf("updates: %+v", updates)
	}
	asks("answered")
	h.update("answered", question+"\nThe walk.", nil)
	h.advance(agent.IdleAfter)
	// Only the note the user wrote in ends in a trigger, not the one with just the agent's question.
	if h.close() != 1 {
		t.Fatal("triggers")
	}
	if runs := h.runs(); runs[len(runs)-1].Note != "answered" {
		t.Fatalf("runs: %+v", runs)
	}
}

func TestMoreAgentChangesDontMakeANewNoteTheUsers(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NoteCreated, agent.NewNoteDone, agent.Menu)
	space := "work"
	// The agent asks in a new, blank note, which the editor then saves.
	h.openInEditor("n1", "", ip(0)).Title = ""
	if _, err := h.d.Agent.RequestRun(ctx, agent.RunRequest{Trigger: agent.NoteCreated, NoteID: "n1", SpaceID: space}); err != nil {
		t.Fatal(err)
	}
	h.model.steps = append(h.model.steps, call("insert", `{"at": "start", "text": "## Q"}`), final("Asked."))
	h.next()
	if _, err := h.d.Notes.Create(ctx, notes.CreateInput{OperationID: h.op(), ID: "n1", Content: h.editor.notes["n1"].Content, SpaceID: &space}); err != nil {
		t.Fatal(err)
	}
	// The menu command changes it again, and the editor saves just that.
	if _, err := h.d.Agent.RequestRun(ctx, agent.RunRequest{Trigger: agent.Menu, NoteID: "n1"}); err != nil {
		t.Fatal(err)
	}
	h.model.steps = append(h.model.steps, call("insert", `{"at": "end", "text": "#journal"}`), final("Tagged."))
	h.next()
	h.update("n1", h.editor.notes["n1"].Content, nil)
	n, err := h.d.Notes.Get(ctx, "n1")
	if err != nil {
		t.Fatal(err)
	}
	if err := h.d.Agent.EditorClosed(ctx, "n1", n.Revision); err != nil {
		t.Fatal(err)
	}
	h.advance(agent.IdleAfter)
	h.close()
	if runs := h.runs(); len(runs) != 2 || h.sessions() != 0 {
		t.Fatalf("runs %+v, sessions %d", runs, h.sessions())
	}
}

// editorRun runs the trigger the user's editor asked for on note id, as
// the model's steps script it, and returns it.
func (h *harness) editorRun(trigger agent.Trigger, id string, steps ...func(llm.ChatRequest) (*llm.ChatResponse, error)) fullRun {
	h.t.Helper()
	if _, err := h.d.Agent.RequestRun(ctx, agent.RunRequest{Trigger: trigger, NoteID: id, SpaceID: "work"}); err != nil {
		h.t.Fatal(err)
	}
	h.model.steps = append(h.model.steps, steps...)
	h.next()
	runs := h.allRuns()
	return runs[len(runs)-1]
}

func TestEditorTriggersStartOverFromTheEditor(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NoteCreated)
	// Never saved: the store doesn't have it, and needn't.
	h.openInEditor("n1", "", ip(0)).Title = ""
	r := h.editorRun(agent.NoteCreated, "n1",
		func(llm.ChatRequest) (*llm.ChatResponse, error) {
			h.editor.notes["n1"].Content = "Dear diary" // the user types first
			return toolReply("insert", `{"at": "start", "text": "## Today"}`), nil
		},
		call("insert", `{"at": "start", "text": "## Today"}`),
		final("Added a heading."),
	)
	if r.Status != agent.StatusDone || h.editor.notes["n1"].Content != "## Today\n\nDear diary" {
		t.Fatalf("run %+v, editor %q", r, h.editor.notes["n1"].Content)
	}
	if _, err := h.d.Notes.Get(ctx, "n1"); !errors.Is(err, notes.ErrNotFound) {
		t.Fatalf("stored: %v", err)
	}
}

func TestEditorTriggersStopWithoutTheirEditor(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NoteCreated, agent.EditStarted, agent.Menu)
	h.create("n1", "work", "Milk")

	// Closed before the run started: the store's copy isn't used instead.
	h.openInEditor("other", "", nil) // an editor connection, without n1
	if r := h.editorRun(agent.EditStarted, "n1"); r.Status != agent.StatusSkipped || r.Failure != agent.FailNoteClosed ||
		r.message != "The note was closed before the agent got to it." {
		t.Fatalf("before: %+v", r)
	}

	// Closed while it worked: its change doesn't land in the store either.
	h.openInEditor("n1", "Milk", nil)
	r := h.editorRun(agent.Menu, "n1", func(llm.ChatRequest) (*llm.ChatResponse, error) {
		delete(h.editor.notes, "n1")
		return toolReply("insert", `{"at": "end", "text": "#groceries"}`), nil
	})
	if r.Status != agent.StatusSkipped || r.Failure != agent.FailNoteClosed ||
		r.message != "The note was closed while the agent worked on it." || h.content("n1") != "Milk" {
		t.Fatalf("while: %+v, stored %q", r, h.content("n1"))
	}

	// Locked in its editor: nothing of it is sent.
	h.openInEditor("n1", "Milk", nil)
	h.editor.locked = true
	calls := h.model.calls()
	if r := h.editorRun(agent.Menu, "n1"); r.Status != agent.StatusSkipped || r.Failure != agent.FailNoteLocked || h.model.calls() != calls {
		t.Fatalf("locked: %+v", r)
	}
}
