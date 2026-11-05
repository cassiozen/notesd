package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"strings"

	"github.com/google/uuid"

	"notes/server/internal/database"
	"notes/server/internal/images"
	"notes/server/internal/mdimport"
	"notes/server/internal/notes"
	"notes/server/internal/spaces"
)

// storeWriter writes an import through the stores the API uses, so search,
// tags, image references and sync see imported notes like any other.
type storeWriter struct {
	notes   *notes.Store
	images  *images.Store
	spaceID string
}

func (w storeWriter) PutImage(ctx context.Context, id, contentType string, data []byte) error {
	_, _, err := w.images.Put(ctx, id, contentType, data)
	return err
}

func (w storeWriter) CreateNote(ctx context.Context, n mdimport.Note) (bool, error) {
	in := notes.CreateInput{
		// From the note id: a run cut short and started again replays it.
		OperationID: "import-" + n.ID,
		ID:          n.ID,
		Title:       n.Title,
		Content:     n.Content,
		SpaceID:     &w.spaceID,
	}
	if n.CreatedAt > 0 {
		in.CreatedAt = &n.CreatedAt
	}
	if n.UpdatedAt > 0 {
		in.UpdatedAt = &n.UpdatedAt
	}
	res, err := w.notes.Create(ctx, in)
	var conflict *notes.ConflictError
	switch {
	case errors.As(err, &conflict) && conflict.Code == notes.CodeNoteExists:
		return false, nil
	case err != nil:
		return false, err
	}
	return !res.Replayed, nil
}

// dryWriter stores nothing; it only says which notes are already there.
type dryWriter struct{ notes *notes.Store }

func (dryWriter) PutImage(context.Context, string, string, []byte) error { return nil }

func (w dryWriter) CreateNote(ctx context.Context, n mdimport.Note) (bool, error) {
	_, err := w.notes.Get(ctx, n.ID)
	if errors.Is(err, notes.ErrNotFound) {
		return true, nil
	}
	return false, err
}

// importSpace finds the space named name, ignoring case, or creates it
// (unless dryRun). No name is the default space.
func importSpace(ctx context.Context, db *database.DB, name string, dryRun bool) (sp spaces.Space, created bool, err error) {
	store := spaces.NewStore(db)
	list, err := store.List(ctx)
	if err != nil {
		return sp, false, err
	}
	name = strings.Join(strings.Fields(name), " ")
	for _, s := range list {
		if name == "" && s.ID == spaces.Default || name != "" && spaces.Key(s.Name) == spaces.Key(name) {
			return s, false, nil
		}
	}
	if dryRun {
		return spaces.Space{Name: name}, true, nil
	}
	id, err := uuid.NewV7()
	if err != nil {
		return sp, false, err
	}
	sp, err = store.Put(ctx, id.String(), name, nil)
	return sp, err == nil, err
}

func importNotes(ctx context.Context, db *database.DB, o options, stdout io.Writer) error {
	src, err := mdimport.Open(o.from)
	if err != nil {
		return err
	}
	defer src.Close()
	sp, created, err := importSpace(ctx, db, o.space, o.dryRun)
	if err != nil {
		return fmt.Errorf("space %q: %w", o.space, err)
	}
	ns := notes.NewStore(db)
	var w mdimport.Writer = storeWriter{notes: ns, images: images.NewStore(db), spaceID: sp.ID}
	if o.dryRun {
		w = dryWriter{notes: ns}
	}
	rep, runErr := mdimport.Run(ctx, src, mdimport.Options{SpaceID: sp.ID, LineBreaks: !o.noBreaks}, w)
	printImport(stdout, rep, o, sp.Name, created)
	return runErr
}

func printImport(out io.Writer, rep mdimport.Report, o options, space string, created bool) {
	verb, spaceVerb := "imported", "created"
	if o.dryRun {
		verb, spaceVerb = "would import", "would create"
	}
	if created {
		fmt.Fprintf(out, "%s space %s\n", spaceVerb, space)
	}
	fmt.Fprintf(out, "%s %d notes and %d images from %s into %s\n", verb, rep.Notes, rep.Images, o.from, space)
	if rep.Existing > 0 {
		fmt.Fprintf(out, "skipped %d notes already there: imported before, or the same text twice\n", rep.Existing)
	}
	if rep.NoteLinks > 0 {
		fmt.Fprintf(out, "made %d links to other notes plain text; notesd has no links between notes\n", rep.NoteLinks)
	}
	if rep.RemoteImages > 0 {
		fmt.Fprintf(out, "left %d web images as links to their sites; they are never downloaded\n", rep.RemoteImages)
	}
	if rep.OtherFiles > 0 {
		fmt.Fprintf(out, "left out %d files that are neither notes nor images a note shows\n", rep.OtherFiles)
	}
	if len(rep.Skipped) > 0 {
		fmt.Fprintf(out, "\nskipped %d files:\n", len(rep.Skipped))
		for _, is := range rep.Skipped {
			fmt.Fprintf(out, "  %s: %s\n", is.Path, is.Message)
		}
	}
	if len(rep.Changed) > 0 {
		fmt.Fprintln(out, "\nimported with changes:")
		for _, is := range rep.Changed {
			fmt.Fprintf(out, "  %s: %s\n", is.Path, is.Message)
		}
	}
}
