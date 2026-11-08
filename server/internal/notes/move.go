package notes

import (
	"context"
	"database/sql"
	"errors"
	"fmt"

	"notes/server/internal/images"
	"notes/server/internal/locks"
	"notes/server/internal/search"
	"notes/server/internal/spaces"
)

// Moves between files (§7.9): the source's write transaction spans the destination's, so what's stored
// there is exactly what becomes tombstones here. Callers log moves and finish cut-short ones at startup.

// Carried is a note on its way to another file, with the images it shows.
type Carried struct {
	Note   Note
	Images []images.Blob
}

// SpaceRow is a space as a shared space's file records it.
type SpaceRow struct {
	ID        string
	Name      string
	Icon      string
	CreatedAt int64
}

// LockedInSpaceError refuses to share a space that holds locked notes.
type LockedInSpaceError struct{ Count int }

func (e *LockedInSpaceError) Error() string {
	return fmt.Sprintf("the space holds %d locked notes", e.Count)
}

// MoveInput moves a note to a space in another file.
type MoveInput struct {
	OperationID  string `json:"operationId"`
	ID           string `json:"id"`
	BaseRevision int64  `json:"baseRevision"`
	SpaceID      string `json:"spaceId"`
	// As CreateInput.UpdatedBy.
	UpdatedBy int64 `json:"-"`
	// As CreateInput.Fingerprint.
	Fingerprint string `json:"-"`
}

// MoveOut moves a note out of this file: write stores it in the destination and returns its revision
// there, then a moved tombstone stays here. Answers like an update; idempotent by operation ID.
func (s *Store) MoveOut(ctx context.Context, in MoveInput, write func(Carried) (Result, error)) (Result, error) {
	if err := validateOp(in.OperationID); err != nil {
		return Result{}, err
	}
	if err := validateID("id", in.ID); err != nil {
		return Result{}, err
	}
	if err := validateID("spaceId", in.SpaceID); err != nil {
		return Result{}, err
	}
	if in.BaseRevision < 1 {
		return Result{}, &ValidationError{"baseRevision", "must be a positive revision"}
	}
	now := s.now().UnixMilli()
	return s.apply(ctx, in.OperationID, fingerprint(in.Fingerprint, "move", in.ID, in), func(tx *sql.Tx) (Result, error) {
		cur, err := loadForUpdate(ctx, tx, in.ID)
		if err != nil {
			return Result{}, err
		}
		if err := gone(cur); err != nil {
			return Result{}, err
		}
		if cur.Revision != in.BaseRevision {
			return Result{}, &ConflictError{Code: CodeRevisionConflict, Current: cur.Note}
		}
		c, err := carry(ctx, tx, cur)
		if err != nil {
			return Result{}, err
		}
		dest, err := write(c)
		if err != nil {
			return Result{}, err
		}
		if err := tombstoneMoved(ctx, tx, cur, in.SpaceID, in.UpdatedBy, now); err != nil {
			return Result{}, err
		}
		return Result{ID: cur.ID, Status: StatusUpdated, Revision: dest.Revision, ChangeSeq: dest.ChangeSeq,
			UpdatedAt: dest.UpdatedAt, SpaceID: in.SpaceID, HTTPStatus: 200}, nil
	})
}

// MoveIn stores a note moved here into space. Revisions only grow: past a tombstone an earlier move left.
// A note already live here (a move finished after a crash) is answered as it is.
func (s *Store) MoveIn(ctx context.Context, c Carried, space string) (Result, error) {
	if err := s.validateText(c.Note.Title, c.Note.Content); err != nil {
		return Result{}, err
	}
	var out Result
	err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		if got, err := spaces.Resolve(ctx, tx, space); err != nil {
			return err
		} else if got != space || (s.shared && space != s.home) {
			return &ValidationError{"spaceId", "no such space"}
		}
		rev := c.Note.Revision
		cur, err := loadForUpdate(ctx, tx, c.Note.ID)
		switch {
		case errors.Is(err, ErrNotFound):
		case err != nil:
			return err
		case cur.DeletedAt == nil:
			out = Result{ID: cur.ID, Status: StatusUnchanged, Revision: cur.Revision, ChangeSeq: cur.ChangeSeq,
				UpdatedAt: cur.UpdatedAt, SpaceID: cur.SpaceID, HTTPStatus: 200}
			return nil
		case cur.MovedTo == nil:
			// Deleted here for good: a stale device must never see it come back (§6.4).
			return &ConflictError{Code: CodeNoteExists, Current: cur.Note}
		default:
			rev = max(rev, cur.Revision+1)
		}
		seq, err := s.store(ctx, tx, c, space, rev, cur.rowid)
		if err != nil {
			return err
		}
		out = Result{ID: c.Note.ID, Status: StatusUpdated, Revision: rev, ChangeSeq: seq,
			UpdatedAt: c.Note.UpdatedAt, SpaceID: space, HTTPStatus: 200}
		return nil
	})
	if err == nil && out.Status == StatusUpdated {
		s.committed()
	}
	return out, err
}

// ShareOut moves a space being shared, and its live notes, to the file write stores them in; moved
// tombstones and no space row stay here. Locked notes refuse it (LockedInSpaceError).
func (s *Store) ShareOut(ctx context.Context, spaceID string, by int64, write func(SpaceRow, []Carried) error) error {
	now := s.now().UnixMilli()
	err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		row := SpaceRow{ID: spaceID}
		err := tx.QueryRowContext(ctx, `SELECT name, icon, created_at FROM spaces WHERE id = ?`, spaceID).
			Scan(&row.Name, &row.Icon, &row.CreatedAt)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrNotFound
		} else if err != nil {
			return err
		}
		var locked int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM notes WHERE space_id = ? AND deleted_at IS NULL
			AND substr(content, 1, ?) = ?`, spaceID, len(locks.Family), locks.Family).Scan(&locked); err != nil {
			return err
		}
		if locked > 0 {
			return &LockedInSpaceError{Count: locked}
		}
		live, err := liveIn(ctx, tx, spaceID)
		if err != nil {
			return err
		}
		carried := make([]Carried, len(live))
		for i, cur := range live {
			if carried[i], err = carry(ctx, tx, cur); err != nil {
				return err
			}
		}
		if err := write(row, carried); err != nil {
			return err
		}
		return finishShareOut(ctx, tx, live, spaceID, by, now)
	})
	if err == nil {
		s.committed()
	}
	return err
}

// ShareIn stores a space and its notes, moved here by ShareOut, in what must
// be a new shared space's file.
func (s *Store) ShareIn(ctx context.Context, space SpaceRow, notes []Carried) error {
	err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `INSERT INTO spaces (id, name, name_key, icon, created_at) VALUES (?, ?, ?, ?, ?)
			ON CONFLICT (id) DO NOTHING`, space.ID, space.Name, spaces.Key(space.Name), space.Icon, space.CreatedAt); err != nil {
			return err
		}
		for _, c := range notes {
			if _, err := s.store(ctx, tx, c, space.ID, c.Note.Revision, 0); err != nil {
				return err
			}
		}
		return nil
	})
	if err == nil {
		s.committed()
	}
	return err
}

// FinishMoveOut leaves the moved tombstone of a note whose move was cut short
// after its destination stored it. Nothing to do if it already has one.
func (s *Store) FinishMoveOut(ctx context.Context, id, dest string) error {
	done := true
	err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		cur, err := loadForUpdate(ctx, tx, id)
		if errors.Is(err, ErrNotFound) {
			return nil
		} else if err != nil {
			return err
		}
		if cur.DeletedAt != nil {
			return nil
		}
		done = false
		return tombstoneMoved(ctx, tx, cur, dest, 0, s.now().UnixMilli())
	})
	if err == nil && !done {
		s.committed()
	}
	return err
}

// FinishShareOut finishes a ShareOut cut short after the space's own file
// stored its notes: their moved tombstones here, and the space's row gone.
func (s *Store) FinishShareOut(ctx context.Context, spaceID string) error {
	err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		live, err := liveIn(ctx, tx, spaceID)
		if err != nil {
			return err
		}
		return finishShareOut(ctx, tx, live, spaceID, 0, s.now().UnixMilli())
	})
	if err == nil {
		s.committed()
	}
	return err
}

// HasSpace reports whether this file still has a space's row.
func (s *Store) HasSpace(ctx context.Context, spaceID string) (bool, error) {
	var ok bool
	err := s.db.R.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM spaces WHERE id = ?)`, spaceID).Scan(&ok)
	return ok, err
}

// Live reports whether a note is here and not a tombstone.
func (s *Store) Live(ctx context.Context, id string) (bool, error) {
	var ok bool
	err := s.db.R.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM notes WHERE id = ? AND deleted_at IS NULL)`, id).Scan(&ok)
	return ok, err
}

// store writes a carried note at revision rev, over the row at rowid (a
// moved tombstone) or as a new one, returning its change sequence.
func (s *Store) store(ctx context.Context, tx *sql.Tx, c Carried, space string, rev, rowid int64) (int64, error) {
	n := c.Note
	seq, err := nextChangeSeq(ctx, tx)
	if err != nil {
		return 0, err
	}
	if rowid != 0 {
		_, err = tx.ExecContext(ctx, `UPDATE notes SET title = ?, content = ?, revision = ?, change_seq = ?, created_at = ?,
			updated_at = ?, deleted_at = NULL, conflict_of = ?, space_id = ?, updated_by = ?, moved_to = NULL WHERE seq = ?`,
			n.Title, n.Content, rev, seq, n.CreatedAt, n.UpdatedAt, n.ConflictOf, space, n.UpdatedBy, rowid)
	} else {
		var res sql.Result
		res, err = tx.ExecContext(ctx, `INSERT INTO notes (id, title, content, revision, change_seq, created_at, updated_at,
			conflict_of, space_id, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			n.ID, n.Title, n.Content, rev, seq, n.CreatedAt, n.UpdatedAt, n.ConflictOf, space, n.UpdatedBy)
		if err == nil {
			rowid, err = res.LastInsertId()
		}
	}
	if err != nil {
		return 0, err
	}
	if err := search.IndexNote(ctx, tx, rowid, n.Title, n.Content); err != nil {
		return 0, err
	}
	for _, b := range c.Images {
		if err := images.PutTx(ctx, tx, b); err != nil {
			return 0, err
		}
	}
	return seq, images.SetRefs(ctx, tx, rowid, shownImages(n.Content, n.ImageIDs), s.now().UnixMilli())
}

// carry reads what a move takes of a note: its state and its images.
func carry(ctx context.Context, tx *sql.Tx, cur storedNote) (Carried, error) {
	imgs, err := images.Shown(ctx, tx, cur.rowid)
	return Carried{Note: cur.Note, Images: imgs}, err
}

// liveIn loads every live note of a space.
func liveIn(ctx context.Context, tx *sql.Tx, spaceID string) ([]storedNote, error) {
	rows, err := tx.QueryContext(ctx, `SELECT id FROM notes WHERE space_id = ? AND deleted_at IS NULL ORDER BY seq`, spaceID)
	if err != nil {
		return nil, err
	}
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return nil, err
		}
		ids = append(ids, id)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := make([]storedNote, len(ids))
	for i, id := range ids {
		if out[i], err = loadForUpdate(ctx, tx, id); err != nil {
			return nil, err
		}
	}
	return out, nil
}

func finishShareOut(ctx context.Context, tx *sql.Tx, live []storedNote, spaceID string, by, now int64) error {
	for _, cur := range live {
		if err := tombstoneMoved(ctx, tx, cur, spaceID, by, now); err != nil {
			return err
		}
	}
	_, err := tx.ExecContext(ctx, `DELETE FROM spaces WHERE id = ?`, spaceID)
	return err
}

// tombstoneMoved turns a note a move took into a tombstone that says where
// it went, so the devices syncing this file learn it left.
func tombstoneMoved(ctx context.Context, tx *sql.Tx, cur storedNote, dest string, by, now int64) error {
	seq, err := nextChangeSeq(ctx, tx)
	if err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE notes SET title = '', content = '', revision = ?, change_seq = ?,
		updated_at = ?, deleted_at = ?, moved_to = ?, updated_by = ? WHERE seq = ?`,
		cur.Revision+1, seq, max(now, cur.CreatedAt), now, dest, actor(by), cur.rowid); err != nil {
		return err
	}
	if err := search.UnindexNote(ctx, tx, cur.rowid); err != nil {
		return err
	}
	return images.SetRefs(ctx, tx, cur.rowid, nil, now)
}
