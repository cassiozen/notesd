package agent_test

import (
	"errors"
	"strings"
	"testing"

	"notes/server/internal/agent"
	"notes/server/internal/llm"
	"notes/server/internal/notes"
)

func (h *harness) lastRunID() string {
	h.t.Helper()
	runs, _, err := h.d.Agent.Runs(ctx, agent.RunFilter{}, 1, "")
	if err != nil || len(runs) == 0 {
		h.t.Fatalf("no run: %v", err)
	}
	return runs[0].ID
}

func (h *harness) undo(id string) (agent.UndoResult, error) {
	return h.d.Agent.Undo(ctx, h.d.Notes, id)
}

func TestUndoAnEdit(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone, agent.EditDone)
	h.tagged("n1")
	id := h.lastRunID()
	res, err := h.undo(id)
	if err != nil || len(res.Undone) != 1 || len(res.Skipped) != 0 {
		t.Fatalf("undo: %+v %v", res, err)
	}
	if got := h.content("n1"); got != "Milk" {
		t.Fatalf("content: %q", got)
	}
	d, _ := h.d.Agent.Run(ctx, id)
	if d.Outcome != agent.OutcomeUndone || d.Edits[0].Undoable || d.Edits[0].Blocked != agent.BlockedUndone {
		t.Fatalf("after undo: %+v", d.Run)
	}
	// Undo is the agent's write: no session, no trigger, no new edit.
	h.advance(agent.IdleAfter)
	h.close()
	if h.sessions() != 0 || len(h.allRuns()) != 1 {
		t.Fatal("the undo started a session or queued a run")
	}
	var edits int
	if err := h.db.R.QueryRow(`SELECT count(*) FROM agent_run_edits`).Scan(&edits); err != nil || edits != 1 {
		t.Fatalf("edits: %d %v", edits, err)
	}
	// Again: nothing left.
	res, err = h.undo(id)
	if !errors.Is(err, agent.ErrNothingToUndo) || len(res.Skipped) != 1 || res.Skipped[0].Reason != agent.BlockedUndone {
		t.Fatalf("again: %+v %v", res, err)
	}
}

func TestUndoSkipsANoteChangedSince(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.tagged("n1")
	h.update("n1", "Milk\n\n#tag\nmine", nil)
	res, err := h.undo(h.lastRunID())
	if !errors.Is(err, agent.ErrNothingToUndo) || len(res.Skipped) != 1 || res.Skipped[0].Reason != agent.BlockedChanged {
		t.Fatalf("undo: %+v %v", res, err)
	}
	if got := h.content("n1"); got != "Milk\n\n#tag\nmine" {
		t.Fatalf("the undo wrote over the user: %q", got)
	}
}

func TestUndoPartly(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "Milk")
	h.model.steps = append(h.model.steps,
		call("insert", `{"at": "end", "text": "#tag"}`),
		call("create_note", `{"title": "Digest", "content": "Milk"}`),
		final("Done."))
	h.next()
	id := h.lastRunID()
	d, _ := h.d.Agent.Run(ctx, id)
	var created string
	for _, e := range d.Edits {
		if e.Kind == "create" {
			created = e.NoteID
		}
	}
	h.update(created, "Milk, eggs", nil)
	res, err := h.undo(id)
	if err != nil || len(res.Undone) != 1 || res.Undone[0] != "n1" || len(res.Skipped) != 1 || res.Skipped[0].NoteID != created {
		t.Fatalf("undo: %+v %v", res, err)
	}
	if d, _ := h.d.Agent.Run(ctx, id); d.Outcome != agent.OutcomePartlyUndone {
		t.Fatalf("outcome: %s", d.Outcome)
	}
}

func TestUndoACreatedNote(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone, agent.NoteDeleted)
	h.done("n1", "Milk")
	h.model.steps = append(h.model.steps, call("create_note", `{"title": "Digest", "content": "Milk"}`), final("Made it."))
	h.next()
	id := h.lastRunID()
	d, _ := h.d.Agent.Run(ctx, id)
	created := d.Edits[0].NoteID
	if _, err := h.undo(id); err != nil {
		t.Fatal(err)
	}
	if n, err := h.d.Notes.Get(ctx, created); err != nil || n.DeletedAt == nil {
		t.Fatalf("created note: %+v %v", n, err)
	}
	if len(h.allRuns()) != 1 {
		t.Fatal("deleting it fired note deleted")
	}
}

func TestUndoWaitsForTheRun(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.tagged("n1")
	id := h.lastRunID()
	if _, err := h.db.W.Exec(`UPDATE agent_runs SET status = 'running'`); err != nil {
		t.Fatal(err)
	}
	if res, err := h.undo(id); !errors.Is(err, agent.ErrNothingToUndo) || res.Skipped[0].Reason != agent.BlockedRunning {
		t.Fatalf("undo: %+v %v", res, err)
	}
	if _, err := h.undo("run_nope"); !errors.Is(err, agent.ErrRunNotFound) {
		t.Fatalf("unknown: %v", err)
	}
}

func TestLockingOrDeletingRemovesTheAgentsCopies(t *testing.T) {
	sealed := "notesd:locked:v1:" + strings.Repeat("A", 60)
	t.Run("locked", func(t *testing.T) {
		h := newHarness(t)
		h.agent("work", agent.NewNoteDone)
		h.tagged("n1")
		id := h.lastRunID()
		h.update("n1", sealed, nil)
		d, _ := h.d.Agent.Run(ctx, id)
		if !d.Diffs[0].TextRemoved || len(d.Diffs[0].Lines) != 0 || d.Diffs[0].TitleAfter != "T" {
			t.Fatalf("diff: %+v", d.Diffs[0])
		}
		if res, err := h.undo(id); !errors.Is(err, agent.ErrNothingToUndo) || res.Skipped[0].Reason != agent.BlockedLocked {
			t.Fatalf("undo: %+v %v", res, err)
		}
	})
	t.Run("deleted", func(t *testing.T) {
		h := newHarness(t)
		h.agent("work", agent.NewNoteDone)
		h.tagged("n1")
		id := h.lastRunID()
		h.delete("n1")
		d, _ := h.d.Agent.Run(ctx, id)
		if !d.Diffs[0].TextRemoved || d.Diffs[0].TitleBefore != "" || len(d.Diffs[0].Lines) != 0 {
			t.Fatalf("diff: %+v", d.Diffs[0])
		}
		var leftover int
		if err := h.db.R.QueryRow(`SELECT count(*) FROM agent_run_edits WHERE before_content != '' OR after_content != ''`).
			Scan(&leftover); err != nil || leftover != 0 {
			t.Fatalf("text left: %d %v", leftover, err)
		}
	})
	t.Run("queued runs are skipped when the note is locked", func(t *testing.T) {
		h := newHarness(t)
		h.agent("work", agent.NewNoteDone)
		h.done("n1", "Milk")
		h.update("n1", sealed, nil)
		if r := h.only(); r.Status != agent.StatusSkipped || r.Failure != agent.FailNoteLocked {
			t.Fatalf("run: %+v", r)
		}
	})
}

func TestUndoACreatedNoteDeletedSince(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.done("n1", "Milk")
	h.model.steps = append(h.model.steps, call("create_note", `{"title": "Digest", "content": "Milk"}`), final("Made it."))
	h.next()
	id := h.lastRunID()
	d, _ := h.d.Agent.Run(ctx, id)
	h.delete(d.Edits[0].NoteID)
	if res, err := h.undo(id); !errors.Is(err, agent.ErrNothingToUndo) || res.Skipped[0].Reason != agent.BlockedDeleted {
		t.Fatalf("undo: %+v %v", res, err)
	}
}

func TestLockedDuringARunStopsIt(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.MovedOut)
	h.create("n1", "work", "Leaving")
	h.update("n1", "Leaving", func(in *notes.UpdateInput) { in.SpaceID = ptr("home") })
	h.model.steps = append(h.model.steps, func(llm.ChatRequest) (*llm.ChatResponse, error) {
		h.update("n1", "notesd:locked:v1:"+strings.Repeat("A", 60), nil)
		return toolReply("list_tags", `{}`), nil
	}, final("No change."))
	h.next()
	if r := h.only(); r.Status != agent.StatusFailed || r.Failure != agent.FailNoteLocked || h.model.calls() != 1 {
		t.Fatalf("run: %+v, calls %d", r, h.model.calls())
	}
}

func TestFinishedOrRecoveredRunsDropADeletedNotesText(t *testing.T) {
	moved := func(h *harness) {
		h.agent("work", agent.MovedOut)
		h.create("n1", "work", "Leaving")
		h.update("n1", "Leaving", func(in *notes.UpdateInput) { in.SpaceID = ptr("home") })
	}
	t.Run("finished", func(t *testing.T) {
		h := newHarness(t)
		moved(h)
		h.delete("n1")
		h.model.steps = append(h.model.steps, final("No change."))
		h.next()
		if runs := h.runs(); runs[0].Event.Title != nil || runs[0].Event.Content != nil {
			t.Fatalf("event: %+v", runs[0].Event)
		}
	})
	t.Run("recovered after a crash", func(t *testing.T) {
		h := newHarness(t)
		moved(h)
		if _, err := h.db.W.Exec(`UPDATE agent_runs SET status = 'running', attempts = 2`); err != nil {
			t.Fatal(err)
		}
		h.delete("n1")
		if err := h.d.Agent.RecoverRuns(ctx); err != nil {
			t.Fatal(err)
		}
		if runs := h.runs(); runs[0].Status != agent.StatusFailed || runs[0].Event.Title != nil || runs[0].Event.Content != nil {
			t.Fatalf("run: %+v", runs[0])
		}
	})
}
