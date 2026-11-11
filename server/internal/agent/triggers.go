package agent

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math"
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
	// ConnectionID is the editor that asked for the run, for an editor trigger.
	ConnectionID string `json:"connectionId,omitempty"`
	// By is the account the run is for: the one whose editor asked, or whose write
	// fired it. Its writes are that account's, in its time zone. 0: the file's account.
	By int64 `json:"by,omitempty"`
}

// OnScheduled has fn called when a run is queued or a writing session
// starts: the account's runner has something new to wait for. It's called
// inside the write's transaction, which is safe as a wake-up: the database
// has one writer, so the runner can't claim before the write commits, and
// one rolled back only wakes it for nothing. fn must not block.
func (s *Store) OnScheduled(fn func()) { s.onScheduled = fn }

func (s *Store) scheduled() {
	if s.onScheduled != nil {
		s.onScheduled()
	}
}

// NoteCommitted is the notes store's hook after a write commits (notes.Store.OnCommitted): any write,
// whoever made it, as what a run done ahead read may be what changed. After the commit, so a run
// reading the note once it's told reads the new one.
func (s *Store) NoteCommitted(ev notes.Event) { s.changed(Change{Note: &ev}) }

// OnWrite is the notes store's write hook (notes.Store.OnWrite): inside the
// write's transaction it keeps writing sessions up to date and queues the
// triggers a write fires, so neither is lost to a crash.
func (s *Store) OnWrite(ctx context.Context, tx *sql.Tx, ev notes.Event) error {
	if err := removeText(ctx, tx, ev, s.now().UnixMilli()); err != nil {
		return err
	}
	if ev.Unchanged || ev.Origin != "" {
		// Nothing changed, or no one is editing: a device's first sync or an import.
		return nil
	}
	if ev.Kind == notes.EventDeleted || ev.Left {
		// Gone from this file: a session here would end on a note it can't read.
		if _, err := tx.ExecContext(ctx, `DELETE FROM agent_sessions WHERE note_id = ?`, ev.NoteID); err != nil {
			return err
		}
	}
	if ev.Agent != nil && ev.Agent.Runner && ev.Kind != notes.EventDeleted {
		// With the write, so a crash can't keep the change and lose its record.
		if err := recordEdit(ctx, tx, ev, s.now().UnixMilli()); err != nil {
			return err
		}
	}
	if (ev.Agent == nil || !ev.Agent.Runner) && ev.Kind != notes.EventDeleted {
		agentOnly, err := linkEditorEdit(ctx, tx, ev)
		if err != nil {
			return err
		}
		if agentOnly && ev.Kind == notes.EventCreated {
			// A new note the agent wrote in before the user did: its session
			// starts at the agent's revision, so only the user's writing ends it with a trigger.
			if on, err := anyAgent(ctx, tx); err != nil || !on {
				return err
			}
			return s.extendSession(ctx, tx, ev, "new", ev.After.Revision, s.now().UnixMilli())
		}
		if agentOnly && !ev.SpaceChanged() {
			return nil // an editor saving just a run's change: not the user writing
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
			Trigger: NoteDeleted, NoteID: ev.NoteID, Title: &ev.Before.Title, Content: &ev.Before.Content, By: ev.By,
		}, now)
	case notes.EventUpdated:
		if ev.SpaceChanged() && !locks.IsLocked(ev.After.Content) {
			from, to := ev.Before.SpaceID, ev.After.SpaceID
			if err := s.queueIfActs(ctx, tx, from, Event{
				Trigger: MovedOut, NoteID: ev.NoteID, FromSpaceID: from, ToSpaceID: to,
				Title: &ev.After.Title, Content: &ev.After.Content, By: ev.By,
			}, now); err != nil {
				return err
			}
			if err := s.queueIfActs(ctx, tx, to, Event{Trigger: MovedIn, NoteID: ev.NoteID, FromSpaceID: from, ToSpaceID: to, By: ev.By}, now); err != nil {
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

// removeText clears the agents' copies of a note's text when the note is
// locked (its body) or deleted (title and body), whoever made the change:
// Activity must not keep what the note itself no longer shows.
func removeText(ctx context.Context, tx *sql.Tx, ev notes.Event, now int64) error {
	if ev.Unchanged {
		return nil
	}
	switch {
	case ev.Kind == notes.EventDeleted:
		if err := clearOverlays(ctx, tx, ev.NoteID); err != nil {
			return err
		}
		// applied_from too: a hash of short text can be guessed.
		if _, err := tx.ExecContext(ctx, `UPDATE agent_run_edits SET before_title = '', after_title = '',
			before_content = '', after_content = '', applied_from = NULL, text_removed = 1 WHERE note_id = ?`, ev.NoteID); err != nil {
			return err
		}
		// A queued run keeps its snapshot: a "note deleted" run needs it, and drops it when it finishes.
		_, err := tx.ExecContext(ctx, `UPDATE agent_runs SET event_json = json_remove(event_json, '$.title', '$.content')
			WHERE note_id = ? AND status NOT IN ('queued', 'running')`, ev.NoteID)
		return err
	case ev.Kind == notes.EventUpdated && locks.IsLocked(ev.After.Content) && !locks.IsLocked(ev.Before.Content):
		if err := clearOverlays(ctx, tx, ev.NoteID); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE agent_run_edits SET before_content = '', after_content = '',
			applied_from = NULL, text_removed = 1 WHERE note_id = ?`, ev.NoteID); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE agent_runs SET status = ?, failure = ?, finished_at = ?,
			message = 'The note was locked before the agent got to it. Locked notes are never sent.'
			WHERE note_id = ? AND status = 'queued'`, StatusSkipped, FailNoteLocked, now, ev.NoteID); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `UPDATE agent_runs SET event_json = json_remove(event_json, '$.content') WHERE note_id = ?`, ev.NoteID)
		return err
	}
	return nil
}

// clearOverlays ends what shows on a note and forgets its text, which
// quotes the note; Activity keeps only that the run showed them.
func clearOverlays(ctx context.Context, tx *sql.Tx, noteID string) error {
	_, err := tx.ExecContext(ctx, `UPDATE agent_overlays SET place = '{}', body = '', change = NULL,
		status = CASE status WHEN 'open' THEN 'closed' ELSE status END WHERE note_id = ?`, noteID)
	return err
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
	// Its "done" run is for whoever wrote last.
	res, err := tx.ExecContext(ctx, `UPDATE agent_sessions SET last_revision = ?, last_user_save_at = ?, by_user = ? WHERE note_id = ?`,
		ev.After.Revision, now, ev.By, ev.NoteID)
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
	if _, err = tx.ExecContext(ctx, `INSERT INTO agent_sessions (note_id, kind, start_revision, last_revision, last_user_save_at, by_user)
		VALUES (?, ?, ?, ?, ?, ?)`, ev.NoteID, kind, startRevision, ev.After.Revision, now, ev.By); err != nil {
		return err
	}
	s.scheduled()
	return nil
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
	if _, err = queue(ctx, tx, spaceID, e, 0, now); err != nil {
		return err
	}
	s.scheduled()
	return nil
}

// queue adds a run. Beyond MaxQueued it's recorded as skipped instead.
func queue(ctx context.Context, tx *sql.Tx, spaceID string, e Event, priority int, now int64) (string, error) {
	var queued int
	if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM agent_runs WHERE status = 'queued'`).Scan(&queued); err != nil {
		return "", err
	}
	status, failure := StatusQueued, ""
	var finished any
	if queued >= MaxQueued {
		status, failure, finished = StatusSkipped, FailQueueFull, now
		// Never run, so nothing needs the note's text.
		e.Title, e.Content = nil, nil
	}
	body, err := json.Marshal(e)
	if err != nil {
		return "", err
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
// without a change, queuing each one's "done" trigger. The account's runner
// calls it as each one comes due. It returns how many runs it queued.
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

// EditorClosed ends the note's writing session now, when an editor closed it
// and the server has what it last saved (revision), rather than waiting
// IdleAfter.
func (s *Store) EditorClosed(ctx context.Context, noteID string, revision int64) error {
	now := s.now().UnixMilli()
	return s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		// The note's own revision, not the session's: writes that aren't the
		// user's (the agent's, a move) leave the session's behind.
		var current int64
		err := tx.QueryRowContext(ctx, `SELECT n.revision FROM agent_sessions s JOIN notes n ON n.id = s.note_id
			WHERE s.note_id = ?`, noteID).Scan(&current)
		if errors.Is(err, sql.ErrNoRows) || (err == nil && current < revision) {
			// No session, or a save still on its way: the idle timeout ends it.
			return nil
		}
		if err != nil {
			return err
		}
		_, err = s.closeSession(ctx, tx, noteID, math.MaxInt64, now)
		return err
	})
}

// closeSession ends one session if it's still idle, and queues its trigger
// if the note was changed and its space's agent still acts on it.
func (s *Store) closeSession(ctx context.Context, tx *sql.Tx, noteID string, cutoff, now int64) (bool, error) {
	var kind string
	var start, last, by int64
	err := tx.QueryRowContext(ctx, `DELETE FROM agent_sessions WHERE note_id = ? AND last_user_save_at <= ?
		RETURNING kind, start_revision, last_revision, by_user`, noteID, cutoff).Scan(&kind, &start, &last, &by)
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
	if _, err = queue(ctx, tx, spaceID, Event{Trigger: trigger, NoteID: noteID, By: by}, 0, now); err != nil {
		return false, err
	}
	s.scheduled()
	return true, nil
}
