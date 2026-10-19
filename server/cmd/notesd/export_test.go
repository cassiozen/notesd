package main

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"notes/server/internal/database"
	"notes/server/internal/images"
	"notes/server/internal/locks"
	"notes/server/internal/notes"
	"notes/server/internal/spaces"
)

var ctx = context.Background()

// newDB returns a fresh database and a function that adds a note, each a second after the last.
func newDB(t *testing.T) (*database.DB, func(id, title, content string)) {
	t.Helper()
	db, err := database.Open(ctx, filepath.Join(t.TempDir(), "notes.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	s := notes.NewStore(db)
	at := int64(1_700_000_000_000)
	return db, func(id, title, content string) {
		t.Helper()
		at += 1000
		ts := at
		if _, err := s.Create(ctx, notes.CreateInput{OperationID: "op-" + id, ID: id, Title: title, Content: content, CreatedAt: &ts, UpdatedAt: &ts}); err != nil {
			t.Fatal(err)
		}
	}
}

func TestExportNotes(t *testing.T) {
	db, create := newDB(t)
	s := notes.NewStore(db)
	create("a", "Groceries", "- milk #home\n")
	create("b", "groceries", "second\n")
	create("c", "  ", "no title\n")
	create("d", "a/b: c?", "odd name\n")
	create("e", "Bank", locks.Prefix+"c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0")
	create("f", "Gone", "deleted\n")
	if _, err := s.Delete(ctx, notes.DeleteInput{OperationID: "del-f", ID: "f", BaseRevision: 1}); err != nil {
		t.Fatal(err)
	}

	dir := filepath.Join(t.TempDir(), "out")
	all, err := liveNotes(ctx, db)
	if err != nil {
		t.Fatal(err)
	}
	res, err := writeExport(dir, all, nil, nil, noImages)
	if err != nil {
		t.Fatal(err)
	}
	if res != (exported{Written: 4, Locked: 1}) {
		t.Fatalf("result = %+v", res)
	}

	entries, _ := os.ReadDir(dir)
	var names []string
	for _, e := range entries {
		names = append(names, e.Name())
	}
	slices.Sort(names)
	if want := []string{"Groceries.md", "Untitled.md", "a b c.md", "groceries (2).md"}; !slices.Equal(names, want) {
		t.Fatalf("files = %q, want %q", names, want)
	}
	for name, want := range map[string]string{
		"Groceries.md":     "# Groceries\n\n- milk #home\n",
		"groceries (2).md": "# groceries\n\nsecond\n",
		"Untitled.md":      "no title\n",
	} {
		got, _ := os.ReadFile(filepath.Join(dir, name))
		if string(got) != want {
			t.Errorf("%s = %q, want %q", name, got, want)
		}
	}
	info, _ := os.Stat(filepath.Join(dir, "Groceries.md"))
	if !info.ModTime().Equal(time.UnixMilli(1_700_000_001_000)) {
		t.Errorf("mtime = %v", info.ModTime())
	}

	if _, err := writeExport(dir, all, nil, nil, noImages); err == nil || !strings.Contains(err.Error(), "not empty") {
		t.Fatalf("second export into the same dir: %v", err)
	}
}

func noImages(string) (string, []byte, error) { return "", nil, images.ErrNotFound }

// storedImages keeps images as the server does and returns a reference to each.
func storedImages(t *testing.T, db *database.DB) (getImage, func(contentType string, data []byte) (id, ref string)) {
	t.Helper()
	s := images.NewStore(db)
	get := func(id string) (string, []byte, error) {
		img, data, err := s.Get(ctx, id)
		return img.ContentType, data, err
	}
	return get, func(contentType string, data []byte) (string, string) {
		t.Helper()
		sum := sha256.Sum256(data)
		id := hex.EncodeToString(sum[:])
		if _, _, err := s.Put(ctx, id, contentType, data); err != nil {
			t.Fatal(err)
		}
		return id, "notesd:image/" + id + "?w=4&h=3"
	}
}

// A 1×1 PNG and a JPEG's leading bytes: enough for the type check.
var (
	png  = []byte("\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR")
	jpeg = []byte{0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'}
)

func TestExportImages(t *testing.T) {
	db, create := newDB(t)
	get, put := storedImages(t, db)
	pngID, pngRef := put("image/png", png)
	jpgID, jpgRef := put("image/jpeg", jpeg)
	missing := "notesd:image/" + strings.Repeat("0", 64) + "?w=4&h=3"
	create("a", "Trip", "Day one\n\n![the beach]("+pngRef+")\n\nand ![]("+jpgRef+") inline\n")
	create("b", "Again", "![same]("+pngRef+")\n\n![gone]("+missing+")\n")
	code := "`![code](" + jpgRef + ")`\n"
	create("c", "Code", code)

	dir := filepath.Join(t.TempDir(), "out")
	all, err := liveNotes(ctx, db)
	if err != nil {
		t.Fatal(err)
	}
	res, err := writeExport(dir, all, nil, nil, get)
	if err != nil {
		t.Fatal(err)
	}
	if res != (exported{Written: 3, Images: 2, MissingImages: 1}) {
		t.Fatalf("result = %+v", res)
	}
	for name, want := range map[string]string{
		"Trip.md":  "# Trip\n\nDay one\n\n![the beach](images/" + pngID + ".png)\n\nand ![](images/" + jpgID + ".jpg) inline\n",
		"Again.md": "# Again\n\n![same](images/" + pngID + ".png)\n\n![gone](" + missing + ")\n",
		"Code.md":  "# Code\n\n" + code,
	} {
		got, _ := os.ReadFile(filepath.Join(dir, name))
		if string(got) != want {
			t.Errorf("%s = %q, want %q", name, got, want)
		}
	}
	entries, _ := os.ReadDir(filepath.Join(dir, "images"))
	if len(entries) != 2 {
		t.Fatalf("images/ holds %d files, want 2", len(entries))
	}
	if got, _ := os.ReadFile(filepath.Join(dir, "images", pngID+".png")); !bytes.Equal(got, png) {
		t.Errorf("png = %q", got)
	}
}

// seal encrypts as the clients do (§10.1), for building locked notes in tests.
func seal(t *testing.T, key, plain []byte, aad string) []byte {
	t.Helper()
	block, err := aes.NewCipher(key)
	if err != nil {
		t.Fatal(err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		t.Fatal(err)
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		t.Fatal(err)
	}
	return gcm.Seal(nonce, nonce, plain, []byte(aad))
}

func TestExportLockedImages(t *testing.T) {
	f := loadCryptoFixtures(t)
	key, err := base64.RawURLEncoding.DecodeString(f.Images[0].Key)
	if err != nil {
		t.Fatal(err)
	}
	plain, _ := base64.RawURLEncoding.DecodeString(f.Images[0].Plain)
	sealed, _ := base64.RawURLEncoding.DecodeString(f.Images[0].Sealed)

	db, create := newDB(t)
	get, put := storedImages(t, db)
	lockedID, lockedRef := put(images.Locked, sealed)
	_, otherRef := put(images.Locked, seal(t, make([]byte, 32), plain, "notesd:image:v1"))
	body := "![x](" + lockedRef + ")\n\n![y](" + otherRef + ")\n"
	create("a", "Secret", locks.Prefix+base64.RawURLEncoding.EncodeToString(seal(t, key, []byte(body), "notesd:locked:v1")))
	all, err := liveNotes(ctx, db)
	if err != nil {
		t.Fatal(err)
	}

	// Without the key the note is skipped, and so are its images.
	dir := filepath.Join(t.TempDir(), "out")
	if res, err := writeExport(dir, all, nil, nil, get); err != nil || res != (exported{Locked: 1}) {
		t.Fatalf("no key: %+v, %v", res, err)
	}

	dir = filepath.Join(t.TempDir(), "out")
	res, err := writeExport(dir, all, nil, key, get)
	if err != nil {
		t.Fatal(err)
	}
	// The second image is sealed under another key: it keeps its reference.
	if res != (exported{Written: 1, Images: 1, MissingImages: 1}) {
		t.Fatalf("result = %+v", res)
	}
	got, _ := os.ReadFile(filepath.Join(dir, "Secret.md"))
	if want := "# Secret\n\n![x](images/" + lockedID + ".png)\n\n![y](" + otherRef + ")\n"; string(got) != want {
		t.Fatalf("Secret.md = %q, want %q", got, want)
	}
	if img, _ := os.ReadFile(filepath.Join(dir, "images", lockedID+".png")); !bytes.Equal(img, plain) {
		t.Fatal("the locked image wasn't written decrypted")
	}
}

func TestUniqueNameIgnoresCaseAndComposition(t *testing.T) {
	taken := map[string]bool{}
	first, second := uniqueName("Café", taken), uniqueName("CAFE\u0301", taken)
	if first != "Café.md" || second != "CAFE\u0301 (2).md" {
		t.Fatalf("got %q then %q; the second is the same file on macOS", first, second)
	}
}

func TestFileName(t *testing.T) {
	for title, want := range map[string]string{
		"Plain":                       "Plain",
		"":                            "Untitled",
		"...":                         "Untitled",
		".hidden":                     "hidden",
		"trailing. ":                  "trailing",
		"tab\tand\nnewline":           "tab and newline",
		`<all>|"bad"*chars?`:          "all bad chars",
		strings.Repeat("é", 60):       strings.Repeat("é", 50),
		strings.Repeat("x", 99) + "é": strings.Repeat("x", 99),
		"CON":                         "_CON",
		"lpt1.notes":                  "_lpt1.notes",
		"Console":                     "Console",
		"Cafe\u0301":                  "Café",
	} {
		if got := fileName(title); got != want {
			t.Errorf("fileName(%q) = %q, want %q", title, got, want)
		}
	}
}

// The shared crypto fixtures: a keyring whose password entry opens with
// fixturePassword, and envelopes sealed with the key it holds.
const fixturePassword = "correct horse battery staple"

type cryptoFixtures struct {
	KDF []struct {
		Password, Salt string
		Iterations     int
	}
	Wraps     []struct{ Kind, Wrapped string }
	Envelopes []struct{ Name, Plaintext, Envelope string }
	Invalid   []struct{ Name, Envelope string }
	Images    []struct{ Name, Key, Plain, Sealed string }
}

func loadCryptoFixtures(t *testing.T) cryptoFixtures {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "shared", "crypto-fixtures", "locked.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f cryptoFixtures
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	if f.KDF[0].Password != fixturePassword || f.Wraps[0].Kind != "password" {
		t.Fatal("fixtures changed shape")
	}
	return f
}

func TestExportLockedNotes(t *testing.T) {
	f := loadCryptoFixtures(t)
	db, create := newDB(t)
	envelope := func(name string) string {
		for _, e := range f.Envelopes {
			if e.Name == name {
				return e.Envelope
			}
		}
		for _, e := range f.Invalid {
			if e.Name == name {
				return e.Envelope
			}
		}
		t.Fatalf("no fixture %q", name)
		return ""
	}
	create("a", "Bank", envelope("markdown"))
	create("b", "Broken", envelope("tampered tag"))
	create("c", "Open", "plain\n")
	all, err := liveNotes(ctx, db)
	if err != nil {
		t.Fatal(err)
	}
	stdin, _, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer stdin.Close()

	// The password is typed, so a pipe is refused.
	if _, err := askLockPassword("out", 2, stdin, io.Discard); err == nil || !strings.Contains(err.Error(), "terminal") {
		t.Fatalf("not a terminal: %v", err)
	}

	if _, err := lockKey(ctx, db, fixturePassword); err == nil || !strings.Contains(err.Error(), "no lock password") {
		t.Fatalf("no keyring: %v", err)
	}
	k := f.KDF[0]
	if _, err := db.W.ExecContext(ctx, `INSERT INTO lock_keys (id, kind, salt, iterations, wrapped, created_at)
		VALUES ('password', 'password', ?, ?, ?, 0)`, k.Salt, k.Iterations, f.Wraps[0].Wrapped); err != nil {
		t.Fatal(err)
	}
	if _, err := lockKey(ctx, db, "wrong"); !errors.Is(err, locks.ErrWrongPassword) {
		t.Fatalf("wrong password: %v", err)
	}
	key, err := lockKey(ctx, db, fixturePassword)
	if err != nil {
		t.Fatal(err)
	}

	dir := filepath.Join(t.TempDir(), "out")
	res, err := writeExport(dir, all, nil, key, noImages)
	if err != nil {
		t.Fatal(err)
	}
	if res != (exported{Written: 2, Unreadable: 1}) {
		t.Fatalf("result = %+v", res)
	}
	got, _ := os.ReadFile(filepath.Join(dir, "Bank.md"))
	if want := "# Bank\n\n" + f.Envelopes[1].Plaintext; f.Envelopes[1].Name != "markdown" || string(got) != want {
		t.Fatalf("Bank.md = %q, want %q", got, want)
	}
}

func TestExportBySpace(t *testing.T) {
	db, create := newDB(t)
	if folders, err := spaceFolders(ctx, db); err != nil || folders != nil {
		t.Fatalf("one space stays flat: %v %v", folders, err)
	}
	sp := spaces.NewStore(db)
	if _, err := sp.Put(ctx, "work", "Work: Q3", nil); err != nil {
		t.Fatal(err)
	}
	if _, err := sp.Put(ctx, "img", "Images", nil); err != nil {
		t.Fatal(err)
	}
	get, put := storedImages(t, db)
	_, ref := put("image/png", png)
	create("a", "Home", "![]("+ref+")\n")
	s := notes.NewStore(db)
	work := "work"
	if _, err := s.Create(ctx, notes.CreateInput{OperationID: "op-b", ID: "b", Title: "Plan", Content: "![](" + ref + ")\n", SpaceID: &work}); err != nil {
		t.Fatal(err)
	}

	folders, err := spaceFolders(ctx, db)
	if err != nil {
		t.Fatal(err)
	}
	// "Images" would share the images folder, ignoring case.
	if folders["default"] != "Notes" || folders["work"] != "Work Q3" || folders["img"] != "Images (2)" {
		t.Fatalf("folders = %v", folders)
	}
	all, err := liveNotes(ctx, db)
	if err != nil {
		t.Fatal(err)
	}
	dir := filepath.Join(t.TempDir(), "out")
	if _, err := writeExport(dir, all, folders, nil, get); err != nil {
		t.Fatal(err)
	}
	for path, want := range map[string]string{
		"Notes/Home.md":   "# Home\n\n![](../images/",
		"Work Q3/Plan.md": "# Plan\n\n![](../images/",
	} {
		got, err := os.ReadFile(filepath.Join(dir, filepath.FromSlash(path)))
		if err != nil || !strings.HasPrefix(string(got), want) {
			t.Errorf("%s = %q (%v)", path, got, err)
		}
	}
	if entries, _ := os.ReadDir(filepath.Join(dir, "images")); len(entries) != 1 {
		t.Errorf("images: %d files", len(entries))
	}
}
