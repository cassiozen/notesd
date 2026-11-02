package notes_test

import (
	"errors"
	"slices"
	"strings"
	"testing"

	"notes/server/internal/database"
	"notes/server/internal/images"
	"notes/server/internal/locks"
	"notes/server/internal/notes"
	"notes/server/internal/search"
)

// shown is what note_images says the note shows.
func shown(t *testing.T, db *database.DB, id string) []string {
	t.Helper()
	rows, err := db.R.QueryContext(ctx, `SELECT r.image_id FROM note_images r JOIN notes n ON n.seq = r.note_seq
		WHERE n.id = ? ORDER BY r.image_id`, id)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var ids []string
	for rows.Next() {
		var s string
		if err := rows.Scan(&s); err != nil {
			t.Fatal(err)
		}
		ids = append(ids, s)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	return ids
}

func img(c byte) string { return strings.Repeat(string(c), 64) }

func imgMD(id string) string { return "![](notesd:image/" + id + "?w=4&h=3)\n" }

const sealedBody = locks.Prefix + "c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0"

func TestImageReferencesFollowNoteWrites(t *testing.T) {
	db, s := setup(t)
	a, b, c := img('a'), img('b'), img('c')

	// A plain note's images come from its content; a listed one is ignored.
	if _, err := s.Create(ctx, notes.CreateInput{OperationID: "op-1", ID: "n1", Title: "T",
		Content: imgMD(a) + imgMD(b) + "`" + imgMD(c) + "`", ImageIDs: []string{c}}); err != nil {
		t.Fatal(err)
	}
	if got := shown(t, db, "n1"); !slices.Equal(got, []string{a, b}) {
		t.Fatalf("plain create shows %q", got)
	}

	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "op-2", ID: "n1", BaseRevision: 1, Title: "T", Content: imgMD(b)}); err != nil {
		t.Fatal(err)
	}
	if got := shown(t, db, "n1"); !slices.Equal(got, []string{b}) {
		t.Fatalf("after dropping a: %q", got)
	}

	// Locking swaps the plain images for the encrypted ones the client lists.
	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "op-3", ID: "n1", BaseRevision: 2, Title: "T",
		Content: sealedBody, ImageIDs: []string{c, c}}); err != nil {
		t.Fatal(err)
	}
	if got := shown(t, db, "n1"); !slices.Equal(got, []string{c}) {
		t.Fatalf("locked shows %q", got)
	}

	// A locked save that doesn't list them keeps the images recorded; an empty list clears them.
	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "op-3b", ID: "n1", BaseRevision: 3, Title: "Renamed",
		Content: strings.Replace(sealedBody, "c2Vj", "d2Vj", 1)}); err != nil {
		t.Fatal(err)
	}
	if got := shown(t, db, "n1"); !slices.Equal(got, []string{c}) {
		t.Fatalf("unlisted locked save shows %q", got)
	}
	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "op-3c", ID: "n1", BaseRevision: 4, Title: "Renamed",
		Content: strings.Replace(sealedBody, "c2Vj", "e2Vj", 1), ImageIDs: []string{}}); err != nil {
		t.Fatal(err)
	}
	if got := shown(t, db, "n1"); got != nil {
		t.Fatalf("empty list shows %q", got)
	}
	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "op-3d", ID: "n1", BaseRevision: 5, Title: "Renamed",
		Content: sealedBody, ImageIDs: []string{c}}); err != nil {
		t.Fatal(err)
	}

	// A rebuild can't read a locked note's images, so it keeps them.
	if _, err := search.Rebuild(ctx, db); err != nil {
		t.Fatal(err)
	}
	if got := shown(t, db, "n1"); !slices.Equal(got, []string{c}) {
		t.Fatalf("after rebuild: %q", got)
	}

	if _, err := s.Delete(ctx, notes.DeleteInput{OperationID: "op-4", ID: "n1", BaseRevision: 6}); err != nil {
		t.Fatal(err)
	}
	if got := shown(t, db, "n1"); got != nil {
		t.Fatalf("a deleted note shows %q", got)
	}
}

func TestRebuildRecomputesPlainReferences(t *testing.T) {
	db, s := setup(t)
	a, b := img('a'), img('b')
	create(t, s, "n1", "T", imgMD(a))
	// Out of step, as before references were tracked.
	if _, err := db.W.ExecContext(ctx, `UPDATE note_images SET image_id = ?`, b); err != nil {
		t.Fatal(err)
	}
	if _, err := search.Rebuild(ctx, db); err != nil {
		t.Fatal(err)
	}
	if got := shown(t, db, "n1"); !slices.Equal(got, []string{a}) {
		t.Fatalf("after rebuild: %q", got)
	}
}

func TestImageIDsAreValidated(t *testing.T) {
	_, s := setup(t)
	many := make([]string, images.MaxRefs+1)
	for i := range many {
		many[i] = img('a')
	}
	for name, ids := range map[string][]string{
		"not hex":   {"xyz"},
		"uppercase": {strings.Repeat("A", 64)},
		"too many":  many,
	} {
		_, err := s.Create(ctx, notes.CreateInput{OperationID: "op-" + strings.ReplaceAll(name, " ", "-"), ID: "n-" + strings.ReplaceAll(name, " ", "-"),
			Title: "T", Content: sealedBody, ImageIDs: ids})
		var v *notes.ValidationError
		if !errors.As(err, &v) || v.Field != "imageIds" {
			t.Errorf("%s: %v", name, err)
		}
	}
}

func TestLockedImageIDsRoundTrip(t *testing.T) {
	db, s := setup(t)
	a, b := img('a'), img('b')
	if _, err := s.Create(ctx, notes.CreateInput{OperationID: "op-1", ID: "n1", Title: "T", Content: sealedBody, ImageIDs: []string{b, a}}); err != nil {
		t.Fatal(err)
	}

	// Absent and empty mean different things, so one operation id can't carry both.
	in := notes.UpdateInput{OperationID: "op-2", ID: "n1", BaseRevision: 1, Title: "T2", Content: sealedBody}
	if _, err := s.Update(ctx, in); err != nil {
		t.Fatal(err)
	}
	in.ImageIDs = []string{}
	if _, err := s.Update(ctx, in); !errors.Is(err, notes.ErrOperationReuse) {
		t.Fatalf("reused with [] instead of absent: %v", err)
	}

	// The same body listing its images again records them, without a new revision.
	r, err := s.Update(ctx, notes.UpdateInput{OperationID: "op-3", ID: "n1", BaseRevision: 2, Title: "T2", Content: sealedBody, ImageIDs: []string{a}})
	if err != nil || r.Status != notes.StatusUnchanged {
		t.Fatalf("unchanged body: %+v %v", r, err)
	}
	if got := shown(t, db, "n1"); !slices.Equal(got, []string{a}) {
		t.Fatalf("shows %q", got)
	}

	// Devices that can't read the body get the list with the note.
	n, err := s.Get(ctx, "n1")
	if err != nil || !slices.Equal(n.ImageIDs, []string{a}) {
		t.Fatalf("Get: %q %v", n.ImageIDs, err)
	}
	// And a conflict's current note carries it too.
	_, err = s.Update(ctx, notes.UpdateInput{OperationID: "op-4", ID: "n1", BaseRevision: 1, Title: "old", Content: sealedBody})
	var c *notes.ConflictError
	if !errors.As(err, &c) || !slices.Equal(c.Current.ImageIDs, []string{a}) {
		t.Fatalf("conflict: %v", err)
	}
	create(t, s, "p", "Plain", imgMD(b))
	if p, _ := s.Get(ctx, "p"); p.ImageIDs != nil {
		t.Fatalf("a plain note lists %q", p.ImageIDs)
	}
}

// check finds the images a later version's locked note lists, too.
func TestMissingIncludesLaterLockVersions(t *testing.T) {
	db, s := setup(t)
	if _, err := s.Create(ctx, notes.CreateInput{OperationID: "op-1", ID: "n1", Title: "T", Content: locks.Family + "v2:opaque",
		ImageIDs: []string{img('d')}}); err != nil {
		t.Fatal(err)
	}
	if missing, err := images.NewStore(db).Missing(ctx); err != nil || !slices.Equal(missing, []string{img('d')}) {
		t.Fatalf("missing = %v, %v", missing, err)
	}
}
