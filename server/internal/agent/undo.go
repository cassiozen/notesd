package agent

import (
	"context"
	"database/sql"
	"errors"

	"notes/server/internal/locks"
	"notes/server/internal/notes"
)

// Outcomes of a run after undo.
const (
	OutcomeUndone       = "undone"
	OutcomePartlyUndone = "partly_undone"
)

// ErrNothingToUndo means none of a run's changes could be undone.
var ErrNothingToUndo = errors.New("nothing to undo")

// UndoResult is what an undo did, note by note.
type UndoResult struct {
	Undone  []string   `json:"undone"`
	Skipped []UndoSkip `json:"skipped"`
}

// UndoSkip is a note an undo left as it is, and why: a Blocked constant.
type UndoSkip struct {
	NoteID string `json:"noteId"`
	Reason string `json:"reason"`
}

type undoEdit struct {
	noteID, kind, title, content string
	afterRev                     sql.NullInt64
	undone                       bool
}

// Undo restores (or, if created, deletes) each note a finished run changed and nobody has since,
// one write per note so one changed note doesn't stop the rest. ErrNothingToUndo, with reasons, if none.
// by is the account undoing it, whose edit the writes are.
func (s *Store) Undo(ctx context.Context, ns *notes.Store, runID string, by int64) (UndoResult, error) {
	res := UndoResult{Undone: []string{}, Skipped: []UndoSkip{}}
	var status string
	err := s.db.R.QueryRowContext(ctx, `SELECT status FROM agent_runs WHERE id = ?`, runID).Scan(&status)
	if errors.Is(err, sql.ErrNoRows) {
		return res, ErrRunNotFound
	}
	if err != nil {
		return res, err
	}
	edits, err := s.undoEdits(ctx, runID)
	if err != nil {
		return res, err
	}
	for _, e := range edits {
		reason := ""
		switch {
		case e.undone:
			reason = BlockedUndone
		case status == StatusQueued || status == StatusRunning:
			reason = BlockedRunning
		case !e.afterRev.Valid:
			reason = BlockedNotSaved
		default:
			reason, err = undoOne(ctx, ns, runID, e, by)
			if err != nil {
				return res, err
			}
		}
		if reason != "" {
			res.Skipped = append(res.Skipped, UndoSkip{NoteID: e.noteID, Reason: reason})
			continue
		}
		if err := s.markUndone(ctx, runID, e.noteID); err != nil {
			return res, err
		}
		res.Undone = append(res.Undone, e.noteID)
	}
	if len(res.Undone) == 0 {
		return res, ErrNothingToUndo
	}
	return res, nil
}

func (s *Store) undoEdits(ctx context.Context, runID string) ([]undoEdit, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT note_id, kind, before_title, before_content, after_revision, undone_at IS NOT NULL
		FROM agent_run_edits WHERE run_id = ? ORDER BY written_at, note_id`, runID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []undoEdit
	for rows.Next() {
		var e undoEdit
		if err := rows.Scan(&e.noteID, &e.kind, &e.title, &e.content, &e.afterRev, &e.undone); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

// undoOne bases its write on the revision the run left, so the store refuses a note changed since;
// the fixed operation id makes a retry after a crash replay instead of failing.
func undoOne(ctx context.Context, ns *notes.Store, runID string, e undoEdit, by int64) (reason string, err error) {
	op := "agent-undo:" + runID + ":" + e.noteID
	mark := &notes.AgentMark{RunID: runID, Only: true}
	if e.kind == "create" {
		var res notes.Result
		res, err = ns.Delete(ctx, notes.DeleteInput{OperationID: op, ID: e.noteID, BaseRevision: e.afterRev.Int64, UpdatedBy: by, Agent: mark})
		if err == nil && res.Status == notes.StatusAlreadyDeleted {
			// Someone else deleted it; a replay of this undo's own delete isn't this.
			return BlockedDeleted, nil
		}
	} else {
		_, err = ns.Update(ctx, notes.UpdateInput{OperationID: op, ID: e.noteID, BaseRevision: e.afterRev.Int64,
			Title: e.title, Content: e.content, UpdatedBy: by, Agent: mark})
	}
	var conflict *notes.ConflictError
	switch {
	case errors.As(err, &conflict):
		switch {
		case conflict.Current.DeletedAt != nil:
			return BlockedDeleted, nil
		case locks.IsLocked(conflict.Current.Content):
			return BlockedLocked, nil
		}
		return BlockedChanged, nil
	case errors.Is(err, notes.ErrNotFound):
		return BlockedDeleted, nil
	}
	return "", err
}

// markUndone records one note undone, and what the run's outcome is now.
func (s *Store) markUndone(ctx context.Context, runID, noteID string) error {
	now := s.now().UnixMilli()
	return s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `UPDATE agent_run_edits SET undone_at = ? WHERE run_id = ? AND note_id = ?`,
			now, runID, noteID); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `UPDATE agent_runs SET outcome = CASE
				WHEN (SELECT count(*) FROM agent_run_edits WHERE run_id = ? AND undone_at IS NULL) = 0 THEN ?
				ELSE ? END
			WHERE id = ?`, runID, OutcomeUndone, OutcomePartlyUndone, runID)
		return err
	})
}
