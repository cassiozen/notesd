package agent

import (
	"context"
	"database/sql"
	"errors"

	"notes/server/internal/locks"
)

// Why an editor's request for a run was refused.
var (
	ErrAgentOff   = errors.New("the space's agent is off")
	ErrTriggerOff = errors.New("the space's agent doesn't act on this")
	ErrNoteLocked = errors.New("the note is locked")
	ErrNoteGone   = errors.New("no such note")
)

// RunRequest is an editor asking for a run: a new note opened, a note
// started being edited, or the menu command.
type RunRequest struct {
	Trigger Trigger
	NoteID  string
	// SpaceID is a new note's space, needed only while the store hasn't
	// got the note.
	SpaceID      string
	ConnectionID string
}

// RequestRun queues a run an editor asked for, ahead of the others. Asked
// again before it starts, it's the same run.
func (s *Store) RequestRun(ctx context.Context, req RunRequest) (string, error) {
	switch req.Trigger {
	case NoteCreated, EditStarted, Menu:
	default:
		return "", &InputError{Field: "trigger", Message: "must be noteCreated, editStarted or menu"}
	}
	if req.NoteID == "" || len(req.NoteID) > 64 {
		return "", &InputError{Field: "noteId", Message: "required"}
	}
	var id string
	err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		space, err := requestSpace(ctx, tx, req)
		if err != nil {
			return err
		}
		c, err := configIn(ctx, tx, space)
		switch {
		case err != nil:
			return err
		case !c.Enabled:
			return ErrAgentOff
		case !c.Acts(req.Trigger):
			return ErrTriggerOff
		}
		err = tx.QueryRowContext(ctx, `SELECT id FROM agent_runs WHERE note_id = ? AND trigger = ? AND status = 'queued'`,
			req.NoteID, string(req.Trigger)).Scan(&id)
		if !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if id, err = queue(ctx, tx, space, Event{Trigger: req.Trigger, NoteID: req.NoteID, ConnectionID: req.ConnectionID},
			editorPriority, s.now().UnixMilli()); err != nil {
			return err
		}
		s.scheduled()
		return nil
	})
	return id, err
}

// requestSpace is the space the note is in, or, for a new note the store
// hasn't got yet, the one the editor named.
func requestSpace(ctx context.Context, tx *sql.Tx, req RunRequest) (string, error) {
	var space, content string
	var deleted sql.NullInt64
	err := tx.QueryRowContext(ctx, `SELECT space_id, content, deleted_at FROM notes WHERE id = ?`, req.NoteID).
		Scan(&space, &content, &deleted)
	switch {
	case errors.Is(err, sql.ErrNoRows) && req.Trigger == NoteCreated && req.SpaceID != "":
		return req.SpaceID, nil
	case errors.Is(err, sql.ErrNoRows) || deleted.Valid:
		return "", ErrNoteGone
	case err != nil:
		return "", err
	case locks.IsLocked(content):
		return "", ErrNoteLocked
	}
	return space, nil
}
