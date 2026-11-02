package agent_test

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"notes/server/internal/agent"
	"notes/server/internal/database"
	"notes/server/internal/notes"
	"notes/server/internal/spaces"
	"notes/server/internal/userdata"
)

// fixture is an account's database with its stores wired as the server
// wires them, and one clock for both.
type fixture struct {
	t     *testing.T
	db    *database.DB
	d     *userdata.Data
	mu    sync.Mutex
	clock time.Time
	ops   int
}

func newFixture(t *testing.T) *fixture {
	t.Helper()
	db, err := database.Open(ctx, filepath.Join(t.TempDir(), "notes.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	f := &fixture{t: t, db: db, d: userdata.New(db), clock: time.UnixMilli(1_700_000_000_000)}
	now := func() time.Time { f.mu.Lock(); defer f.mu.Unlock(); return f.clock }
	f.d.Notes.SetClock(now)
	f.d.Agent.SetClock(now)
	for _, id := range []string{"work", "home"} {
		if _, err := f.d.Spaces.Put(ctx, id, id, nil); err != nil {
			t.Fatal(err)
		}
	}
	return f
}

func (f *fixture) advance(d time.Duration) { f.mu.Lock(); f.clock = f.clock.Add(d); f.mu.Unlock() }

func (f *fixture) op() string { f.ops++; return fmt.Sprintf("op-%d", f.ops) }

func (f *fixture) agent(space string, triggers ...agent.Trigger) {
	f.t.Helper()
	c := agent.Config{SpaceID: space, Enabled: true, Prompt: "Space.", Triggers: map[agent.Trigger]string{}}
	for _, tr := range triggers {
		c.Triggers[tr] = "Do " + string(tr) + "."
	}
	if tr := c.Triggers[agent.Menu]; tr != "" {
		c.MenuLabel = "Menu"
	}
	if _, err := f.d.Agent.Put(ctx, c); err != nil {
		f.t.Fatal(err)
	}
}

func (f *fixture) create(id, space, content string) {
	f.t.Helper()
	if _, err := f.d.Notes.Create(ctx, notes.CreateInput{OperationID: f.op(), ID: id, Title: "T", Content: content, SpaceID: &space}); err != nil {
		f.t.Fatal(err)
	}
}

func (f *fixture) update(id, content string, change func(*notes.UpdateInput)) {
	f.t.Helper()
	n, err := f.d.Notes.Get(ctx, id)
	if err != nil {
		f.t.Fatal(err)
	}
	in := notes.UpdateInput{OperationID: f.op(), ID: id, BaseRevision: n.Revision, Title: n.Title, Content: content}
	if change != nil {
		change(&in)
	}
	if _, err := f.d.Notes.Update(ctx, in); err != nil {
		f.t.Fatal(err)
	}
}

func (f *fixture) delete(id string) {
	f.t.Helper()
	n, _ := f.d.Notes.Get(ctx, id)
	if _, err := f.d.Notes.Delete(ctx, notes.DeleteInput{OperationID: f.op(), ID: id, BaseRevision: n.Revision}); err != nil {
		f.t.Fatal(err)
	}
}

func (f *fixture) close() int {
	f.t.Helper()
	n, err := f.d.Agent.CloseIdleSessions(ctx)
	if err != nil {
		f.t.Fatal(err)
	}
	return n
}

type run struct {
	Status, Trigger, Space, Note, Failure string
	Event                                 agent.Event
}

func (f *fixture) runs() []run {
	f.t.Helper()
	rows, err := f.db.R.Query(`SELECT status, trigger, space_id, note_id, failure, event_json FROM agent_runs ORDER BY created_at, rowid`)
	if err != nil {
		f.t.Fatal(err)
	}
	defer rows.Close()
	var out []run
	for rows.Next() {
		var r run
		var ev string
		if err := rows.Scan(&r.Status, &r.Trigger, &r.Space, &r.Note, &r.Failure, &ev); err != nil {
			f.t.Fatal(err)
		}
		if err := json.Unmarshal([]byte(ev), &r.Event); err != nil {
			f.t.Fatal(err)
		}
		out = append(out, r)
	}
	return out
}

func (f *fixture) sessions() int {
	var n int
	if err := f.db.R.QueryRow(`SELECT count(*) FROM agent_sessions`).Scan(&n); err != nil {
		f.t.Fatal(err)
	}
	return n
}

func (f *fixture) wantRuns(want ...string) []run {
	f.t.Helper()
	got := f.runs()
	var names []string
	for _, r := range got {
		names = append(names, r.Trigger+":"+r.Note+"@"+r.Space)
	}
	if fmt.Sprint(names) != fmt.Sprint(want) {
		f.t.Fatalf("runs: got %v, want %v", names, want)
	}
	return got
}

func TestNoAgentNoSessions(t *testing.T) {
	f := newFixture(t)
	f.agent("home", agent.NewNoteDone) // another space's agent
	f.create("n1", "work", "a")
	f.update("n1", "b", nil)
	f.delete("n1")
	f.advance(time.Hour)
	if f.close() != 0 || f.sessions() != 0 {
		t.Fatal("a space without an agent had a session")
	}
	f.wantRuns()
}

func TestNewNoteDone(t *testing.T) {
	f := newFixture(t)
	f.agent("work", agent.NewNoteDone, agent.EditDone)
	f.create("n1", "work", "a")
	f.advance(time.Minute)
	f.update("n1", "ab", nil)
	f.advance(agent.IdleAfter - time.Second)
	if f.close() != 0 {
		t.Fatal("closed before idle")
	}
	f.advance(time.Second)
	if f.close() != 1 || f.sessions() != 0 {
		t.Fatal("didn't close once idle")
	}
	runs := f.wantRuns("newNoteDone:n1@work")
	if runs[0].Status != agent.StatusQueued || runs[0].Event.Title != nil {
		t.Fatalf("run: %+v", runs[0])
	}

	// The next change starts an edit session; it fires once however many saves it has.
	f.update("n1", "abc", nil)
	f.update("n1", "abcd", nil)
	f.advance(agent.IdleAfter)
	f.close()
	f.close()
	f.wantRuns("newNoteDone:n1@work", "editDone:n1@work")
}

func TestOnlyTheConfiguredDoneTrigger(t *testing.T) {
	f := newFixture(t)
	f.create("n1", "work", "a") // before the agent: no session
	f.agent("work", agent.EditDone)
	f.create("n2", "work", "new")
	f.update("n1", "edited", nil)
	if f.sessions() != 1 {
		t.Fatalf("sessions: %d", f.sessions())
	}
	f.advance(agent.IdleAfter)
	f.close()
	f.wantRuns("editDone:n1@work")
}

func TestAgentChangesAndNonEditsDontCount(t *testing.T) {
	f := newFixture(t)
	f.agent("work", agent.NewNoteDone, agent.EditDone, agent.NoteDeleted, agent.MovedIn)
	f.create("n1", "home", "a")

	// The agent's own write, and a save holding only its change.
	f.update("n1", "b", func(in *notes.UpdateInput) { in.Agent = &notes.AgentMark{RunID: "run_1", Only: true} })
	// A device's first sync, and an import.
	f.update("n1", "c", func(in *notes.UpdateInput) { in.Origin = "initial" })
	if _, err := f.d.Notes.Create(ctx, notes.CreateInput{OperationID: f.op(), ID: "n2", Title: "T", Origin: "import", SpaceID: ptr("work")}); err != nil {
		t.Fatal(err)
	}
	// Moving in by import doesn't fire moved in either.
	f.update("n1", "c", func(in *notes.UpdateInput) { in.SpaceID = ptr("work"); in.Origin = "import" })
	// Saving the same text again changes nothing.
	f.update("n1", "c", nil)
	if f.sessions() != 0 {
		t.Fatalf("sessions: %d", f.sessions())
	}

	// A save with the agent's change and the user's typing is the user's.
	f.update("n1", "d", func(in *notes.UpdateInput) { in.Agent = &notes.AgentMark{RunID: "run_1", Only: false} })
	if f.sessions() != 1 {
		t.Fatalf("sessions after a mixed save: %d", f.sessions())
	}
	f.advance(agent.IdleAfter)
	f.close()
	f.wantRuns("editDone:n1@work")
}

func TestDeleteEndsSessionAndFires(t *testing.T) {
	f := newFixture(t)
	f.agent("work", agent.NewNoteDone, agent.NoteDeleted)
	f.create("n1", "work", "Milk")
	f.delete("n1")
	if f.sessions() != 0 {
		t.Fatal("session kept after delete")
	}
	f.advance(agent.IdleAfter)
	f.close()
	runs := f.wantRuns("noteDeleted:n1@work")
	if runs[0].Event.Title == nil || *runs[0].Event.Title != "T" || *runs[0].Event.Content != "Milk" {
		t.Fatalf("deleted note's snapshot: %+v", runs[0].Event)
	}
}

func TestMoves(t *testing.T) {
	f := newFixture(t)
	f.agent("work", agent.MovedOut, agent.EditDone)
	f.agent("home", agent.MovedIn, agent.EditDone)
	f.create("n1", "work", "Plan")
	f.update("n1", "Plan", func(in *notes.UpdateInput) { in.SpaceID = ptr("home") })
	runs := f.wantRuns("movedOut:n1@work", "movedIn:n1@home")
	if out := runs[0].Event; out.FromSpaceID != "work" || out.ToSpaceID != "home" || out.Content == nil || *out.Content != "Plan" {
		t.Fatalf("moved out: %+v", out)
	}
	if in := runs[1].Event; in.Content != nil || in.FromSpaceID != "work" {
		t.Fatalf("moved in: %+v", in)
	}
	// A move alone isn't writing.
	if f.sessions() != 0 {
		t.Fatal("a move started a session")
	}
}

func TestSessionFiresForTheSpaceTheNoteEndsIn(t *testing.T) {
	f := newFixture(t)
	f.agent("work", agent.EditDone)
	f.create("n1", "work", "a")
	f.update("n1", "b", nil)
	f.update("n1", "b", func(in *notes.UpdateInput) { in.SpaceID = ptr(spaces.Default) })
	f.advance(agent.IdleAfter)
	f.close()
	f.wantRuns()
}

func TestLockedAndConflictCopies(t *testing.T) {
	f := newFixture(t)
	f.agent("work", agent.NewNoteDone, agent.EditDone, agent.NoteDeleted, agent.MovedOut)
	sealed := func(fill byte) string {
		return "notesd:locked:v1:" + base64.RawURLEncoding.EncodeToString(bytes.Repeat([]byte{fill}, 40))
	}
	f.create("n1", "work", sealed(1))
	f.update("n1", sealed(2), nil)
	f.update("n1", sealed(2), func(in *notes.UpdateInput) { in.SpaceID = ptr("home") })
	f.delete("n1")
	if _, err := f.d.Notes.Create(ctx, notes.CreateInput{OperationID: f.op(), ID: "c1", Title: "T (Conflicted copy)", Content: "x",
		ConflictOf: ptr("n0"), SpaceID: ptr("work")}); err != nil {
		t.Fatal(err)
	}
	f.advance(agent.IdleAfter)
	f.close()
	f.wantRuns()
}

func TestTriggerErrorFailsTheWrite(t *testing.T) {
	f := newFixture(t)
	if _, err := f.db.W.Exec(`DROP TABLE agent_triggers; DROP TABLE agent_spaces`); err != nil {
		t.Fatal(err)
	}
	space := "work"
	if _, err := f.d.Notes.Create(ctx, notes.CreateInput{OperationID: f.op(), ID: "n1", Title: "T", Content: "a", SpaceID: &space}); err == nil {
		t.Fatal("the write committed without its triggers")
	}
	if _, err := f.d.Notes.Get(ctx, "n1"); !errors.Is(err, notes.ErrNotFound) {
		t.Fatalf("the note was written: %v", err)
	}
}

func TestTurnedOffBeforeTheSessionEnds(t *testing.T) {
	f := newFixture(t)
	f.agent("work", agent.NewNoteDone)
	f.create("n1", "work", "a")
	if f.sessions() != 1 {
		t.Fatal("no session to end")
	}
	if _, err := f.d.Agent.Put(ctx, agent.Config{SpaceID: "work", Enabled: false, Triggers: map[agent.Trigger]string{agent.NewNoteDone: "x"}}); err != nil {
		t.Fatal(err)
	}
	f.advance(agent.IdleAfter)
	f.close()
	f.wantRuns()
}

func TestSessionsSurviveARestart(t *testing.T) {
	f := newFixture(t)
	f.agent("work", agent.NewNoteDone)
	f.create("n1", "work", "a")
	// A new store over the same database, as after a restart.
	s := agent.NewStore(f.db)
	s.SetClock(func() time.Time { return f.clock.Add(time.Hour) })
	if n, err := s.CloseIdleSessions(ctx); err != nil || n != 1 {
		t.Fatalf("closed %d: %v", n, err)
	}
}

func TestQueueIsBounded(t *testing.T) {
	f := newFixture(t)
	f.agent("work", agent.NoteDeleted)
	for i := range agent.MaxQueued {
		if _, err := f.db.W.Exec(`INSERT INTO agent_runs (id, status, trigger, space_id, note_id, event_json, created_at)
			VALUES (?, 'queued', 'noteDeleted', 'work', 'x', '{}', 0)`, fmt.Sprint("r", i)); err != nil {
			t.Fatal(err)
		}
	}
	f.create("n1", "work", "a")
	f.delete("n1")
	var status, failure string
	if err := f.db.R.QueryRow(`SELECT status, failure FROM agent_runs WHERE note_id = 'n1'`).Scan(&status, &failure); err != nil {
		t.Fatal(err)
	}
	if status != agent.StatusSkipped || failure != "queue_full" {
		t.Fatalf("got %s %s", status, failure)
	}
}

func ptr(s string) *string { return &s }
