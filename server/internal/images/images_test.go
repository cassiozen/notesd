package images_test

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"notes/server/internal/database"
	"notes/server/internal/images"
)

func TestCheckFindsDamagedImages(t *testing.T) {
	ctx := context.Background()
	db, err := database.Open(ctx, filepath.Join(t.TempDir(), "notes.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	s := images.NewStore(db)

	gif := []byte("GIF89a\x01\x00\x01\x00")
	sum := sha256.Sum256(gif)
	id := hex.EncodeToString(sum[:])
	if _, created, err := s.Put(ctx, id, "image/gif", gif); err != nil || !created {
		t.Fatalf("put: %v %v", created, err)
	}
	if bad, err := s.Check(ctx); err != nil || len(bad) != 0 {
		t.Fatalf("intact: %v %v", bad, err)
	}
	if _, err := db.W.ExecContext(ctx, `UPDATE images SET data = ? WHERE id = ?`, []byte("GIF89a\x02"), id); err != nil {
		t.Fatal(err)
	}
	if bad, err := s.Check(ctx); err != nil || len(bad) != 1 || bad[0] != id {
		t.Fatalf("damaged: %v %v", bad, err)
	}
}

func TestImageTypeFixtures(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "shared", "crypto-fixtures", "locked.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f struct {
		ImageTypes []struct {
			Type              string
			Locked, Supported bool
		}
	}
	if err := json.Unmarshal(raw, &f); err != nil || len(f.ImageTypes) == 0 {
		t.Fatalf("fixtures: %v", err)
	}
	for _, c := range f.ImageTypes {
		// Every one is stored; only the supported ones are read (Types).
		if images.IsLockedType(c.Type) != c.Locked || images.Types[c.Type] != c.Supported || !images.ValidType(c.Type) {
			t.Errorf("%s: locked %v, supported %v, valid %v", c.Type, images.IsLockedType(c.Type), images.Types[c.Type], images.ValidType(c.Type))
		}
	}
	for _, bad := range []string{"application/vnd.notesd.locked.v1", "application/vnd.notesd.locked.v01", "application/vnd.notesd.lockedx", "application/vnd.notesd.locked."} {
		if images.ValidType(bad) {
			t.Errorf("%s accepted", bad)
		}
	}
}

// Locked images of any version wait for their note's save: the server can't
// tell which locked note shows them.
func TestReleaseUntrackedKeepsLocked(t *testing.T) {
	ctx := context.Background()
	db, err := database.Open(ctx, filepath.Join(t.TempDir(), "notes.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	for id, ct := range map[string]string{"plain": "image/png", "v1": images.Locked, "v2": images.Locked + ".v2"} {
		if _, err := db.W.ExecContext(ctx, `INSERT INTO images (id, content_type, size, data, created_at) VALUES (?, ?, 1, x'00', 1)`, id, ct); err != nil {
			t.Fatal(err)
		}
	}
	if err := db.WriteTx(ctx, func(tx *sql.Tx) error { return images.ReleaseUntracked(ctx, tx, 5) }); err != nil {
		t.Fatal(err)
	}
	rows, err := db.R.QueryContext(ctx, `SELECT id FROM images WHERE released_at IS NOT NULL`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var released []string
	for rows.Next() {
		var id string
		_ = rows.Scan(&id)
		released = append(released, id)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	if len(released) != 1 || released[0] != "plain" {
		t.Fatalf("released %v", released)
	}
}
