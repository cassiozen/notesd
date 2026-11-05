package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"image"
	imgpng "image/png"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"notes/server/internal/database"
	"notes/server/internal/images"
	"notes/server/internal/notes"
	"notes/server/internal/spaces"
)

func realPNG(t *testing.T, w, h int) ([]byte, string) {
	t.Helper()
	var b bytes.Buffer
	if err := imgpng.Encode(&b, image.NewRGBA(image.Rect(0, 0, w, h))); err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(b.Bytes())
	return b.Bytes(), fmt.Sprintf("notesd:image/%s?w=%d&h=%d", hex.EncodeToString(sum[:]), w, h)
}

func writeFiles(t *testing.T, files map[string][]byte) string {
	t.Helper()
	root := t.TempDir()
	for p, data := range files {
		full := filepath.Join(root, filepath.FromSlash(p))
		if err := os.MkdirAll(filepath.Dir(full), 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(full, data, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	return root
}

func allNotes(t *testing.T, db *database.DB) []notes.Note {
	t.Helper()
	all, err := liveNotes(ctx, db)
	if err != nil {
		t.Fatal(err)
	}
	return all
}

func TestImportNotes(t *testing.T) {
	db, _ := newDB(t)
	pic, picRef := realPNG(t, 4, 3)
	dir := writeFiles(t, map[string][]byte{
		"Recipes/Soup.md": []byte("---\ntitle: Tomato soup\ncreated: 2021-03-04T05:06:07Z\ntags: [cooking]\n---\nChop\nSimmer\n\n![](../img/soup.png)\n"),
		"img/soup.png":    pic,
		"todo.txt":        []byte("milk"),
	})
	o := options{from: dir, space: "Imported"}

	var out bytes.Buffer
	if err := importNotes(ctx, db, o, &out); err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"created space Imported", "imported 2 notes and 1 images from " + dir + " into Imported"} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("output %q lacks %q", out.String(), want)
		}
	}

	list, err := spaces.NewStore(db).List(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 2 || list[1].Name != "Imported" || list[1].NoteCount != 2 {
		t.Fatalf("spaces = %+v", list)
	}
	var soup notes.Note
	for _, n := range allNotes(t, db) {
		if n.SpaceID != list[1].ID {
			t.Errorf("%s in space %s", n.Title, n.SpaceID)
		}
		if n.Title == "Tomato soup" {
			soup = n
		}
	}
	if want := "Chop\\\nSimmer\n\n![](" + picRef + ")\n\n#cooking #Recipes"; soup.Content != want {
		t.Errorf("content = %q, want %q", soup.Content, want)
	}
	if want := time.Date(2021, 3, 4, 5, 6, 7, 0, time.UTC).UnixMilli(); soup.CreatedAt != want {
		t.Errorf("created = %d, want %d", soup.CreatedAt, want)
	}
	if ids := images.Refs(soup.Content); len(ids) != 1 {
		t.Fatalf("refs = %v", ids)
	} else if _, data, err := images.NewStore(db).Get(ctx, ids[0]); err != nil || !bytes.Equal(data, pic) {
		t.Errorf("stored image: %v", err)
	}
	var shown int
	if err := db.R.QueryRowContext(ctx, `SELECT count(*) FROM note_images`).Scan(&shown); err != nil || shown != 1 {
		t.Errorf("note_images = %d, %v", shown, err)
	}
	var tags int
	if err := db.R.QueryRowContext(ctx, `SELECT count(*) FROM note_tags`).Scan(&tags); err != nil || tags != 2 {
		t.Errorf("note_tags = %d, %v", tags, err)
	}

	// Again, same space by another case: nothing new.
	out.Reset()
	o.space = "imported"
	if err := importNotes(ctx, db, o, &out); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.String(), "imported 0 notes and 0 images") || !strings.Contains(out.String(), "skipped 2 notes already there") {
		t.Errorf("second run: %q", out.String())
	}
	if n := len(allNotes(t, db)); n != 2 {
		t.Errorf("%d notes after the second run", n)
	}
}

func TestImportDryRun(t *testing.T) {
	db, _ := newDB(t)
	dir := writeFiles(t, map[string][]byte{"a.md": []byte("a"), "b.md": []byte("b")})
	var out bytes.Buffer
	if err := importNotes(ctx, db, options{from: dir, space: "New", dryRun: true}, &out); err != nil {
		t.Fatal(err)
	}
	if want := "would create space New\nwould import 2 notes and 0 images from " + dir + " into New\n"; out.String() != want {
		t.Errorf("output = %q, want %q", out.String(), want)
	}
	if n := len(allNotes(t, db)); n != 0 {
		t.Errorf("dry run wrote %d notes", n)
	}
	if list, _ := spaces.NewStore(db).List(ctx); len(list) != 1 {
		t.Errorf("dry run made a space: %+v", list)
	}
}

func TestImportDefaultSpace(t *testing.T) {
	db, _ := newDB(t)
	dir := writeFiles(t, map[string][]byte{"a.md": []byte("a")})
	var out bytes.Buffer
	if err := importNotes(ctx, db, options{from: dir}, &out); err != nil {
		t.Fatal(err)
	}
	if all := allNotes(t, db); len(all) != 1 || all[0].SpaceID != spaces.Default {
		t.Errorf("notes = %+v", all)
	}
	if strings.Contains(out.String(), "space") {
		t.Errorf("output = %q", out.String())
	}
}

// What `notesd export` writes, `notesd import` reads back as the same notes.
func TestImportReadsExport(t *testing.T) {
	db, create := newDB(t)
	get, _ := storedImages(t, db)
	pic, picRef := realPNG(t, 4, 3)
	sum := sha256.Sum256(pic)
	if _, _, err := images.NewStore(db).Put(ctx, hex.EncodeToString(sum[:]), "image/png", pic); err != nil {
		t.Fatal(err)
	}
	create("a", "Trip", "Day one\\\nDay two\n\n![the beach]("+picRef+") #travel/2024\n\n- [ ] pack")
	create("b", "Code", "```go\nx := 1\ny := 2\n```")
	create("c", "Groceries", "milk *and* eggs")

	dir := filepath.Join(t.TempDir(), "out")
	if _, err := writeExport(dir, allNotes(t, db), nil, nil, get); err != nil {
		t.Fatal(err)
	}

	db2, _ := newDB(t)
	var out bytes.Buffer
	if err := importNotes(ctx, db2, options{from: dir}, &out); err != nil {
		t.Fatal(err)
	}
	want := map[string]string{}
	for _, n := range allNotes(t, db) {
		want[n.Title] = n.Content
	}
	got := map[string]string{}
	for _, n := range allNotes(t, db2) {
		got[n.Title] = n.Content
	}
	if fmt.Sprint(got) != fmt.Sprint(want) {
		t.Errorf("imported %q\nwant %q\n(output %s)", got, want, out.String())
	}
}

// A copy of the same files has other modification times: still skipped.
func TestImportAgainWithOtherTimes(t *testing.T) {
	db, _ := newDB(t)
	dir := writeFiles(t, map[string][]byte{"a.md": []byte("a")})
	var out bytes.Buffer
	if err := importNotes(ctx, db, options{from: dir}, &out); err != nil {
		t.Fatal(err)
	}
	later := time.Now().Add(-time.Hour)
	if err := os.Chtimes(filepath.Join(dir, "a.md"), later, later); err != nil {
		t.Fatal(err)
	}
	out.Reset()
	if err := importNotes(ctx, db, options{from: dir}, &out); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.String(), "skipped 1 notes already there") {
		t.Errorf("output = %q", out.String())
	}
}

func TestImportDryRunChecksSpaceName(t *testing.T) {
	db, _ := newDB(t)
	dir := writeFiles(t, map[string][]byte{"a.md": []byte("a")})
	err := importNotes(ctx, db, options{from: dir, space: strings.Repeat("x", 65), dryRun: true}, io.Discard)
	if err == nil || !strings.Contains(err.Error(), "at most") {
		t.Errorf("err = %v", err)
	}
}
