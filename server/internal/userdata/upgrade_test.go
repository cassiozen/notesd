package userdata

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"testing"

	"notes/server/internal/auth"
	"notes/server/internal/database"
	"notes/server/internal/mail"
	"notes/server/migrations"
)

// legacyDB writes a notes.db as a single-user notesd left it: schema version
// 9, with a note and, if withOwner, the owner, a device session and email
// settings. It returns that session's access token.
func legacyDB(t *testing.T, dir string, withOwner bool) string {
	t.Helper()
	ctx := context.Background()
	db, err := sql.Open("sqlite", "file:"+filepath.Join(dir, legacyName)+"?_pragma=journal_mode(WAL)")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	for v := 1; v <= 9; v++ {
		names, _ := fs.Glob(migrations.Notes, fmt.Sprintf("%04d_*.sql", v))
		body, err := fs.ReadFile(migrations.Notes, names[0])
		if err != nil {
			t.Fatal(err)
		}
		if _, err := db.ExecContext(ctx, string(body)); err != nil {
			t.Fatalf("%s: %v", names[0], err)
		}
	}
	if _, err := db.ExecContext(ctx, `PRAGMA user_version = 9;
		INSERT INTO notes (id, title, content, change_seq, created_at, updated_at) VALUES ('n1', 'Kept', 'body', 1, 1, 1);
		UPDATE sync_state SET current_seq = 1`); err != nil {
		t.Fatal(err)
	}
	if !withOwner {
		return ""
	}
	hash, err := auth.HashPassword("correct horse battery")
	if err != nil {
		t.Fatal(err)
	}
	token := auth.NewToken()
	for _, q := range []struct {
		sql  string
		args []any
	}{
		{`INSERT INTO users (id, username, password_hash, created_at, updated_at) VALUES (1, 'Owner', ?, 1, 1)`, []any{hash}},
		{`INSERT INTO sessions (id, kind, name, access_hash, access_expires_at, refresh_hash, refresh_expires_at, created_at, last_used_at)
		  VALUES ('s1', 'device', 'Pixel', ?, 99999999999999, 'r', 99999999999999, 1, 1)`, []any{auth.HashToken(token)}},
		{`INSERT INTO settings (key, value) VALUES ('resend_api_key', 're_123456789'), ('email_from', 'me@example.com')`, nil},
	} {
		if _, err := db.ExecContext(ctx, q.sql, q.args...); err != nil {
			t.Fatal(err)
		}
	}
	return token
}

func openSystem(t *testing.T, dir string) *database.DB {
	t.Helper()
	db, err := database.OpenSystem(context.Background(), filepath.Join(dir, "system.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

func exists(path string) bool {
	_, err := os.Stat(path)
	return !errors.Is(err, fs.ErrNotExist)
}

func TestUpgradeMovesTheOwnerAndTheirNotes(t *testing.T) {
	ctx := context.Background()
	dir := t.TempDir()
	token := legacyDB(t, dir, true)
	system := openSystem(t, dir)

	if err := Upgrade(ctx, dir, system); err != nil {
		t.Fatal(err)
	}
	if exists(filepath.Join(dir, legacyName)) || !exists(Path(dir, 1)) {
		t.Fatal("notes.db should have moved to users/1.db")
	}

	// Signed-in devices stay signed in, as the owner, an admin.
	a := auth.NewService(system)
	sess, err := a.Authenticate(ctx, token, auth.KindDevice)
	if err != nil || sess.UserID != 1 || sess.Username != "Owner" || !sess.IsAdmin {
		t.Fatalf("old session: %+v, %v", sess, err)
	}
	if _, err := a.Login(ctx, "owner", "correct horse battery", auth.KindWeb, "x"); err != nil {
		t.Fatalf("login with the old password: %v", err)
	}
	if st, err := mail.Load(ctx, system); err != nil || st.APIKey != "re_123456789" || st.From != "me@example.com" {
		t.Fatalf("settings: %+v, %v", st, err)
	}

	users := NewRegistry(dir)
	defer users.Close()
	d, err := users.Open(ctx, 1)
	if err != nil {
		t.Fatal(err)
	}
	if n, err := d.Notes.Get(ctx, "n1"); err != nil || n.Title != "Kept" {
		t.Fatalf("note: %+v, %v", n, err)
	}
	// The moved tables are gone from the user's file.
	var left int
	if err := d.DB.R.QueryRowContext(ctx,
		`SELECT count(*) FROM sqlite_schema WHERE name IN ('users', 'sessions', 'settings')`).Scan(&left); err != nil || left != 0 {
		t.Fatalf("tables left in users/1.db: %d, %v", left, err)
	}

	// Nothing of the moved tables is left in the file's bytes, nor in a WAL.
	for _, f := range []string{Path(dir, 1), Path(dir, 1) + "-wal"} {
		b, err := os.ReadFile(f)
		if err != nil && !errors.Is(err, fs.ErrNotExist) {
			t.Fatal(err)
		}
		if bytes.Contains(b, []byte("re_123456789")) || bytes.Contains(b, []byte("$argon2id$")) {
			t.Fatalf("%s still holds the Resend key or the password hash", f)
		}
	}

	// A second run has nothing to do.
	if err := Upgrade(ctx, dir, system); err != nil {
		t.Fatal(err)
	}
}

func TestUpgradeFinishesAnInterruptedMove(t *testing.T) {
	ctx := context.Background()
	dir := t.TempDir()
	legacyDB(t, dir, true)
	system := openSystem(t, dir)
	// As if a run copied the accounts, then stopped before the rename.
	if err := importAccounts(ctx, filepath.Join(dir, legacyName), system); err != nil {
		t.Fatal(err)
	}
	if err := Upgrade(ctx, dir, system); err != nil {
		t.Fatal(err)
	}
	if exists(filepath.Join(dir, legacyName)) || !exists(Path(dir, 1)) {
		t.Fatal("the rename should be finished")
	}
	var n int
	if err := system.R.QueryRowContext(ctx, `SELECT count(*) FROM users`).Scan(&n); err != nil || n != 1 {
		t.Fatalf("users: %d, %v (imported once)", n, err)
	}
}

func TestUpgradeConflictChangesNothing(t *testing.T) {
	ctx := context.Background()
	dir := t.TempDir()
	legacyDB(t, dir, true)
	if err := os.MkdirAll(filepath.Dir(Path(dir, 1)), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(Path(dir, 1), nil, 0o600); err != nil {
		t.Fatal(err)
	}
	system := openSystem(t, dir)
	if err := Upgrade(ctx, dir, system); err == nil {
		t.Fatal("both notes.db and users/1.db: want an error")
	}
	var n int
	if err := system.R.QueryRowContext(ctx, `SELECT count(*) FROM users`).Scan(&n); err != nil || n != 0 {
		t.Fatalf("users moved despite the conflict: %d, %v", n, err)
	}
}

func TestUpgradeWithoutAnOwnerKeepsTheNotesForTheFirstUser(t *testing.T) {
	ctx := context.Background()
	dir := t.TempDir()
	legacyDB(t, dir, false)
	system := openSystem(t, dir)
	if err := Upgrade(ctx, dir, system); err != nil {
		t.Fatal(err)
	}
	a := auth.NewService(system)
	if has, err := a.HasUsers(ctx); err != nil || has {
		t.Fatalf("has users: %v, %v", has, err)
	}
	if err := a.CreateOwner(ctx, "first", "correct horse battery"); err != nil {
		t.Fatal(err)
	}
	u, err := a.UserByName(ctx, "first")
	if err != nil || u.ID != 1 || !u.IsAdmin {
		t.Fatalf("first user: %+v, %v", u, err)
	}
	users := NewRegistry(dir)
	defer users.Close()
	d, err := users.Open(ctx, u.ID)
	if err != nil {
		t.Fatal(err)
	}
	if n, err := d.Notes.Get(ctx, "n1"); err != nil || n.Title != "Kept" {
		t.Fatalf("note: %+v, %v", n, err)
	}
}

func TestRemovedUsersIDsAreNotReused(t *testing.T) {
	ctx := context.Background()
	a := auth.NewService(openSystem(t, t.TempDir()))
	if err := a.CreateOwner(ctx, "owner", "correct horse battery"); err != nil {
		t.Fatal(err)
	}
	if _, err := a.RemoveUser(ctx, "owner"); !errors.Is(err, auth.ErrLastAdmin) {
		t.Fatalf("removing the last admin: %v", err)
	}
	kid, err := a.AddUser(ctx, "kid", "correct horse battery", false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := a.RemoveUser(ctx, "kid"); err != nil {
		t.Fatal(err)
	}
	again, err := a.AddUser(ctx, "kid", "correct horse battery", false)
	if err != nil || again.ID == kid.ID {
		t.Fatalf("re-added user got id %d (was %d), %v: they'd adopt the old notes file", again.ID, kid.ID, err)
	}
}
