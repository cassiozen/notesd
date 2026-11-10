package sharing

import (
	"context"
	"errors"
	"fmt"

	"notes/server/internal/images"
	"notes/server/internal/notes"
	"notes/server/internal/userdata"
)

// Writes arrive at the account's address whatever file the note is in (§7.9): clients never learn
// how notes are stored. A move between files is the update that names the new space.

// File is one notes file an account reaches.
type File struct {
	Data *userdata.Data
	// SpaceID is the shared space whose file it is; "" for the account's own.
	SpaceID string
}

func (f File) ref(userID int64) string {
	if f.SpaceID != "" {
		return SpaceRef(f.SpaceID)
	}
	return UserRef(userID)
}

// ErrKeptMoving is a write that kept meeting a note or space moving under it.
var ErrKeptMoving = errors.New("the note kept moving between files: try again")

// A share or move can run between choosing a file and writing it: the write then starts over.
const attempts = 3

// Files lists every notes file userID reaches: their own first, then each shared space they're in, each
// held (see Open) until release is called.
func (s *Service) Files(ctx context.Context, userID int64) (_ []File, _ func(), err error) {
	var releases []func()
	// Not a named result: `return nil, nil, err` would nil it before the deferred call.
	releaseAll := func() {
		for _, r := range releases {
			r()
		}
	}
	defer func() {
		if err != nil {
			releaseAll()
		}
	}()
	own, err := s.files.Open(ctx, userID)
	if err != nil {
		return nil, nil, err
	}
	out := []File{{Data: own}}
	ids, err := s.SpaceIDs(ctx, userID)
	if err != nil {
		return nil, nil, err
	}
	for _, id := range ids {
		d, r, err := s.Open(ctx, userID, id)
		if errors.Is(err, ErrNotFound) {
			continue // left or deleted since the list
		} else if err != nil {
			return nil, nil, err
		}
		releases = append(releases, r)
		out = append(out, File{Data: d, SpaceID: id})
	}
	return out, releaseAll, nil
}

// ForSpace is the file a space's notes are in for userID: the shared space's if it is one they're in,
// else their own (which files a space it doesn't have in its home space).
// Held until release is called.
func (s *Service) ForSpace(ctx context.Context, userID int64, spaceID string) (File, func(), error) {
	files, release, err := s.Files(ctx, userID)
	if err != nil {
		return File{}, nil, err
	}
	return forSpace(files, spaceID), release, nil
}

func forSpace(files []File, spaceID string) File {
	for _, f := range files[1:] {
		if f.SpaceID == spaceID {
			return f
		}
	}
	return files[0]
}

// Locate finds a note among the files userID reaches: where it's live, else where it was deleted. One
// moved only to files they don't reach is deleted as far as they can tell: no movedTo. The file is held
// until release is called.
func (s *Service) Locate(ctx context.Context, userID int64, id string) (File, notes.Note, func(), error) {
	files, release, err := s.Files(ctx, userID)
	if err != nil {
		return File{}, notes.Note{}, nil, err
	}
	f, n, err := locate(ctx, files, id)
	if err != nil {
		release()
		return File{}, notes.Note{}, nil, err
	}
	return f, n, release, nil
}

func locate(ctx context.Context, files []File, id string) (File, notes.Note, error) {
	var deleted, moved *File
	var deletedNote, movedNote notes.Note
	for i := range files {
		n, err := files[i].Data.Notes.Get(ctx, id)
		switch {
		case errors.Is(err, notes.ErrNotFound):
			continue
		case err != nil:
			return File{}, notes.Note{}, err
		case n.DeletedAt == nil:
			return files[i], n, nil
		case n.MovedTo == nil:
			if deleted == nil {
				deleted, deletedNote = &files[i], n
			}
		default:
			if moved == nil {
				moved, movedNote = &files[i], n
			}
		}
	}
	switch {
	case deleted != nil:
		return *deleted, deletedNote, nil
	case moved != nil:
		movedNote.MovedTo = nil
		return *moved, movedNote, nil
	}
	return File{}, notes.Note{}, notes.ErrNotFound
}

// replayed is the file that recorded an operation: a retry is answered there, wherever the note is now.
func replayed(ctx context.Context, files []File, opID string) (*File, error) {
	if opID == "" {
		return nil, nil
	}
	for i := range files {
		ok, err := files[i].Data.Notes.Applied(ctx, opID)
		if err != nil {
			return nil, err
		}
		if ok {
			return &files[i], nil
		}
	}
	return nil, nil
}

// CreateNote stores a new note in the file of the space it names.
func (s *Service) CreateNote(ctx context.Context, userID int64, in notes.CreateInput) (notes.Result, error) {
	in.UpdatedBy = userID
	elsewhere := func(err error) bool { return errors.As(err, new(*notes.ElsewhereError)) }
	return s.routed(ctx, userID, elsewhere, func(files []File) (notes.Result, error) {
		if f, err := replayed(ctx, files, in.OperationID); err != nil {
			return notes.Result{}, err
		} else if f != nil {
			return f.Data.Notes.Create(ctx, in)
		}
		dest := files[0]
		if in.SpaceID != nil {
			dest = forSpace(files, *in.SpaceID)
		}
		// Ids are one note's wherever it is: one live in another file is taken.
		if f, n, err := locate(ctx, files, in.ID); err == nil && n.DeletedAt == nil && f.Data != dest.Data {
			return notes.Result{}, &notes.ConflictError{Code: notes.CodeNoteExists, Current: n}
		} else if err != nil && !errors.Is(err, notes.ErrNotFound) {
			return notes.Result{}, err
		}
		var err error
		if in.Carry, err = carried(ctx, files, dest, notes.ShownImages(in.Content, in.ImageIDs)); err != nil {
			return notes.Result{}, err
		}
		return dest.Data.Notes.Create(ctx, in)
	})
}

// UpdateNote changes a note where it is, moving it first if it names a space in another file.
func (s *Service) UpdateNote(ctx context.Context, userID int64, in notes.UpdateInput) (notes.Result, error) {
	in.UpdatedBy = userID
	return s.routed(ctx, userID, retry, func(files []File) (notes.Result, error) {
		if f, err := replayed(ctx, files, in.OperationID); err != nil {
			return notes.Result{}, err
		} else if f != nil {
			return f.Data.Notes.Update(ctx, in)
		}
		src, cur, err := locate(ctx, files, in.ID)
		if err != nil {
			return notes.Result{}, err
		}
		if cur.DeletedAt != nil {
			return notes.Result{}, &notes.ConflictError{Code: notes.CodeNoteDeleted, Current: cur}
		}
		dest := src
		if in.SpaceID != nil && *in.SpaceID != cur.SpaceID {
			if dest, err = destination(ctx, files, src, *in.SpaceID); err != nil {
				return notes.Result{}, err
			}
		}
		if dest.Data != src.Data {
			return s.move(ctx, userID, files, src, dest, in)
		}
		if in.Carry, err = carried(ctx, files, dest, notes.ShownImages(in.Content, in.ImageIDs)); err != nil {
			return notes.Result{}, err
		}
		return src.Data.Notes.Update(ctx, in)
	})
}

// DeleteNote deletes a note where it is.
func (s *Service) DeleteNote(ctx context.Context, userID int64, in notes.DeleteInput) (notes.Result, error) {
	in.UpdatedBy = userID
	return s.routed(ctx, userID, retry, func(files []File) (notes.Result, error) {
		if f, err := replayed(ctx, files, in.OperationID); err != nil {
			return notes.Result{}, err
		} else if f != nil {
			return f.Data.Notes.Delete(ctx, in)
		}
		src, cur, err := locate(ctx, files, in.ID)
		if err != nil {
			return notes.Result{}, err
		}
		if cur.DeletedAt != nil {
			// Moved only out of reach counts too: answered here, as its file would answer a move.
			return notes.Result{ID: in.ID, Status: notes.StatusAlreadyDeleted, Revision: cur.Revision,
				ChangeSeq: cur.ChangeSeq, UpdatedAt: cur.UpdatedAt, DeletedAt: cur.DeletedAt, HTTPStatus: 200}, nil
		}
		return src.Data.Notes.Delete(ctx, in)
	})
}

// routed runs write over the files userID reaches, held meanwhile, starting over while again(err).
func (s *Service) routed(ctx context.Context, userID int64, again func(error) bool,
	write func([]File) (notes.Result, error)) (notes.Result, error) {
	for range attempts {
		files, release, err := s.Files(ctx, userID)
		if err != nil {
			return notes.Result{}, err
		}
		res, err := write(files)
		release()
		if !again(err) {
			return res, err
		}
	}
	return notes.Result{}, ErrKeptMoving
}

// retry reports whether a write met a share or move that ran after it chose its file.
func retry(err error) bool {
	var conflict *notes.ConflictError
	return errors.As(err, new(*notes.ElsewhereError)) ||
		(errors.As(err, &conflict) && conflict.Code == notes.CodeNoteMoved)
}

// destination is the file spaceID's notes are in: a shared space the account is in, or one of its own.
// A space deleted since it was chosen leaves the note in src, which files it in its home space.
func destination(ctx context.Context, files []File, src File, spaceID string) (File, error) {
	if f := forSpace(files, spaceID); f.SpaceID != "" {
		return f, nil
	}
	if ok, err := files[0].Data.Notes.HasSpace(ctx, spaceID); err != nil || !ok {
		return src, err
	}
	return files[0], nil
}

// move moves a note from src to dest with the update that named dest's space, as one write there,
// logged first so a stop between the two files is finished or undone at startup.
func (s *Service) move(ctx context.Context, userID int64, files []File, src, dest File, in notes.UpdateInput) (notes.Result, error) {
	spaceID := *in.SpaceID
	s.moving.Lock()
	defer s.moving.Unlock()
	moveID := newID()
	if err := s.logMove(ctx, moveID, spaceID, in.ID, src.ref(userID), dest.ref(userID)); err != nil {
		return notes.Result{}, err
	}
	res, err := src.Data.Notes.MoveOut(ctx, notes.MoveInput{
		OperationID:  in.OperationID,
		ID:           in.ID,
		BaseRevision: in.BaseRevision,
		SpaceID:      spaceID,
		UpdatedBy:    userID,
		Fingerprint:  in.Fingerprint,
		Origin:       in.Origin,
	}, func(c notes.Carried) (notes.Result, error) {
		var err error
		if in.Carry, err = carried(ctx, files, dest, notes.ShownImages(in.Content, in.ImageIDs)); err != nil {
			return notes.Result{}, err
		}
		if c, err = src.Data.Notes.Edited(c, in); err != nil {
			return notes.Result{}, err
		}
		c.Origin = in.Origin
		return dest.Data.Notes.MoveIn(ctx, c, spaceID)
	})
	if err != nil {
		live, lerr := dest.Data.Notes.Live(ctx, in.ID)
		if lerr != nil {
			return res, errors.Join(err, lerr)
		}
		// The destination kept it: finished now, so the note isn't live in both files. A retry then hears
		// note_moved and finds it there. Left logged for startup only if that fails too.
		if live {
			if ferr := src.Data.Notes.FinishMoveOut(ctx, in.ID, spaceID); ferr != nil {
				return res, errors.Join(err, ferr)
			}
		}
	}
	return res, errors.Join(err, s.clearMove(ctx, moveID))
}

// carried is the images among ids dest lacks but another file the writer reaches has (uploads go to
// the account's file): stored with the note so every member can load them.
func carried(ctx context.Context, files []File, dest File, ids []string) ([]images.Blob, error) {
	var out []images.Blob
	for _, id := range ids {
		if _, err := dest.Data.Images.Info(ctx, id); err == nil {
			continue
		} else if !errors.Is(err, images.ErrNotFound) {
			return nil, err
		}
		for _, f := range files {
			if f.Data == dest.Data {
				continue
			}
			img, data, err := f.Data.Images.Get(ctx, id)
			if errors.Is(err, images.ErrNotFound) {
				continue
			} else if err != nil {
				return nil, fmt.Errorf("image %s: %w", id, err)
			}
			out = append(out, images.Blob{ID: img.ID, ContentType: img.ContentType, Data: data, CreatedAt: img.CreatedAt})
			break
		}
	}
	return out, nil
}

// FileFor is the file among files, as Files lists them, that holds spaceID.
func FileFor(files []File, spaceID string) File { return forSpace(files, spaceID) }

// FileOf is the file among files where note id is (live, else deleted); ok is false when none has it.
func FileOf(ctx context.Context, files []File, id string) (f File, ok bool, err error) {
	f, _, err = locate(ctx, files, id)
	if errors.Is(err, notes.ErrNotFound) {
		return File{}, false, nil
	}
	return f, err == nil, err
}
