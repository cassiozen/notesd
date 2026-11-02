package agent

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"notes/server/internal/locks"
	"notes/server/internal/notes"
)

// IdleAfter is how long a note goes without the user's changes before its
// writing session ends and a "done" trigger fires.
var IdleAfter = 2 * time.Minute

// MaxQueued bounds an account's queue: beyond it, new runs are recorded as
// skipped, so a backlog can't grow without end.
const MaxQueued = 500

// Run statuses.
const (
	StatusQueued  = "queued"
	StatusRunning = "running"
	StatusDone    = "done"
	StatusFailed  = "failed"
	StatusSkipped = "skipped"
)

// Event is what a run's agent is told happened, stored with the run.
type Event struct {
	Trigger Trigger `json:"trigger"`
	NoteID  string  `json:"noteId"`
	// For a move, the space it came from and the one it went to.
	FromSpaceID string `json:"fromSpaceId,omitempty"`
	ToSpaceID   string `json:"toSpaceId,omitempty"`
	// The note as it was, for a trigger whose note the agent can no longer
	// read: deleted, or moved to another space.
	Title   *string `json:"title,omitempty"`
	Content *string `json:"content,omitempty"`
}

// OnWrite is the notes store's write hook (notes.Store.OnWrite): inside the
// write's transaction it keeps writing sessions up to date and queues the
// triggers a write fires, so neither is lost to a crash.
func (s *Store) OnWrite(ctx context.Context, tx *sql.Tx, ev notes.Event) error {
	if ev.Unchanged || ev.Origin != "" {
		// Nothing changed, or no one is editing: a device's first sync or an import.
		return nil
	}
	if ev.Kind == notes.EventDeleted {
		if _, err := tx.ExecContext(ctx, `DELETE FROM agent_sessions WHERE note_id = ?`, ev.NoteID); err != nil {
			return err
		}
	}
	if ev.Agent != nil && ev.Agent.Only {
		// The agent's own change, or a save holding only that: never a trigger.
		return nil
	}
	if on, err := anyAgent(ctx, tx); err != nil || !on {
		return err
	}
	now := s.now().UnixMilli()

	switch ev.Kind {
	case notes.EventDeleted:
		if locks.IsLocked(ev.Before.Content) {
			return nil
		}
		return s.queueIfActs(ctx, tx, ev.Before.SpaceID, Event{
			Trigger: NoteDeleted, NoteID: ev.NoteID, Title: &ev.Before.Title, Content: &ev.Before.Content,
		}, now)
	case notes.EventUpdated:
		if ev.SpaceChanged() && !locks.IsLocked(ev.After.Content) {
			from, to := ev.Before.SpaceID, ev.After.SpaceID
			if err := s.queueIfActs(ctx, tx, from, Event{
				Trigger: MovedOut, NoteID: ev.NoteID, FromSpaceID: from, ToSpaceID: to,
				Title: &ev.After.Title, Content: &ev.After.Content,
			}, now); err != nil {
				return err
			}
			if err := s.queueIfActs(ctx, tx, to, Event{Trigger: MovedIn, NoteID: ev.NoteID, FromSpaceID: from, ToSpaceID: to}, now); err != nil {
				return err
			}
		}
		if ev.Before.Title == ev.After.Title && ev.Before.Content == ev.After.Content {
			return nil // only moved
		}
		return s.extendSession(ctx, tx, ev, "edit", ev.Before.Revision, now)
	case notes.EventCreated:
		if ev.After.ConflictOf != nil {
			// A conflicted or recovered copy, made by sync: what it holds was
			// already written in a session of its own.
			return nil
		}
		return s.extendSession(ctx, tx, ev, "new", 0, now)
	}
	return nil
}

// anyAgent reports whether any space's agent is on, so accounts without one
// pay a single small query per write.
func anyAgent(ctx context.Context, tx *sql.Tx) (bool, error) {
	var one int
	err := tx.QueryRowContext(ctx, `SELECT 1 FROM agent_spaces WHERE enabled = 1 LIMIT 1`).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}

// extendSession starts a writing session on the note, or extends the one in
// progress. A session starts only where a "done" trigger could fire; the
// space is checked again when it ends, in case the note moved.
func (s *Store) extendSession(ctx context.Context, tx *sql.Tx, ev notes.Event, kind string, startRevision, now int64) error {
	res, err := tx.ExecContext(ctx, `UPDATE agent_sessions SET last_revision = ?, last_user_save_at = ? WHERE note_id = ?`,
		ev.After.Revision, now, ev.NoteID)
	if err != nil {
		return err
	}
	if n, err := res.RowsAffected(); err != nil || n > 0 {
		return err
	}
	if locks.IsLocked(ev.After.Content) {
		return nil
	}
	c, err := configIn(ctx, tx, ev.After.SpaceID)
	if err != nil {
		return ignoreGoneSpace(err)
	}
	if !c.Acts(doneTrigger(kind)) {
		return nil
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO agent_sessions (note_id, kind, start_revision, last_revision, last_user_save_at)
		VALUES (?, ?, ?, ?, ?)`, ev.NoteID, kind, startRevision, ev.After.Revision, now)
	return err
}

func doneTrigger(kind string) Trigger {
	if kind == "new" {
		return NewNoteDone
	}
	return EditDone
}

func ignoreGoneSpace(err error) error {
	if errors.Is(err, ErrSpaceNotFound) {
		return nil
	}
	return err
}

// queueIfActs queues a run of the space's agent for e, if it acts on e's trigger.
func (s *Store) queueIfActs(ctx context.Context, tx *sql.Tx, spaceID string, e Event, now int64) error {
	c, err := configIn(ctx, tx, spaceID)
	if err != nil {
		return ignoreGoneSpace(err)
	}
	if !c.Acts(e.Trigger) {
		return nil
	}
	_, err = queue(ctx, tx, spaceID, e, 0, now)
	return err
}

// queue adds a run. Beyond MaxQueued it's recorded as skipped instead.
func queue(ctx context.Context, tx *sql.Tx, spaceID string, e Event, priority int, now int64) (string, error) {
	body, err := json.Marshal(e)
	if err != nil {
		return "", err
	}
	var queued int
	if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM agent_runs WHERE status = 'queued'`).Scan(&queued); err != nil {
		return "", err
	}
	status, failure := StatusQueued, ""
	var finished any
	if queued >= MaxQueued {
		status, failure, finished = StatusSkipped, FailQueueFull, now
	}
	id := newRunID(now)
	_, err = tx.ExecContext(ctx, `INSERT INTO agent_runs (id, status, trigger, space_id, note_id, priority, event_json, created_at, finished_at, failure)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		id, status, string(e.Trigger), spaceID, e.NoteID, priority, string(body), now, finished, failure)
	return id, err
}

// newRunID sorts by time, which makes the log easy to read.
func newRunID(now int64) string {
	var b [8]byte
	_, _ = rand.Read(b[:])
	return fmt.Sprintf("run_%012x%s", now, hex.EncodeToString(b[:]))
}

// CloseIdleSessions ends the writing sessions that have gone IdleAfter
// without a change, queuing each one's "done" trigger. It returns how many
// runs it queued.
func (s *Store) CloseIdleSessions(ctx context.Context) (int, error) {
	now := s.now().UnixMilli()
	cutoff := now - IdleAfter.Milliseconds()
	var ids []string
	rows, err := s.db.R.QueryContext(ctx, `SELECT note_id FROM agent_sessions WHERE last_user_save_at <= ?`, cutoff)
	if err != nil {
		return 0, err
	}
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return 0, err
		}
		ids = append(ids, id)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}
	queued := 0
	for _, id := range ids {
		err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
			ok, err := s.closeSession(ctx, tx, id, cutoff, now)
			if ok {
				queued++
			}
			return err
		})
		if err != nil {
			return queued, err
		}
	}
	return queued, nil
}

// closeSession ends one session if it's still idle, and queues its trigger
// if the note was changed and its space's agent still acts on it.
func (s *Store) closeSession(ctx context.Context, tx *sql.Tx, noteID string, cutoff, now int64) (bool, error) {
	var kind string
	var start, last int64
	err := tx.QueryRowContext(ctx, `DELETE FROM agent_sessions WHERE note_id = ? AND last_user_save_at <= ?
		RETURNING kind, start_revision, last_revision`, noteID, cutoff).Scan(&kind, &start, &last)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil // changed again since, or closed elsewhere
	}
	if err != nil {
		return false, err
	}
	if last == start {
		return false, nil
	}
	var spaceID, content string
	var deleted sql.NullInt64
	err = tx.QueryRowContext(ctx, `SELECT space_id, content, deleted_at FROM notes WHERE id = ?`, noteID).Scan(&spaceID, &content, &deleted)
	if errors.Is(err, sql.ErrNoRows) || deleted.Valid || locks.IsLocked(content) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	c, err := configIn(ctx, tx, spaceID)
	if err != nil {
		return false, ignoreGoneSpace(err)
	}
	trigger := doneTrigger(kind)
	if !c.Acts(trigger) {
		return false, nil
	}
	_, err = queue(ctx, tx, spaceID, Event{Trigger: trigger, NoteID: noteID}, 0, now)
	return err == nil, err
}
