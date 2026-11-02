package agent

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"

	"notes/server/internal/notes"
)

// Keep is how long finished runs stay in the activity log.
const Keep = 30 * 24 * time.Hour

// Outcomes of a run that finished.
const (
	OutcomeEdited   = "edited"
	OutcomeCreated  = "created"
	OutcomeNoChange = "no_change"
)

// Why a run failed or was skipped.
const (
	FailAgentOff     = "agent_off"     // turned off, or the trigger's prompt removed, since it was queued
	FailNoteGone     = "note_gone"     // deleted before the run started
	FailNoteMoved    = "note_moved"    // moved to another space before the run started
	FailNoteLocked   = "note_locked"   // locked notes are never sent
	FailDailyCap     = "daily_cap"     // the account used its tokens for the day
	FailTokenCap     = "token_cap"     // the run used more tokens than one run may
	FailToolCap      = "tool_cap"      // the run made more tool calls than one run may
	FailTimeout      = "timeout"       // the run took longer than one run may
	FailProvider     = "provider"      // the provider refused or failed
	FailNoteChanged  = "note_changed"  // the note kept changing under the run
	FailInterrupted  = "interrupted"   // the server stopped during the run
	FailNotSetUp     = "ai_not_set_up" // AI was removed
	FailQueueFull    = "queue_full"
	FailUnknownEvent = "unknown_trigger"
)

// queuedRun is a run as the runner takes it from the queue.
type queuedRun struct {
	ID       string
	Trigger  Trigger
	SpaceID  string
	NoteID   string
	Event    Event
	Attempts int
}

// claim takes the next run that's due, editor triggers first, and marks it
// running. ok is false when none is due.
func (s *Store) claim(ctx context.Context) (r queuedRun, ok bool, err error) {
	now := s.now().UnixMilli()
	var ev string
	err = s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		return tx.QueryRowContext(ctx, `
			UPDATE agent_runs SET status = 'running', started_at = ?, attempts = attempts + 1
			WHERE id = (SELECT id FROM agent_runs WHERE status = 'queued' AND not_before <= ?
			            ORDER BY priority DESC, created_at, rowid LIMIT 1)
			RETURNING id, trigger, space_id, note_id, event_json, attempts`, now, now).
			Scan(&r.ID, &r.Trigger, &r.SpaceID, &r.NoteID, &ev, &r.Attempts)
	})
	if errors.Is(err, sql.ErrNoRows) {
		return r, false, nil
	}
	if err != nil {
		return r, false, err
	}
	if err := json.Unmarshal([]byte(ev), &r.Event); err != nil {
		return r, true, err
	}
	return r, true, nil
}

// nextDue is when the earliest queued run waiting for a retry is due, or
// zero if none is.
func (s *Store) nextDue(ctx context.Context) (time.Time, error) {
	var at sql.NullInt64
	err := s.db.R.QueryRowContext(ctx, `SELECT min(not_before) FROM agent_runs WHERE status = 'queued' AND not_before > ?`,
		s.now().UnixMilli()).Scan(&at)
	if err != nil || !at.Valid {
		return time.Time{}, err
	}
	return time.UnixMilli(at.Int64), nil
}

// recoverRuns puts back in the queue the runs a stopped server left
// running, once; a run interrupted twice fails.
func (s *Store) recoverRuns(ctx context.Context) error {
	now := s.now().UnixMilli()
	return s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `UPDATE agent_runs SET status = 'failed', failure = ?, finished_at = ?
			WHERE status = 'running' AND (attempts >= 2 OR EXISTS (SELECT 1 FROM agent_run_edits e WHERE e.run_id = agent_runs.id))`,
			FailInterrupted, now); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `UPDATE agent_runs SET status = 'queued', started_at = NULL WHERE status = 'running'`)
		return err
	})
}

// finished is how a run ended.
type finished struct {
	Status           string
	Failure          string
	Outcome          string
	Message          string
	Model            string
	ToolCalls        int
	PromptTokens     int64
	CompletionTokens int64
	// RetryAt, when set, puts the run back in the queue until then.
	RetryAt time.Time
}

// finish records how a run ended, and adds its tokens to the day's usage.
func (s *Store) finish(ctx context.Context, id string, f finished, day string) error {
	now := s.now().UnixMilli()
	if len(f.Message) > 4<<10 {
		f.Message = f.Message[:4<<10]
	}
	return s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		if !f.RetryAt.IsZero() {
			_, err := tx.ExecContext(ctx, `UPDATE agent_runs SET status = 'queued', started_at = NULL, not_before = ?, message = ?
				WHERE id = ?`, f.RetryAt.UnixMilli(), f.Message, id)
			if err != nil {
				return err
			}
		} else if _, err := tx.ExecContext(ctx, `UPDATE agent_runs SET status = ?, failure = ?, outcome = ?, message = ?, model = ?,
				tool_calls = tool_calls + ?, prompt_tokens = prompt_tokens + ?, completion_tokens = completion_tokens + ?, finished_at = ?
			WHERE id = ?`, f.Status, f.Failure, f.Outcome, f.Message, f.Model, f.ToolCalls, f.PromptTokens, f.CompletionTokens, now, id); err != nil {
			return err
		}
		if f.PromptTokens+f.CompletionTokens == 0 {
			return nil
		}
		_, err := tx.ExecContext(ctx, `INSERT INTO agent_usage (day, prompt_tokens, completion_tokens, runs) VALUES (?, ?, ?, 1)
			ON CONFLICT(day) DO UPDATE SET prompt_tokens = prompt_tokens + excluded.prompt_tokens,
				completion_tokens = completion_tokens + excluded.completion_tokens, runs = runs + 1`,
			day, f.PromptTokens, f.CompletionTokens)
		return err
	})
}

// usedOn is the tokens the account used on day.
func (s *Store) usedOn(ctx context.Context, day string) (int64, error) {
	var n int64
	err := s.db.R.QueryRowContext(ctx, `SELECT prompt_tokens + completion_tokens FROM agent_usage WHERE day = ?`, day).Scan(&n)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, nil
	}
	return n, err
}

// recordEdit notes that a run changed (or created) a note: before is kept
// from its first change in the run, after replaced by each.
func (s *Store) recordEdit(ctx context.Context, runID string, before *notes.Note, after notes.Note) error {
	now := s.now().UnixMilli()
	kind := "edit"
	var beforeRev any
	var beforeTitle, beforeContent string
	if before == nil {
		kind = "create"
	} else {
		beforeRev, beforeTitle, beforeContent = before.Revision, before.Title, before.Content
	}
	return s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `
			INSERT INTO agent_run_edits (run_id, note_id, kind, before_revision, before_title, before_content,
			                             after_revision, after_title, after_content, written_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT(run_id, note_id) DO UPDATE SET after_revision = excluded.after_revision,
				after_title = excluded.after_title, after_content = excluded.after_content, written_at = excluded.written_at`,
			runID, after.ID, kind, beforeRev, beforeTitle, beforeContent, after.Revision, after.Title, after.Content, now)
		return err
	})
}

// edited reports what a run changed: notes it edited, and notes it created.
func (s *Store) edited(ctx context.Context, runID string) (edits, creates int, err error) {
	err = s.db.R.QueryRowContext(ctx, `SELECT count(*) FILTER (WHERE kind = 'edit'), count(*) FILTER (WHERE kind = 'create')
		FROM agent_run_edits WHERE run_id = ?`, runID).Scan(&edits, &creates)
	return edits, creates, err
}

// Purge deletes runs finished more than Keep ago, with what they recorded,
// and usage older than twice that.
func (s *Store) Purge(ctx context.Context) error {
	now := s.now()
	return s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `DELETE FROM agent_runs WHERE finished_at IS NOT NULL AND finished_at < ?`,
			now.Add(-Keep).UnixMilli()); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `DELETE FROM agent_usage WHERE day < ?`, now.Add(-2*Keep).UTC().Format(time.DateOnly))
		return err
	})
}
