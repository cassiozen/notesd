package notes_test

import (
	"context"
	"database/sql"
	"errors"
	"testing"

	"notes/server/internal/notes"
	"notes/server/internal/spaces"
)

// recorder keeps the events each hook saw.
type recorder struct {
	written   []notes.Event
	committed []notes.Event
}

func record(s *notes.Store) *recorder {
	r := &recorder{}
	s.OnWrite(func(_ context.Context, _ *sql.Tx, ev notes.Event) error {
		r.written = append(r.written, ev)
		return nil
	})
	s.OnCommit(func(ev notes.Event) { r.committed = append(r.committed, ev) })
	return r
}

func (r *recorder) last(t *testing.T) notes.Event {
	t.Helper()
	if len(r.written) != len(r.committed) {
		t.Fatalf("%d written, %d committed", len(r.written), len(r.committed))
	}
	if len(r.committed) == 0 {
		t.Fatal("no event")
	}
	return r.committed[len(r.committed)-1]
}

func TestEvents(t *testing.T) {
	db, s := setup(t)
	if _, err := spaces.NewStore(db).Put(ctx, "work", "Work", nil); err != nil {
		t.Fatal(err)
	}
	rec := record(s)

	create(t, s, "n1", "Title", "Body")
	ev := rec.last(t)
	if ev.Kind != notes.EventCreated || ev.NoteID != "n1" || ev.OpID != "op-create-n1" || ev.Before != nil ||
		ev.After.Title != "Title" || ev.After.Content != "Body" || ev.After.Revision != 1 || ev.After.SpaceID != spaces.Default ||
		ev.Unchanged || ev.Agent != nil || ev.Origin != "" {
		t.Fatalf("create: %+v", ev)
	}

	work := "work"
	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "u1", ID: "n1", BaseRevision: 1, Title: "Title", Content: "Body 2", SpaceID: &work}); err != nil {
		t.Fatal(err)
	}
	ev = rec.last(t)
	if ev.Kind != notes.EventUpdated || ev.Before == nil || ev.Before.Content != "Body" || ev.Before.SpaceID != spaces.Default ||
		ev.After.Content != "Body 2" || ev.After.SpaceID != "work" || ev.After.Revision != 2 || !ev.SpaceChanged() || ev.Unchanged {
		t.Fatalf("move: %+v", ev)
	}

	// An agent's write says so, and where it came from.
	mark := &notes.AgentMark{RunID: "run_1", Only: true}
	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "u2", ID: "n1", BaseRevision: 2, Title: "Title", Content: "Body 3", Agent: mark, Origin: "import"}); err != nil {
		t.Fatal(err)
	}
	ev = rec.last(t)
	if ev.Agent == nil || ev.Agent.RunID != "run_1" || !ev.Agent.Only || ev.Origin != "import" || ev.SpaceChanged() || ev.After.SpaceID != "work" {
		t.Fatalf("agent update: %+v", ev)
	}

	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "u3", ID: "n1", BaseRevision: 3, Title: "Title", Content: "Body 3"}); err != nil {
		t.Fatal(err)
	}
	if ev = rec.last(t); !ev.Unchanged || ev.After.Revision != 3 {
		t.Fatalf("unchanged: %+v", ev)
	}

	// A replay emits nothing.
	n := len(rec.committed)
	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "u3", ID: "n1", BaseRevision: 3, Title: "Title", Content: "Body 3"}); err != nil {
		t.Fatal(err)
	}
	if len(rec.committed) != n || len(rec.written) != n {
		t.Fatal("a replay emitted an event")
	}

	// A conflict emits nothing either.
	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "u4", ID: "n1", BaseRevision: 1, Title: "x", Content: "y"}); conflictCode(err) != notes.CodeRevisionConflict {
		t.Fatalf("stale update: %v", err)
	}
	if len(rec.committed) != n {
		t.Fatal("a conflict emitted an event")
	}

	if _, err := s.Delete(ctx, notes.DeleteInput{OperationID: "d1", ID: "n1", BaseRevision: 3}); err != nil {
		t.Fatal(err)
	}
	ev = rec.last(t)
	if ev.Kind != notes.EventDeleted || ev.Before.Title != "Title" || ev.Before.Content != "Body 3" || ev.Before.SpaceID != "work" ||
		ev.After.DeletedAt == nil || ev.After.Content != "" || ev.After.SpaceID != "work" || ev.After.Revision != 4 || ev.Unchanged {
		t.Fatalf("delete: %+v", ev)
	}
	if _, err := s.Delete(ctx, notes.DeleteInput{OperationID: "d2", ID: "n1", BaseRevision: 1}); err != nil {
		t.Fatal(err)
	}
	if ev = rec.last(t); ev.Kind != notes.EventDeleted || !ev.Unchanged {
		t.Fatalf("already deleted: %+v", ev)
	}
}

func TestWriteHookErrorRollsBack(t *testing.T) {
	_, s := setup(t)
	boom := errors.New("boom")
	committed := 0
	s.OnWrite(func(context.Context, *sql.Tx, notes.Event) error { return boom })
	s.OnCommit(func(notes.Event) { committed++ })

	if _, err := s.Create(ctx, notes.CreateInput{OperationID: "o1", ID: "n1", Title: "T", Content: "C"}); !errors.Is(err, boom) {
		t.Fatalf("got %v", err)
	}
	if _, err := s.Get(ctx, "n1"); !errors.Is(err, notes.ErrNotFound) {
		t.Fatalf("the note was written: %v", err)
	}
	if committed != 0 {
		t.Fatal("commit hook ran")
	}
}

func TestWriteHookSeesTheWrite(t *testing.T) {
	_, s := setup(t)
	var seen string
	s.OnWrite(func(ctx context.Context, tx *sql.Tx, ev notes.Event) error {
		return tx.QueryRowContext(ctx, `SELECT content FROM notes WHERE id = ?`, ev.NoteID).Scan(&seen)
	})
	create(t, s, "n1", "T", "inside")
	if seen != "inside" {
		t.Fatalf("hook read %q", seen)
	}
}

func TestAgentMarkIsValidated(t *testing.T) {
	_, s := setup(t)
	_, err := s.Create(ctx, notes.CreateInput{OperationID: "o1", ID: "n1", Title: "T", Agent: &notes.AgentMark{RunID: "bad id"}})
	var v *notes.ValidationError
	if !errors.As(err, &v) || v.Field != "agent.runId" {
		t.Fatalf("got %v", err)
	}
}
