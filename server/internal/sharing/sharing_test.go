package sharing_test

import (
	"context"
	"database/sql"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"testing"

	"notes/server/internal/auth"
	"notes/server/internal/database"
	"notes/server/internal/notes"
	"notes/server/internal/sharing"
	"notes/server/internal/userdata"
)

type env struct {
	t      *testing.T
	ctx    context.Context
	dir    string
	system *database.DB
	files  *userdata.Registry
	s      *sharing.Service
	owner  *userdata.Data
}

// newEnv has user 1 with a space "fam" holding notes a and b.
func newEnv(t *testing.T) *env {
	t.Helper()
	ctx := context.Background()
	dir := t.TempDir()
	system, err := database.OpenSystem(ctx, filepath.Join(dir, "system.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { system.Close() })
	if err := auth.NewService(system).CreateOwner(ctx, "owner", "correct horse battery"); err != nil {
		t.Fatal(err)
	}
	files := userdata.NewRegistry(dir)
	t.Cleanup(func() { files.Close() })
	e := &env{t: t, ctx: ctx, dir: dir, system: system, files: files, s: sharing.New(system, files)}
	e.s.Install()
	if e.owner, err = files.Open(ctx, 1); err != nil {
		t.Fatal(err)
	}
	if _, err := e.owner.Spaces.Put(ctx, "fam", "Family", nil); err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{"a", "b"} {
		if _, err := e.owner.Notes.Create(ctx, notes.CreateInput{OperationID: "op-" + id, ID: id, Title: id, SpaceID: ptr("fam")}); err != nil {
			t.Fatal(err)
		}
	}
	return e
}

func ptr(s string) *string { return &s }

func (e *env) exec(q string, args ...any) {
	e.t.Helper()
	if err := e.system.WriteTx(e.ctx, func(tx *sql.Tx) error { _, err := tx.ExecContext(e.ctx, q, args...); return err }); err != nil {
		e.t.Fatal(err)
	}
}

func (e *env) live(d *userdata.Data, id string) bool {
	e.t.Helper()
	ok, err := d.Notes.Live(e.ctx, id)
	if err != nil {
		e.t.Fatal(err)
	}
	return ok
}

// copyToSpace writes fam's notes into its own file, as a share does before
// the owner's file lets go of them.
func (e *env) copyToSpace() *userdata.Data {
	e.t.Helper()
	dest, err := e.files.OpenSpace(e.ctx, "fam")
	if err != nil {
		e.t.Fatal(err)
	}
	var carried []notes.Carried
	for _, id := range []string{"a", "b"} {
		n, err := e.owner.Notes.Get(e.ctx, id)
		if err != nil {
			e.t.Fatal(err)
		}
		carried = append(carried, notes.Carried{Note: n})
	}
	if err := dest.Notes.ShareIn(e.ctx, notes.SpaceRow{ID: "fam", Name: "Family"}, carried); err != nil {
		e.t.Fatal(err)
	}
	return dest
}

func TestRecoverFinishesAShareWhoseSpaceIsWritten(t *testing.T) {
	e := newEnv(t)
	e.exec(`INSERT INTO space_moves (id, kind, space_id, source, dest, created_at) VALUES ('m', 'space', 'fam', 'user:1', 'space:fam', 0)`)
	dest := e.copyToSpace()
	e.exec(`INSERT INTO shared_spaces (id, owner_id, created_at) VALUES ('fam', 1, 0)`)
	e.exec(`INSERT INTO space_members (space_id, user_id, joined_at) VALUES ('fam', 1, 0)`)

	if err := e.s.Recover(e.ctx); err != nil {
		t.Fatal(err)
	}
	if e.live(e.owner, "a") || e.live(e.owner, "b") || !e.live(dest, "a") || !e.live(dest, "b") {
		t.Fatal("each note in exactly one file: the space's")
	}
	if ok, err := e.owner.Notes.HasSpace(e.ctx, "fam"); err != nil || ok {
		t.Fatal("the owner's file let go of the space")
	}
	n, _ := e.owner.Notes.Get(e.ctx, "a")
	if n.MovedTo == nil || *n.MovedTo != "fam" {
		t.Fatalf("a moved tombstone: %+v", n)
	}
	if role, _ := e.s.Role(e.ctx, 1, "fam"); role != "owner" {
		t.Fatalf("shared, owned: %q", role)
	}
	e.noMoves()
}

func TestRecoverUndoesAShareCutShortBeforeItsSpaceWasRecorded(t *testing.T) {
	e := newEnv(t)
	e.exec(`INSERT INTO space_moves (id, kind, space_id, source, dest, created_at) VALUES ('m', 'space', 'fam', 'user:1', 'space:fam', 0)`)
	e.copyToSpace()

	if err := e.s.Recover(e.ctx); err != nil {
		t.Fatal(err)
	}
	if !e.live(e.owner, "a") || !e.live(e.owner, "b") {
		t.Fatal("the notes stay in the owner's file")
	}
	if _, err := os.Stat(userdata.SpacePath(e.dir, "fam")); !os.IsNotExist(err) {
		t.Fatalf("the half-made file is gone: %v", err)
	}
	e.noMoves()
	// And sharing again works.
	if _, err := auth.NewService(e.system).AddUser(e.ctx, "kid", "another horse battery", false); err != nil {
		t.Fatal(err)
	}
	if _, err := e.s.Invite(e.ctx, 1, "fam", 2); err != nil {
		t.Fatal(err)
	}
}

func TestRecoverFinishesANoteMoveWhoseDestinationHasIt(t *testing.T) {
	e := newEnv(t)
	if _, err := e.owner.Spaces.Put(e.ctx, "other", "Other", nil); err != nil {
		t.Fatal(err)
	}
	e.exec(`INSERT INTO shared_spaces (id, owner_id, created_at) VALUES ('sh', 1, 0)`)
	e.exec(`INSERT INTO space_members (space_id, user_id, joined_at) VALUES ('sh', 1, 0)`)
	dest, err := e.files.OpenSpace(e.ctx, "sh")
	if err != nil {
		t.Fatal(err)
	}
	if err := dest.Notes.ShareIn(e.ctx, notes.SpaceRow{ID: "sh", Name: "Shared"}, nil); err != nil {
		t.Fatal(err)
	}
	e.exec(`INSERT INTO space_moves (id, kind, space_id, note_id, source, dest, created_at) VALUES ('m1', 'note', 'sh', 'a', 'user:1', 'space:sh', 0)`)
	e.exec(`INSERT INTO space_moves (id, kind, space_id, note_id, source, dest, created_at) VALUES ('m2', 'note', 'sh', 'b', 'user:1', 'space:sh', 1)`)
	n, _ := e.owner.Notes.Get(e.ctx, "a")
	if _, err := dest.Notes.MoveIn(e.ctx, notes.Carried{Note: n}, "sh"); err != nil {
		t.Fatal(err)
	}

	if err := e.s.Recover(e.ctx); err != nil {
		t.Fatal(err)
	}
	if e.live(e.owner, "a") || !e.live(dest, "a") {
		t.Fatal("a's move is finished")
	}
	if !e.live(e.owner, "b") || e.live(dest, "b") {
		t.Fatal("b never reached the destination: it stays")
	}
	e.noMoves()
}

func (e *env) noMoves() {
	e.t.Helper()
	var n int
	if err := e.system.R.QueryRowContext(e.ctx, `SELECT count(*) FROM space_moves`).Scan(&n); err != nil || n != 0 {
		e.t.Fatalf("moves left: %d %v", n, err)
	}
}

func TestMovingOntoAnotherNoteWithItsIdIsRefused(t *testing.T) {
	e := newEnv(t)
	dest, err := e.files.OpenSpace(e.ctx, "sh")
	if err != nil {
		t.Fatal(err)
	}
	if err := dest.Notes.ShareIn(e.ctx, notes.SpaceRow{ID: "sh", Name: "Shared"}, []notes.Carried{{Note: notes.Note{ID: "a", Title: "Other", Revision: 1}}}); err != nil {
		t.Fatal(err)
	}
	a, _ := e.owner.Notes.Get(e.ctx, "a")
	var conflict *notes.ConflictError
	if _, err := dest.Notes.MoveIn(e.ctx, notes.Carried{Note: a}, "sh"); !errors.As(err, &conflict) || conflict.Code != notes.CodeNoteExists {
		t.Fatalf("a different note with the id: %v", err)
	}
	// The same note, moved before a crash, is taken as done.
	if _, err := dest.Notes.MoveIn(e.ctx, notes.Carried{Note: notes.Note{ID: "a", Title: "Other", Revision: 1}}, "sh"); err != nil {
		t.Fatalf("this move, again: %v", err)
	}
}

func TestRemovingAMemberTellsThemToo(t *testing.T) {
	e := newEnv(t)
	if _, err := auth.NewService(e.system).AddUser(e.ctx, "kid", "another horse battery", false); err != nil {
		t.Fatal(err)
	}
	in, err := e.s.Invite(e.ctx, 1, "fam", 2)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := e.s.Accept(e.ctx, 2, in.ID); err != nil {
		t.Fatal(err)
	}
	told := map[int64]bool{}
	e.s.OnChange(func(id int64) { told[id] = true })
	if err := e.s.RemoveMember(e.ctx, 1, "fam", 2); err != nil {
		t.Fatal(err)
	}
	if !told[2] || !told[1] {
		t.Fatalf("told: %v", told)
	}
}

func TestAskingForSpacesThatDontExistKeepsNothing(t *testing.T) {
	e := newEnv(t)
	for _, id := range []string{"nope", "nada", "fam"} {
		if _, _, err := e.s.Open(e.ctx, 1, id); !errors.Is(err, sharing.ErrNotFound) {
			t.Fatalf("open %s: %v", id, err)
		}
	}
	if n := e.s.Holds(); n != 0 {
		t.Fatalf("locks kept: %d", n)
	}
}

func TestOpeningASpaceFileIfItExistsDoesntMakeOne(t *testing.T) {
	e := newEnv(t)
	if d, err := e.files.OpenSpaceIfExists(e.ctx, "gone"); err != nil || d != nil {
		t.Fatalf("a space with no file: %v %v", d, err)
	}
	if _, err := os.Stat(userdata.SpacePath(e.dir, "gone")); !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("file made: %v", err)
	}
}

func TestSharingASpaceNamedLikeTheDefault(t *testing.T) {
	e := newEnv(t)
	// Every notes file starts with the default space as "Notes"; this account renamed theirs.
	if _, err := e.owner.Spaces.Put(e.ctx, "default", "Inbox", nil); err != nil {
		t.Fatal(err)
	}
	if _, err := e.owner.Spaces.Put(e.ctx, "fam", "Notes", nil); err != nil {
		t.Fatal(err)
	}
	if _, err := auth.NewService(e.system).AddUser(e.ctx, "kid", "another horse battery", false); err != nil {
		t.Fatal(err)
	}
	if _, err := e.s.Invite(e.ctx, 1, "fam", 2); err != nil {
		t.Fatalf("share: %v", err)
	}
	sp, err := e.s.Space(e.ctx, 1, "fam")
	if err != nil || sp.Name != "Notes" || sp.NoteCount != 2 {
		t.Fatalf("shared: %+v %v", sp, err)
	}
}

func TestRenamingASharedSpaceRenamesItInItsFile(t *testing.T) {
	e := newEnv(t)
	accounts := auth.NewService(e.system)
	for _, u := range []string{"kid", "gran"} {
		if _, err := accounts.AddUser(e.ctx, u, "another horse battery", false); err != nil {
			t.Fatal(err)
		}
	}
	in, err := e.s.Invite(e.ctx, 1, "fam", 2)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := e.s.Accept(e.ctx, 2, in.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := e.s.Invite(e.ctx, 1, "fam", 3); err != nil {
		t.Fatal(err)
	}
	icon := "garden"
	if _, err := e.s.Rename(e.ctx, 1, "fam", "  Household ", &icon); err != nil {
		t.Fatal(err)
	}
	if _, err := e.s.Rename(e.ctx, 2, "fam", "Mine", nil); !errors.Is(err, sharing.ErrNotOwner) {
		t.Fatalf("a member renames: %v", err)
	}

	d, err := e.files.OpenSpace(e.ctx, "fam")
	if err != nil {
		t.Fatal(err)
	}
	if sp, err := d.Spaces.Get(e.ctx, "fam"); err != nil || sp.Name != "Household" || sp.Icon != "garden" {
		t.Fatalf("in its file: %+v %v", sp, err)
	}
	if sp, err := e.s.Space(e.ctx, 2, "fam"); err != nil || sp.Name != "Household" || sp.Icon != "garden" {
		t.Fatalf("as a member sees it: %+v %v", sp, err)
	}
	invites, err := e.s.Invitations(e.ctx, 3)
	if err != nil || len(invites) != 1 || invites[0].SpaceName != "Household" || invites[0].SpaceIcon != "garden" {
		t.Fatalf("as an invitee sees it: %+v %v", invites, err)
	}
	// Keeping the icon.
	if sp, err := e.s.Rename(e.ctx, 1, "fam", "Home", nil); err != nil || sp.Name != "Home" || sp.Icon != "garden" {
		t.Fatalf("renamed again: %+v %v", sp, err)
	}
}
