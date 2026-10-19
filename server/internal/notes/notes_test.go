package notes_test

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"notes/server/internal/database"
	"notes/server/internal/locks"
	"notes/server/internal/notes"
	"notes/server/internal/search"
	"notes/server/internal/spaces"
)

func setup(t *testing.T) (*database.DB, *notes.Store) {
	t.Helper()
	db, err := database.Open(context.Background(), filepath.Join(t.TempDir(), "notes.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	s := notes.NewStore(db)
	clock := time.UnixMilli(1_700_000_000_000)
	var mu sync.Mutex
	s.SetClock(func() time.Time {
		mu.Lock()
		defer mu.Unlock()
		clock = clock.Add(time.Millisecond)
		return clock
	})
	return db, s
}

var ctx = context.Background()

func create(t *testing.T, s *notes.Store, id, title, content string) notes.Result {
	t.Helper()
	r, err := s.Create(ctx, notes.CreateInput{OperationID: "op-create-" + id, ID: id, Title: title, Content: content})
	if err != nil {
		t.Fatalf("create %s: %v", id, err)
	}
	return r
}

func conflictCode(err error) string {
	var c *notes.ConflictError
	if errors.As(err, &c) {
		return c.Code
	}
	return ""
}

func currentSeq(t *testing.T, db *database.DB) int64 {
	t.Helper()
	var n int64
	if err := db.R.QueryRow(`SELECT current_seq FROM sync_state`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestCreateUpdateDeleteLifecycle(t *testing.T) {
	db, s := setup(t)

	r := create(t, s, "n1", "Shopping", "- Milk")
	if r.Status != notes.StatusCreated || r.Revision != 1 || r.ChangeSeq != 1 || r.HTTPStatus != 201 {
		t.Fatalf("create: %+v", r)
	}

	r, err := s.Update(ctx, notes.UpdateInput{OperationID: "u1", ID: "n1", BaseRevision: 1, Title: "Shopping List", Content: "- Milk\n- Bread"})
	if err != nil {
		t.Fatal(err)
	}
	if r.Status != notes.StatusUpdated || r.Revision != 2 || r.ChangeSeq != 2 {
		t.Fatalf("update: %+v", r)
	}
	n, err := s.Get(ctx, "n1")
	if err != nil || n.Title != "Shopping List" || n.Content != "- Milk\n- Bread" || n.Revision != 2 {
		t.Fatalf("get after update: %+v %v", n, err)
	}

	// Identical content: acknowledged without a new revision.
	r, err = s.Update(ctx, notes.UpdateInput{OperationID: "u2", ID: "n1", BaseRevision: 2, Title: "Shopping List", Content: "- Milk\n- Bread"})
	if err != nil || r.Status != notes.StatusUnchanged || r.Revision != 2 || currentSeq(t, db) != 2 {
		t.Fatalf("no-op update: %+v %v", r, err)
	}

	r, err = s.Delete(ctx, notes.DeleteInput{OperationID: "d1", ID: "n1", BaseRevision: 2})
	if err != nil || r.Status != notes.StatusDeleted || r.Revision != 3 || r.ChangeSeq != 3 || r.DeletedAt == nil {
		t.Fatalf("delete: %+v %v", r, err)
	}
	n, _ = s.Get(ctx, "n1")
	if n.DeletedAt == nil || n.Title != "" || n.Content != "" || n.Revision != 3 {
		t.Fatalf("tombstone: %+v", n)
	}
	if res, _ := search.Search(ctx, db, "milk", search.Filter{}, 10, 0); len(res) != 0 {
		t.Errorf("deleted note still searchable: %+v", res)
	}

	// Deleting again from any base succeeds without a new revision.
	r, err = s.Delete(ctx, notes.DeleteInput{OperationID: "d2", ID: "n1", BaseRevision: 1})
	if err != nil || r.Status != notes.StatusAlreadyDeleted || r.Revision != 3 || currentSeq(t, db) != 3 {
		t.Fatalf("second delete: %+v %v", r, err)
	}
}

func TestStaleUpdateIsRejectedWithCurrent(t *testing.T) {
	_, s := setup(t)
	create(t, s, "n1", "Shopping", "Buy milk")
	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "a", ID: "n1", BaseRevision: 1, Title: "Shopping", Content: "Buy oat milk"}); err != nil {
		t.Fatal(err)
	}
	_, err := s.Update(ctx, notes.UpdateInput{OperationID: "b", ID: "n1", BaseRevision: 1, Title: "Groceries", Content: "Buy milk"})
	var c *notes.ConflictError
	if !errors.As(err, &c) || c.Code != notes.CodeRevisionConflict {
		t.Fatalf("want revision_conflict, got %v", err)
	}
	if c.Current.Revision != 2 || c.Current.Content != "Buy oat milk" {
		t.Fatalf("conflict current = %+v", c.Current)
	}
	// Retrying the rejected operation is rejected again: nothing was recorded.
	_, err = s.Update(ctx, notes.UpdateInput{OperationID: "b", ID: "n1", BaseRevision: 1, Title: "Groceries", Content: "Buy milk"})
	if conflictCode(err) != notes.CodeRevisionConflict {
		t.Fatalf("retry: %v", err)
	}
	// The client merges field-by-field (§6.1) and resubmits on the new base.
	r, err := s.Update(ctx, notes.UpdateInput{OperationID: "c", ID: "n1", BaseRevision: 2, Title: "Groceries", Content: "Buy oat milk"})
	if err != nil || r.Revision != 3 {
		t.Fatalf("merged update: %+v %v", r, err)
	}
}

func TestTombstoneRules(t *testing.T) {
	_, s := setup(t)

	// Deleted elsewhere, edited offline: the edit is rejected, tombstone stands.
	create(t, s, "a", "A", "a")
	if _, err := s.Delete(ctx, notes.DeleteInput{OperationID: "da", ID: "a", BaseRevision: 1}); err != nil {
		t.Fatal(err)
	}
	_, err := s.Update(ctx, notes.UpdateInput{OperationID: "ua", ID: "a", BaseRevision: 1, Title: "A", Content: "offline edit"})
	var c *notes.ConflictError
	if !errors.As(err, &c) || c.Code != notes.CodeNoteDeleted || c.Current.DeletedAt == nil {
		t.Fatalf("edit of tombstone: %v", err)
	}
	// Recreating with the same ID is refused too: no resurrection.
	_, err = s.Create(ctx, notes.CreateInput{OperationID: "ca2", ID: "a", Title: "A"})
	if conflictCode(err) != notes.CodeNoteExists {
		t.Fatalf("recreate: %v", err)
	}

	// Deleted offline, edited elsewhere: the stale deletion is rejected.
	create(t, s, "b", "B", "b")
	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "ub", ID: "b", BaseRevision: 1, Title: "B", Content: "newer"}); err != nil {
		t.Fatal(err)
	}
	_, err = s.Delete(ctx, notes.DeleteInput{OperationID: "db", ID: "b", BaseRevision: 1})
	if conflictCode(err) != notes.CodeRevisionConflict {
		t.Fatalf("stale delete: %v", err)
	}
	if n, _ := s.Get(ctx, "b"); n.DeletedAt != nil || n.Content != "newer" {
		t.Fatalf("note b after stale delete: %+v", n)
	}
}

func TestIdempotentReplay(t *testing.T) {
	db, s := setup(t)
	in := notes.CreateInput{OperationID: "op1", ID: "n1", Title: "T", Content: "C"}
	first, err := s.Create(ctx, in)
	if err != nil {
		t.Fatal(err)
	}
	// The response was lost; the client retries the identical request.
	again, err := s.Create(ctx, in)
	if err != nil {
		t.Fatal(err)
	}
	if !again.Replayed || again.HTTPStatus != 201 || again.Revision != first.Revision || again.ChangeSeq != first.ChangeSeq {
		t.Fatalf("replay = %+v, first = %+v", again, first)
	}
	if currentSeq(t, db) != 1 {
		t.Fatal("replay allocated a new change sequence")
	}

	up := notes.UpdateInput{OperationID: "op2", ID: "n1", BaseRevision: 1, Title: "T2", Content: "C"}
	r1, err := s.Update(ctx, up)
	if err != nil {
		t.Fatal(err)
	}
	// Even after further edits, the old operation replays its old answer
	// instead of being re-evaluated as a conflict.
	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "op3", ID: "n1", BaseRevision: 2, Title: "T3", Content: "C"}); err != nil {
		t.Fatal(err)
	}
	r2, err := s.Update(ctx, up)
	if err != nil || !r2.Replayed || r2.Revision != r1.Revision {
		t.Fatalf("late replay = %+v, %v", r2, err)
	}

	// Same ID, different payload.
	up.Content = "different"
	if _, err := s.Update(ctx, up); !errors.Is(err, notes.ErrOperationReuse) {
		t.Fatalf("reused op id: %v", err)
	}
	if _, err := s.Delete(ctx, notes.DeleteInput{OperationID: "op1", ID: "n1", BaseRevision: 3}); !errors.Is(err, notes.ErrOperationReuse) {
		t.Fatalf("op id reused across kinds: %v", err)
	}
}

func TestValidation(t *testing.T) {
	_, s := setup(t)
	bad := []notes.CreateInput{
		{OperationID: "", ID: "x"},
		{OperationID: "op", ID: ""},
		{OperationID: "op", ID: "has space"},
		{OperationID: "op", ID: "x", Title: string(make([]byte, notes.MaxTitleBytes+1))},
		{OperationID: "op", ID: "x", Content: "bad \xff utf8"},
		{OperationID: "op", ID: "x", Content: "nul \x00"},
	}
	for i, in := range bad {
		var v *notes.ValidationError
		if _, err := s.Create(ctx, in); !errors.As(err, &v) {
			t.Errorf("case %d: want ValidationError, got %v", i, err)
		}
	}
	var v *notes.ValidationError
	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "op", ID: "x", BaseRevision: 0}); !errors.As(err, &v) {
		t.Errorf("baseRevision 0: %v", err)
	}
	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "op", ID: "missing", BaseRevision: 1}); !errors.Is(err, notes.ErrNotFound) {
		t.Errorf("update missing: %v", err)
	}
	if _, err := s.Get(ctx, "missing"); !errors.Is(err, notes.ErrNotFound) {
		t.Errorf("get missing: %v", err)
	}
}

func TestClientTimestamps(t *testing.T) {
	_, s := setup(t)
	past := int64(1_600_000_000_000)
	future := time.Now().Add(48*time.Hour).UnixMilli() + 1_000_000_000_000
	if _, err := s.Create(ctx, notes.CreateInput{OperationID: "a", ID: "a", CreatedAt: &past, UpdatedAt: &past}); err != nil {
		t.Fatal(err)
	}
	if n, _ := s.Get(ctx, "a"); n.CreatedAt != past || n.UpdatedAt != past {
		t.Errorf("offline timestamps not kept: %+v", n)
	}
	if _, err := s.Create(ctx, notes.CreateInput{OperationID: "b", ID: "b", CreatedAt: &future}); err != nil {
		t.Fatal(err)
	}
	if n, _ := s.Get(ctx, "b"); n.CreatedAt == future {
		t.Errorf("implausible future timestamp accepted")
	}
}

func TestListPagination(t *testing.T) {
	_, s := setup(t)
	const total = 23
	for i := 0; i < total; i++ {
		create(t, s, fmt.Sprintf("n%02d", i), fmt.Sprintf("Note %d", i), fmt.Sprintf("**Body** of note %d", i))
	}
	if _, err := s.Delete(ctx, notes.DeleteInput{OperationID: "d", ID: "n05", BaseRevision: 1}); err != nil {
		t.Fatal(err)
	}

	var all []notes.ListItem
	cursor := ""
	for pages := 0; ; pages++ {
		if pages > 10 {
			t.Fatal("pagination does not terminate")
		}
		items, next, err := s.List(ctx, search.Filter{}, 5, cursor)
		if err != nil {
			t.Fatal(err)
		}
		all = append(all, items...)
		if next == "" {
			break
		}
		cursor = next
	}
	if len(all) != total-1 {
		t.Fatalf("listed %d notes, want %d", len(all), total-1)
	}
	if n, err := s.Count(ctx, search.Filter{}); err != nil || n != total-1 {
		t.Fatalf("count = %d, %v; want %d", n, err, total-1)
	}
	seen := map[string]bool{}
	for i, it := range all {
		if seen[it.ID] || it.ID == "n05" {
			t.Fatalf("duplicate or deleted note listed: %s", it.ID)
		}
		seen[it.ID] = true
		if i > 0 && it.UpdatedAt > all[i-1].UpdatedAt {
			t.Fatal("not ordered by updatedAt desc")
		}
	}
	if all[0].ID != "n22" || all[0].Preview != "Body of note 22" {
		t.Errorf("first item = %+v", all[0])
	}
	if _, _, err := s.List(ctx, search.Filter{}, 5, "!!garbage"); err == nil {
		t.Error("garbage cursor accepted")
	}
}

// Many clients race to update the same note from the same base revision:
// exactly one wins, the rest get revision conflicts, and nothing is lost or
// double-counted.
func TestConcurrentUpdatesSameNote(t *testing.T) {
	db, s := setup(t)
	create(t, s, "n", "T", "base")

	const clients = 20
	var wg sync.WaitGroup
	results := make([]error, clients)
	for i := 0; i < clients; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			_, results[i] = s.Update(ctx, notes.UpdateInput{
				OperationID: fmt.Sprintf("op-%d", i), ID: "n", BaseRevision: 1,
				Title: "T", Content: fmt.Sprintf("edit %d", i),
			})
		}(i)
	}
	wg.Wait()

	wins := 0
	for _, err := range results {
		switch {
		case err == nil:
			wins++
		case conflictCode(err) == notes.CodeRevisionConflict:
		default:
			t.Fatalf("unexpected error: %v", err)
		}
	}
	if wins != 1 {
		t.Fatalf("%d updates won, want exactly 1", wins)
	}
	n, _ := s.Get(ctx, "n")
	if n.Revision != 2 || currentSeq(t, db) != 2 {
		t.Fatalf("revision %d, seq %d; want 2, 2", n.Revision, currentSeq(t, db))
	}
}

// Concurrent mutations of different notes all succeed and receive unique,
// gap-free change sequences.
func TestConcurrentMutationsDistinctNotes(t *testing.T) {
	db, s := setup(t)
	const workers, perWorker = 8, 25
	var wg sync.WaitGroup
	errs := make(chan error, workers*perWorker*2)
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func(w int) {
			defer wg.Done()
			for i := 0; i < perWorker; i++ {
				id := fmt.Sprintf("w%d-%d", w, i)
				if _, err := s.Create(ctx, notes.CreateInput{OperationID: "c-" + id, ID: id, Title: id}); err != nil {
					errs <- err
					continue
				}
				if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "u-" + id, ID: id, BaseRevision: 1, Title: id, Content: "x"}); err != nil {
					errs <- err
				}
			}
		}(w)
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Fatal(err)
	}

	total := int64(workers * perWorker)
	if got := currentSeq(t, db); got != 2*total {
		t.Fatalf("current_seq = %d, want %d", got, 2*total)
	}
	rows, err := db.R.Query(`SELECT change_seq, revision FROM notes`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var seqs []int64
	for rows.Next() {
		var seq, rev int64
		if err := rows.Scan(&seq, &rev); err != nil {
			t.Fatal(err)
		}
		if rev != 2 {
			t.Fatalf("revision %d, want 2", rev)
		}
		seqs = append(seqs, seq)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	if int64(len(seqs)) != total {
		t.Fatalf("%d notes, want %d", len(seqs), total)
	}
	sort.Slice(seqs, func(i, j int) bool { return seqs[i] < seqs[j] })
	for i := 1; i < len(seqs); i++ {
		if seqs[i] == seqs[i-1] {
			t.Fatalf("duplicate change_seq %d", seqs[i])
		}
	}
}

// Concurrent retries of one operation apply it once.
func TestConcurrentDuplicateOperation(t *testing.T) {
	db, s := setup(t)
	in := notes.CreateInput{OperationID: "same", ID: "n", Title: "T"}
	var wg sync.WaitGroup
	var mu sync.Mutex
	replays := 0
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			r, err := s.Create(ctx, in)
			if err != nil {
				t.Error(err)
				return
			}
			mu.Lock()
			if r.Replayed {
				replays++
			}
			mu.Unlock()
		}()
	}
	wg.Wait()
	if replays != 9 || currentSeq(t, db) != 1 {
		t.Fatalf("replays = %d, seq = %d; want 9, 1", replays, currentSeq(t, db))
	}
}

// A failure late in the transaction (after the change sequence was taken)
// rolls back everything.
func TestFailedMutationRollsBack(t *testing.T) {
	db, s := setup(t)
	create(t, s, "n", "T", "C")
	if _, err := db.W.Exec(`CREATE TEMP TRIGGER boom AFTER UPDATE ON notes
		WHEN NEW.content = 'boom' BEGIN SELECT RAISE(ABORT, 'injected failure'); END`); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "x", ID: "n", BaseRevision: 1, Title: "T", Content: "boom"}); err == nil {
		t.Fatal("update succeeded despite injected failure")
	}
	n, _ := s.Get(ctx, "n")
	if n.Revision != 1 || n.Content != "C" || currentSeq(t, db) != 1 {
		t.Fatalf("partial state after failed update: %+v seq=%d", n, currentSeq(t, db))
	}
	if res, _ := search.Search(ctx, db, "boom", search.Filter{}, 10, 0); len(res) != 0 {
		t.Fatal("search index kept the failed write")
	}
	// The operation was not recorded, so a corrected retry is not a replay.
	r, err := s.Update(ctx, notes.UpdateInput{OperationID: "y", ID: "n", BaseRevision: 1, Title: "T", Content: "fine"})
	if err != nil || r.Replayed || r.Revision != 2 || r.ChangeSeq != 2 {
		t.Fatalf("after rollback: %+v %v", r, err)
	}
}

func tagNames(t *testing.T, db *database.DB) map[string]int {
	t.Helper()
	tags, err := search.ListTags(ctx, db, "")
	if err != nil {
		t.Fatal(err)
	}
	out := map[string]int{}
	for _, tag := range tags {
		out[tag.Name] = tag.Count
	}
	return out
}

func listIDs(t *testing.T, s *notes.Store, f search.Filter) []string {
	t.Helper()
	items, _, err := s.List(ctx, f, 50, "")
	if err != nil {
		t.Fatal(err)
	}
	ids := []string{}
	for _, it := range items {
		ids = append(ids, it.ID)
	}
	sort.Strings(ids)
	if n, err := s.Count(ctx, f); err != nil || n != len(ids) {
		t.Fatalf("Count(%+v) = %d, %v; list has %d", f, n, err, len(ids))
	}
	return ids
}

func TestTagsFollowNotes(t *testing.T) {
	db, s := setup(t)
	create(t, s, "a", "Roof #Home", "Call about #home/repairs/roof")
	create(t, s, "b", "", "#home/garden and #work")
	create(t, s, "c", "Plain", "no tags, # heading-ish, C#")
	create(t, s, "d", "", "#homework")

	// "home" is spelled as in b, the newest note using it.
	want := map[string]int{"home": 2, "home/repairs": 1, "home/repairs/roof": 1, "home/garden": 1, "work": 1, "homework": 1}
	if got := tagNames(t, db); fmt.Sprint(got) != fmt.Sprint(want) {
		t.Fatalf("tags = %v, want %v", got, want)
	}

	// A parent matches its nested tags but not a tag that merely starts with it.
	if ids := listIDs(t, s, search.Filter{Tag: "home"}); fmt.Sprint(ids) != "[a b]" {
		t.Fatalf("home = %v", ids)
	}
	if ids := listIDs(t, s, search.Filter{Tag: "home/repairs"}); fmt.Sprint(ids) != "[a]" {
		t.Fatalf("home/repairs = %v", ids)
	}
	if ids := listIDs(t, s, search.Filter{Untagged: true}); fmt.Sprint(ids) != "[c]" {
		t.Fatalf("untagged = %v", ids)
	}
	if res, _ := search.Search(ctx, db, "home", search.Filter{Tag: "work"}, 10, 0); len(res) != 1 || res[0].ID != "b" {
		t.Fatalf("search in #work = %+v", res)
	}

	// Edits and deletion keep tags in step; the newest spelling names a tag.
	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "u", ID: "b", BaseRevision: 1, Title: "", Content: "#WORK only"}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Delete(ctx, notes.DeleteInput{OperationID: "x", ID: "a", BaseRevision: 1}); err != nil {
		t.Fatal(err)
	}
	want = map[string]int{"WORK": 1, "homework": 1}
	if got := tagNames(t, db); fmt.Sprint(got) != fmt.Sprint(want) {
		t.Fatalf("after edits tags = %v, want %v", got, want)
	}
	if ids := listIDs(t, s, search.Filter{Untagged: true}); fmt.Sprint(ids) != "[c]" {
		t.Fatalf("untagged after delete = %v", ids)
	}
}

func TestRebuildIfNeeded(t *testing.T) {
	db, s := setup(t)
	create(t, s, "a", "", "#kept")
	// A database from before tags: notes but no tag rows.
	if err := db.WriteTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.Exec(`DELETE FROM note_tags`)
		return err
	}); err != nil {
		t.Fatal(err)
	}
	if rebuilt, err := search.RebuildIfNeeded(ctx, db); err != nil || !rebuilt {
		t.Fatalf("first: %v %v", rebuilt, err)
	}
	if got := tagNames(t, db); got["kept"] != 1 {
		t.Fatalf("tags after rebuild = %v", got)
	}
	if rebuilt, err := search.RebuildIfNeeded(ctx, db); err != nil || rebuilt {
		t.Fatalf("second: %v %v", rebuilt, err)
	}
}

func TestLockedNotes(t *testing.T) {
	db, s := setup(t)
	// 28 bytes of base64url: the smallest well-formed envelope.
	sealed := locks.Prefix + "c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0"
	create(t, s, "a", "Bank #private", sealed)
	create(t, s, "b", "Open", "plain #public text")

	items, _, err := s.List(ctx, search.Filter{}, 50, "")
	if err != nil {
		t.Fatal(err)
	}
	got := map[string]notes.ListItem{}
	for _, it := range items {
		got[it.ID] = it
	}
	if len(items) != 2 || len(got) != 2 {
		t.Fatalf("list = %+v", items)
	}
	if a := got["a"]; !a.Locked || a.Preview != "" {
		t.Fatalf("locked item = %+v", a)
	}
	if b := got["b"]; b.Locked || b.Preview == "" {
		t.Fatalf("plain item = %+v", b)
	}
	// Only the title is indexed and tagged.
	if got := tagNames(t, db); fmt.Sprint(got) != "map[private:1 public:1]" {
		t.Fatalf("tags = %v", got)
	}
	if res, _ := search.Search(ctx, db, "c2VjcmV0", search.Filter{}, 10, 0); len(res) != 0 {
		t.Fatalf("ciphertext searchable: %+v", res)
	}
	if res, _ := search.Search(ctx, db, "bank", search.Filter{}, 10, 0); len(res) != 1 || !res[0].Locked || res[0].Excerpt != "" {
		t.Fatalf("title search = %+v", res)
	}
	if ids := listIDs(t, s, search.Filter{Locked: true}); fmt.Sprint(ids) != "[a]" {
		t.Fatalf("locked = %v", ids)
	}
	if n, err := s.Count(ctx, search.Filter{Locked: true}); err != nil || n != 1 {
		t.Fatalf("locked count = %d %v", n, err)
	}

	// An edit made on the ciphertext as text is refused rather than stored.
	_, err = s.Update(ctx, notes.UpdateInput{OperationID: "bad", ID: "a", BaseRevision: 1, Title: "Bank", Content: sealed + " typed"})
	var verr *notes.ValidationError
	if !errors.As(err, &verr) || verr.Field != "content" {
		t.Fatalf("broken envelope: %v", err)
	}

	// Removing the lock is an ordinary update; the body is indexed again.
	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "unlock", ID: "a", BaseRevision: 1, Title: "Bank", Content: "PIN #private"}); err != nil {
		t.Fatal(err)
	}
	if res, _ := search.Search(ctx, db, "pin", search.Filter{}, 10, 0); len(res) != 1 || res[0].Locked {
		t.Fatalf("after unlock = %+v", res)
	}
	if ids := listIDs(t, s, search.Filter{Locked: true}); len(ids) != 0 {
		t.Fatalf("locked after unlock = %v", ids)
	}
}

func TestLockedNoteSizeLimit(t *testing.T) {
	_, s := setup(t)
	// The envelope of the largest note allowed is longer than the cap itself.
	full := locks.EnvelopeLen(notes.MaxContentBytes)
	sealed := locks.Prefix + strings.Repeat("A", full-len(locks.Prefix))
	if !locks.ValidEnvelope(sealed) {
		t.Fatal("test envelope invalid")
	}
	if _, err := s.Create(ctx, notes.CreateInput{OperationID: "big", ID: "a", Title: "Big", Content: sealed}); err != nil {
		t.Fatalf("largest locked note: %v", err)
	}
	sealed += "AAAA"
	_, err := s.Create(ctx, notes.CreateInput{OperationID: "bigger", ID: "b", Title: "Bigger", Content: sealed})
	var verr *notes.ValidationError
	if !errors.As(err, &verr) || verr.Field != "content" {
		t.Fatalf("too large: %v", err)
	}
}

// Locking a note leaves none of its old text in the database file, its
// WAL, or the search index's own storage (§10.1).
func TestLockingScrubsOldText(t *testing.T) {
	db, s := setup(t)
	const secret = "zanzibarquokka"
	create(t, s, "a", "Bank", "PIN 1234 "+secret+"\n")
	create(t, s, "b", "Other", "unrelated words")
	sealed := locks.Prefix + "c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0"
	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "lock", ID: "a", BaseRevision: 1, Title: "Bank", Content: sealed}); err != nil {
		t.Fatal(err)
	}
	for _, f := range []string{db.Path, db.Path + "-wal"} {
		b, err := os.ReadFile(f)
		if err != nil && !os.IsNotExist(err) {
			t.Fatal(err)
		}
		if bytes.Contains(b, []byte(secret)) {
			t.Errorf("%s still holds the text from before locking", filepath.Base(f))
		}
	}
}

func TestMovingBetweenSpaces(t *testing.T) {
	db, s := setup(t)
	if _, err := spaces.NewStore(db).Put(ctx, "work", "Work", nil); err != nil {
		t.Fatal(err)
	}
	if r := create(t, s, "a", "Plan", "body"); r.SpaceID != spaces.Default {
		t.Fatalf("no space named: %q", r.SpaceID)
	}
	work := "work"

	// The same title and content in another space is a change, not "unchanged".
	in := notes.UpdateInput{OperationID: "move", ID: "a", BaseRevision: 1, Title: "Plan", Content: "body", SpaceID: &work}
	r, err := s.Update(ctx, in)
	if err != nil || r.Status != notes.StatusUpdated || r.Revision != 2 || r.SpaceID != "work" {
		t.Fatalf("move: %+v %v", r, err)
	}
	if again, err := s.Update(ctx, in); err != nil || !again.Replayed || again.SpaceID != "work" {
		t.Fatalf("replay: %+v %v", again, err)
	}
	// Without a space the note stays where it is.
	r, err = s.Update(ctx, notes.UpdateInput{OperationID: "edit", ID: "a", BaseRevision: 2, Title: "Plan", Content: "more"})
	if err != nil || r.SpaceID != "work" {
		t.Fatalf("edit: %+v %v", r, err)
	}
	if n, _ := s.Get(ctx, "a"); n.SpaceID != "work" {
		t.Fatalf("stored space: %q", n.SpaceID)
	}
	// Moving into the space it is already in changes nothing.
	r, err = s.Update(ctx, notes.UpdateInput{OperationID: "stay", ID: "a", BaseRevision: 3, Title: "Plan", Content: "more", SpaceID: &work})
	if err != nil || r.Status != notes.StatusUnchanged {
		t.Fatalf("stay: %+v %v", r, err)
	}
	items, _, err := s.List(ctx, search.Filter{Space: "work"}, 10, "")
	if err != nil || len(items) != 1 || items[0].SpaceID != "work" {
		t.Fatalf("list in work: %+v %v", items, err)
	}
	if n, _ := s.Count(ctx, search.Filter{Space: spaces.Default}); n != 0 {
		t.Fatalf("default still counts %d", n)
	}

	bad := "no.pe"
	if _, err := s.Create(ctx, notes.CreateInput{OperationID: "bad", ID: "b", SpaceID: &bad}); err == nil {
		t.Fatal("invalid space id accepted")
	}
}
