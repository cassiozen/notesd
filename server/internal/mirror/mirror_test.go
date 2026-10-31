package mirror

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
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
	"notes/server/internal/userdata"
)

var ctx = context.Background()

var quietLog = slog.New(slog.NewTextHandler(io.Discard, nil))

type fixture struct {
	t      *testing.T
	db     *database.DB
	notes  *notes.Store
	spaces *spaces.Store
	images *images.Store
	dir    string // the mirror's root
	m      *Mirror
	rev    map[string]int64
	op     int
}

func newFixture(t *testing.T) *fixture {
	t.Helper()
	db, err := database.Open(ctx, filepath.Join(t.TempDir(), "notes.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	f := &fixture{t: t, db: db, notes: notes.NewStore(db), spaces: spaces.NewStore(db), images: images.NewStore(db),
		dir: t.TempDir(), rev: map[string]int64{}}
	f.m = f.mirror()
	return f
}

// mirror is a new Mirror over the fixture's folder, as after a restart.
func (f *fixture) mirror() *Mirror {
	return New(Config{
		Dir:      f.dir,
		Accounts: func(context.Context) ([]Account, error) { return []Account{{ID: 1, Username: "sam"}}, nil },
		Open:     func(context.Context, int64) (*database.DB, error) { return f.db, nil },
		Log:      quietLog,
	})
}

func (f *fixture) opID() string { f.op++; return fmt.Sprintf("op-%d", f.op) }

func (f *fixture) create(id, title, content string, space ...string) {
	f.t.Helper()
	in := notes.CreateInput{OperationID: f.opID(), ID: id, Title: title, Content: content}
	if len(space) > 0 {
		in.SpaceID = &space[0]
	}
	res, err := f.notes.Create(ctx, in)
	if err != nil {
		f.t.Fatal(err)
	}
	f.rev[id] = res.Revision
}

func (f *fixture) update(id, title, content string, space ...string) {
	f.t.Helper()
	in := notes.UpdateInput{OperationID: f.opID(), ID: id, BaseRevision: f.rev[id], Title: title, Content: content}
	if len(space) > 0 {
		in.SpaceID = &space[0]
	}
	res, err := f.notes.Update(ctx, in)
	if err != nil {
		f.t.Fatal(err)
	}
	f.rev[id] = res.Revision
}

func (f *fixture) delete(id string) {
	f.t.Helper()
	if _, err := f.notes.Delete(ctx, notes.DeleteInput{OperationID: f.opID(), ID: id, BaseRevision: f.rev[id]}); err != nil {
		f.t.Fatal(err)
	}
}

func (f *fixture) flush() {
	f.t.Helper()
	if err := f.m.flush(ctx, 1); err != nil {
		f.t.Fatal(err)
	}
}

// files lists the user's folder, slash-separated, hidden files left out.
func (f *fixture) files() []string {
	f.t.Helper()
	root := filepath.Join(f.dir, "sam")
	var out []string
	err := filepath.WalkDir(root, func(p string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if strings.HasPrefix(d.Name(), ".") {
			return nil
		}
		rel, _ := filepath.Rel(root, p)
		if rel != "." {
			if d.IsDir() {
				rel += "/"
			}
			out = append(out, filepath.ToSlash(rel))
		}
		return nil
	})
	if err != nil {
		f.t.Fatal(err)
	}
	slices.Sort(out)
	return out
}

func (f *fixture) read(p string) string {
	f.t.Helper()
	data, err := os.ReadFile(filepath.Join(f.dir, "sam", filepath.FromSlash(p)))
	if err != nil {
		f.t.Fatal(err)
	}
	return string(data)
}

func (f *fixture) wantFiles(want ...string) {
	f.t.Helper()
	slices.Sort(want)
	if got := f.files(); !slices.Equal(got, want) {
		f.t.Fatalf("files = %q, want %q", got, want)
	}
}

func TestMirrorFollowsNotes(t *testing.T) {
	f := newFixture(t)
	f.create("a", "Groceries", "- milk\n")
	f.create("b", "Plans", "soon\n")
	f.create("c", "groceries", "other\n")
	f.flush()
	f.wantFiles("Notes/", "Notes/Groceries.md", "Notes/Plans.md", "Notes/groceries (2).md")
	if got := f.read("Notes/Groceries.md"); got != "# Groceries\n\n- milk\n" {
		t.Fatalf("Groceries.md = %q", got)
	}

	f.update("a", "Groceries", "- eggs\n")
	f.update("b", "Trip", "soon\n")
	f.flush()
	f.wantFiles("Notes/", "Notes/Groceries.md", "Notes/Trip.md", "Notes/groceries (2).md")
	if got := f.read("Notes/Groceries.md"); got != "# Groceries\n\n- eggs\n" {
		t.Fatalf("Groceries.md = %q", got)
	}

	// Only the case changes: the same file where names ignore case.
	f.update("b", "trip", "soon\n")
	f.flush()
	f.wantFiles("Notes/", "Notes/Groceries.md", "Notes/groceries (2).md", "Notes/trip.md")

	f.delete("c")
	f.update("a", "Groceries", locks.Prefix+"c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0")
	f.flush()
	f.wantFiles("Notes/", "Notes/trip.md")
}

func TestMirrorKeepsNumberedNames(t *testing.T) {
	f := newFixture(t)
	f.create("a", "Todo", "one\n")
	f.create("b", "Todo", "two\n")
	f.flush()
	f.wantFiles("Notes/", "Notes/Todo.md", "Notes/Todo (2).md")
	// Freed, "Todo" isn't taken back by the other note, which keeps its file.
	f.delete("a")
	f.update("b", "Todo", "two, edited\n")
	f.flush()
	f.wantFiles("Notes/", "Notes/Todo (2).md")
}

func TestMirrorSpaces(t *testing.T) {
	f := newFixture(t)
	if _, err := f.spaces.Put(ctx, "work", "Work", nil); err != nil {
		t.Fatal(err)
	}
	f.create("a", "Plan", "q3\n", "work")
	f.create("b", "Home", "h\n")
	f.flush()
	f.wantFiles("Notes/", "Notes/Home.md", "Work/", "Work/Plan.md")

	if _, err := f.spaces.Put(ctx, "work", "Job: 2026", nil); err != nil {
		t.Fatal(err)
	}
	f.flush()
	f.wantFiles("Job 2026/", "Job 2026/Plan.md", "Notes/", "Notes/Home.md")

	f.update("a", "Plan", "q3\n", spaces.Default)
	if err := f.spaces.Delete(ctx, "work"); err != nil {
		t.Fatal(err)
	}
	f.flush()
	f.wantFiles("Notes/", "Notes/Home.md", "Notes/Plan.md")
}

func TestMirrorLeavesOtherFilesAlone(t *testing.T) {
	f := newFixture(t)
	f.create("a", "Home", "h\n")
	f.flush()
	foreign := filepath.Join(f.dir, "sam", "Notes", "groceries.md")
	if err := os.WriteFile(foreign, []byte("mine"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(f.dir, "sam", "Work"), 0o700); err != nil {
		t.Fatal(err)
	}
	if _, err := f.spaces.Put(ctx, "work", "Work", nil); err != nil {
		t.Fatal(err)
	}
	f.create("b", "Groceries", "g\n")
	f.create("c", "Plan", "p\n", "work")
	f.flush()
	f.wantFiles("Notes/", "Notes/Groceries (2).md", "Notes/Home.md", "Notes/groceries.md", "Work (2)/", "Work (2)/Plan.md", "Work/")
	f.delete("b")
	f.flush()
	if got := f.read("Notes/groceries.md"); got != "mine" {
		t.Fatalf("someone else's file = %q", got)
	}
}

func TestMirrorResumesFromItsCursor(t *testing.T) {
	f := newFixture(t)
	f.create("a", "One", "1\n")
	f.create("b", "Two", "2\n")
	f.flush()
	// Untouched since: a restarted mirror doesn't write it again.
	one := filepath.Join(f.dir, "sam", "Notes", "One.md")
	if err := os.WriteFile(one, []byte("edited by hand"), 0o600); err != nil {
		t.Fatal(err)
	}
	f.update("b", "Two", "two\n")
	f.m = f.mirror()
	f.flush()
	if got := f.read("Notes/One.md"); got != "edited by hand" {
		t.Fatalf("One.md = %q", got)
	}
	if got := f.read("Notes/Two.md"); got != "# Two\n\ntwo\n" {
		t.Fatalf("Two.md = %q", got)
	}
}

func TestMirrorRebuildsAfterRestore(t *testing.T) {
	f := newFixture(t)
	f.create("a", "One", "1\n")
	f.flush()
	// As if the database were restored from a backup taken before "Gone" was written.
	path := filepath.Join(f.dir, "sam", manifestName)
	var man manifest
	data, _ := os.ReadFile(path)
	if err := json.Unmarshal(data, &man); err != nil {
		t.Fatal(err)
	}
	man.Generation = "older"
	man.Notes["gone"] = &entry{Path: "Notes/Gone.md"}
	data, _ = json.Marshal(man)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(f.dir, "sam", "Notes", "Gone.md"), []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	f.m = f.mirror()
	f.flush()
	f.wantFiles("Notes/", "Notes/One.md")
}

func TestMirrorImages(t *testing.T) {
	f := newFixture(t)
	png := []byte("\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR")
	sum := sha256.Sum256(png)
	id := hex.EncodeToString(sum[:])
	ref := "notesd:image/" + id + "?w=4&h=3"

	// Saved before its image is uploaded: written again once it is.
	f.create("a", "Trip", "![beach]("+ref+")\n")
	f.flush()
	if got := f.read("Notes/Trip.md"); got != "# Trip\n\n![beach]("+ref+")\n" {
		t.Fatalf("Trip.md = %q", got)
	}
	if _, _, err := f.images.Put(ctx, id, "image/png", png); err != nil {
		t.Fatal(err)
	}
	f.flush()
	if got := f.read("Notes/Trip.md"); got != "# Trip\n\n![beach](../images/"+id+".png)\n" {
		t.Fatalf("Trip.md = %q", got)
	}
	f.wantFiles("Notes/", "Notes/Trip.md", "images/", "images/"+id+".png")

	// Deleted by hand: back with the note's next change.
	if err := os.Remove(filepath.Join(f.dir, "sam", "images", id+".png")); err != nil {
		t.Fatal(err)
	}
	f.update("a", "Trip", "![beach]("+ref+")\nsand\n")
	f.flush()
	f.wantFiles("Notes/", "Notes/Trip.md", "images/", "images/"+id+".png")

	f.update("a", "Trip", "no pictures\n")
	f.flush()
	f.wantFiles("Notes/", "Notes/Trip.md", "images/")
}

func TestCheck(t *testing.T) {
	f := newFixture(t)
	if err := os.MkdirAll(filepath.Join(f.dir, "sam"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(f.dir, "sam", ".DS_Store"), nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := f.mirror().Check(ctx, t.TempDir()); err != nil {
		t.Fatalf("only hidden files: %v", err)
	}
	if err := os.WriteFile(filepath.Join(f.dir, "sam", "notes.md"), nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := f.mirror().Check(ctx, t.TempDir()); err == nil || !strings.Contains(err.Error(), "isn't a notesd mirror") {
		t.Fatalf("a folder with files: %v", err)
	}
	if err := f.mirror().Check(ctx, filepath.Join(f.dir, "data")); err == nil || !strings.Contains(err.Error(), "data directory") {
		t.Fatalf("data dir inside: %v", err)
	}
	data := t.TempDir()
	link := filepath.Join(t.TempDir(), "link")
	if err := os.Symlink(data, link); err != nil {
		t.Fatal(err)
	}
	through := New(Config{Dir: link, Accounts: f.mirror().cfg.Accounts})
	if err := through.Check(ctx, data); err == nil || !strings.Contains(err.Error(), "data directory") {
		t.Fatalf("data dir inside, through a symlink: %v", err)
	}
}

func TestCheckRefusesClashesAndLinks(t *testing.T) {
	f := newFixture(t)
	if err := os.Mkdir(filepath.Join(f.dir, "SAM"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := f.mirror().Check(ctx, t.TempDir()); err == nil || !strings.Contains(err.Error(), "clashes") {
		t.Fatalf("SAM beside sam: %v", err)
	}
	if err := os.Remove(filepath.Join(f.dir, "SAM")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(t.TempDir(), filepath.Join(f.dir, "sam")); err != nil {
		t.Fatal(err)
	}
	if err := f.mirror().Check(ctx, t.TempDir()); err == nil || !strings.Contains(err.Error(), "symbolic link") {
		t.Fatalf("sam a link: %v", err)
	}
}

func TestMirrorWontWriteThroughLinks(t *testing.T) {
	f := newFixture(t)
	f.create("a", "Plan", "q3\n")
	f.flush()
	outside := t.TempDir()
	notesDir := filepath.Join(f.dir, "sam", "Notes")
	if err := os.RemoveAll(notesDir); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, notesDir); err != nil {
		t.Fatal(err)
	}
	f.update("a", "Plan", "q4\n")
	if err := f.m.flush(ctx, 1); err == nil || !strings.Contains(err.Error(), "symbolic link") {
		t.Fatalf("flush through a link: %v", err)
	}
	if entries, _ := os.ReadDir(outside); len(entries) > 0 {
		t.Fatalf("wrote outside: %v", entries)
	}
}

func TestFolderNames(t *testing.T) {
	// "A B (2)" is a username, so the clash with "A B" goes on to (3).
	got := folderNames([]Account{{ID: 4, Username: "a/b"}, {ID: 1, Username: "A B"}, {ID: 2, Username: "CON"}, {ID: 3, Username: "A B (2)"}})
	want := map[int64]string{1: "A B", 2: "_CON", 3: "A B (2)", 4: "a b (3)"}
	for id, name := range want {
		if got[id] != name {
			t.Errorf("folderNames = %q, want %q", got, want)
		}
	}
}

func TestMirrorRefusesADamagedManifest(t *testing.T) {
	for _, man := range []string{
		`{"notes":{"a":{"path":"../../outside.md"}}}`,
		`{"notes":{"a":{"path":"/etc/passwd"}}}`,
		`{"notes":{"a":{"path":"Notes/sub/x.md"}}}`,
		`{"notes":{"a":null}}`,
		`{"notes":{"a":{"path":"N/x.md"},"b":{"path":"n/X.md"}}}`,
		`{"spaces":{"a":"W","b":"w"}}`,
		`null`,
		`{"spaces":{"default":".."}}`,
		`{"images":{"` + strings.Repeat("a", 64) + `":"../x.png"}}`,
		`not json`,
	} {
		dir := t.TempDir()
		if err := os.WriteFile(filepath.Join(dir, manifestName), []byte(man), 0o600); err != nil {
			t.Fatal(err)
		}
		if _, err := loadUser(dir); err == nil || !strings.Contains(err.Error(), "damaged") {
			t.Errorf("%s: %v", man, err)
		}
	}
}

func TestMirrorRenamesAroundOtherFiles(t *testing.T) {
	f := newFixture(t)
	if _, err := f.spaces.Put(ctx, "work", "Work", nil); err != nil {
		t.Fatal(err)
	}
	f.create("a", "Plan", "q3\n", "work")
	f.flush()
	if err := os.WriteFile(filepath.Join(f.dir, "sam", "Work", "mine.txt"), []byte("mine"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := f.spaces.Put(ctx, "work", "Job", nil); err != nil {
		t.Fatal(err)
	}
	f.flush()
	f.wantFiles("Job/", "Job/Plan.md", "Work/", "Work/mine.txt")
}

func TestNotifyDebounces(t *testing.T) {
	m := New(Config{})
	t0 := time.Unix(1_700_000_000, 0)
	now := t0
	m.now = func() time.Time { return now }

	// Every second for 30s: due once they stop for quiet.
	for range 30 {
		m.Notify(1)
		now = now.Add(time.Second)
	}
	if _, due, _ := m.next(); !due.Equal(t0.Add(29*time.Second + quiet)) {
		t.Fatalf("due %v after the first change", due.Sub(t0))
	}
	// Changes that never stop are written every maxWait.
	for range 300 {
		m.Notify(1)
		now = now.Add(time.Second)
	}
	if _, due, _ := m.next(); !due.Equal(t0.Add(maxWait)) {
		t.Fatalf("due %v after the first change", due.Sub(t0))
	}
}

func TestRunWritesChanges(t *testing.T) {
	dataDir, dir := t.TempDir(), t.TempDir()
	users := userdata.NewRegistry(dataDir)
	t.Cleanup(func() { users.Close() })
	m := New(Config{
		Dir:      dir,
		Accounts: func(context.Context) ([]Account, error) { return []Account{{ID: 1, Username: "sam"}}, nil },
		Open: func(ctx context.Context, id int64) (*database.DB, error) {
			d, err := users.Open(ctx, id)
			if err != nil {
				return nil, err
			}
			return d.DB, nil
		},
		Log: quietLog,
	})
	m.quiet, m.maxWait = 10*time.Millisecond, time.Second
	users.OnChange(m.Notify)
	d, err := users.Open(ctx, 1)
	if err != nil {
		t.Fatal(err)
	}

	runCtx, stop := context.WithCancel(ctx)
	done := make(chan struct{})
	go func() { defer close(done); m.Run(runCtx) }()
	t.Cleanup(func() { stop(); <-done })

	if _, err := d.Notes.Create(ctx, notes.CreateInput{OperationID: "op-1", ID: "a", Title: "Hello", Content: "world\n"}); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "sam", "Notes", "Hello.md")
	deadline := time.Now().Add(10 * time.Second)
	for {
		if got, err := os.ReadFile(path); err == nil && string(got) == "# Hello\n\nworld\n" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("Hello.md was never written")
		}
		time.Sleep(10 * time.Millisecond)
	}
}
