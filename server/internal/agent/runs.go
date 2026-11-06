package agent

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"time"
	"unicode/utf8"

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
	FailAgentOff     = "agent_off"   // turned off, or the trigger's prompt removed, since it was queued
	FailNoteGone     = "note_gone"   // deleted before the run started
	FailNoteMoved    = "note_moved"  // moved to another space before the run started
	FailNoteLocked   = "note_locked" // locked notes are never sent
	FailDailyCap     = "daily_cap"   // the account used its tokens for the day
	FailTokenCap     = "token_cap"   // the run used more tokens than one run may
	FailToolCap      = "tool_cap"    // the run made more tool calls than one run may
	FailTimeout      = "timeout"     // the run took longer than one run may
	FailProvider     = "provider"    // the provider refused or failed
	FailInterrupted  = "interrupted" // the server stopped during the run
	FailQueueFull    = "queue_full"
	FailUnknownEvent = "unknown_trigger"
	FailEditorGone   = "editor_disconnected" // the editor went away while the run changed the note in it
)

// queuedRun is a run as the runner takes it from the queue.
type queuedRun struct {
	ID        string
	CreatedAt int64
	Trigger   Trigger
	SpaceID   string
	NoteID    string
	Event     Event
	Attempts  int
	// What earlier attempts used, counted against this one's caps.
	PriorToolCalls int
	PriorTokens    int64
}

// editorPriority is the queue priority of runs an open editor asked for.
const editorPriority = 1

// claim takes the next run that's due, editor triggers first, and marks it
// running; editorOnly, only one an editor asked for. A note's run waits
// while another on it runs. ok is false when none is due.
func (s *Store) claim(ctx context.Context, editorOnly bool) (r queuedRun, ok bool, err error) {
	now := s.now().UnixMilli()
	minPriority := 0
	if editorOnly {
		minPriority = editorPriority
	}
	var ev string
	err = s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		return tx.QueryRowContext(ctx, `
			UPDATE agent_runs SET status = 'running', started_at = ?, attempts = attempts + 1
			WHERE id = (SELECT id FROM agent_runs WHERE status = 'queued' AND not_before <= ? AND priority >= ?
			              AND NOT EXISTS (SELECT 1 FROM agent_runs b WHERE b.status = 'running' AND b.note_id = agent_runs.note_id)
			            ORDER BY priority DESC, created_at, rowid LIMIT 1)
			RETURNING id, created_at, trigger, space_id, note_id, event_json, attempts, tool_calls, prompt_tokens + completion_tokens`, now, now, minPriority).
			Scan(&r.ID, &r.CreatedAt, &r.Trigger, &r.SpaceID, &r.NoteID, &ev, &r.Attempts, &r.PriorToolCalls, &r.PriorTokens)
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

// nextDue is when the runner next has something to do unless woken: the
// earliest queued run or writing session's end. Zero if there's neither.
func (s *Store) nextDue(ctx context.Context) (time.Time, error) {
	var at sql.NullInt64
	err := s.db.R.QueryRowContext(ctx, `SELECT min(t) FROM (
		SELECT min(not_before) AS t FROM agent_runs WHERE status = 'queued'
		UNION ALL SELECT min(last_user_save_at) + ? FROM agent_sessions)`, IdleAfter.Milliseconds()).Scan(&at)
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
		if _, err := tx.ExecContext(ctx, `UPDATE agent_runs SET status = 'failed', failure = ?, finished_at = ?, event_json = `+doneEvent+`
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
	// sent is whether this attempt has called the model.
	sent   bool
	timing timing
}

// timing is where a run's time went, for the log: getting ready (prep),
// the model's calls, and the tools'.
type timing struct {
	prep, model, tools time.Duration
	calls              int
}

// maxMessage bounds a run's stored message.
const maxMessage = 4 << 10

// doneEvent is a finished run's event_json: the note's body dropped once the
// run no longer needs it, as the note may be locked or deleted later; a
// deleted note's title too, as deleting clears it everywhere else.
const doneEvent = `CASE WHEN trigger = 'noteDeleted'
		OR EXISTS (SELECT 1 FROM notes n WHERE n.id = agent_runs.note_id AND n.deleted_at IS NOT NULL)
	THEN json_remove(event_json, '$.title', '$.content') ELSE json_remove(event_json, '$.content') END`

// finish records how a run ended, or puts it back in the queue for a retry.
// Its tokens were counted as each call came back (addUsage).
func (s *Store) finish(ctx context.Context, id string, f finished) error {
	now := s.now().UnixMilli()
	f.Message = truncate(f.Message, maxMessage)
	return s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		if !f.RetryAt.IsZero() {
			_, err := tx.ExecContext(ctx, `UPDATE agent_runs SET status = 'queued', started_at = NULL, not_before = ?, message = ?,
				tool_calls = tool_calls + ? WHERE id = ?`, f.RetryAt.UnixMilli(), f.Message, f.ToolCalls, id)
			return err
		}
		_, err := tx.ExecContext(ctx, `UPDATE agent_runs SET status = ?, failure = ?, outcome = ?, message = ?,
				tool_calls = tool_calls + ?, finished_at = ?, event_json = `+doneEvent+` WHERE id = ?`,
			f.Status, f.Failure, f.Outcome, f.Message, f.ToolCalls, now, id)
		return err
	})
}

// addUsage counts one model call's tokens, for the run and the day, as soon
// as it comes back, so a crash mid-run can't spend tokens the cap never saw.
func (s *Store) addUsage(ctx context.Context, id, day, model string, prompt, completion int64) error {
	if prompt+completion == 0 {
		return nil
	}
	return s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		var total int64
		if err := tx.QueryRowContext(ctx, `UPDATE agent_runs SET model = ?, prompt_tokens = prompt_tokens + ?,
				completion_tokens = completion_tokens + ? WHERE id = ? RETURNING prompt_tokens + completion_tokens`,
			model, prompt, completion, id).Scan(&total); err != nil {
			return err
		}
		// The run's first tokens count it as one of the day's runs.
		first := 0
		if total == prompt+completion {
			first = 1
		}
		_, err := tx.ExecContext(ctx, `INSERT INTO agent_usage (day, prompt_tokens, completion_tokens, runs) VALUES (?, ?, ?, ?)
			ON CONFLICT(day) DO UPDATE SET prompt_tokens = prompt_tokens + excluded.prompt_tokens,
				completion_tokens = completion_tokens + excluded.completion_tokens, runs = runs + excluded.runs`,
			day, prompt, completion, first)
		return err
	})
}

// truncate cuts s to at most n bytes, on a character boundary.
func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	for n > 0 && !utf8.RuneStart(s[n]) {
		n--
	}
	return s[:n]
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

// recordEdit notes that a run changed (or created) a note, inside the
// write's transaction: before is kept from its first change in the run,
// after replaced by each.
func recordEdit(ctx context.Context, tx *sql.Tx, ev notes.Event, now int64) error {
	kind := "edit"
	var beforeRev any
	var beforeTitle, beforeContent string
	if ev.Kind == notes.EventCreated {
		kind = "create"
	} else {
		beforeRev, beforeTitle, beforeContent = ev.Before.Revision, ev.Before.Title, ev.Before.Content
	}
	_, err := tx.ExecContext(ctx, `
		INSERT INTO agent_run_edits (run_id, note_id, kind, before_revision, before_title, before_content,
		                             after_revision, after_title, after_content, written_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(run_id, note_id) DO UPDATE SET after_revision = excluded.after_revision,
			after_title = excluded.after_title, after_content = excluded.after_content, written_at = excluded.written_at`,
		ev.Agent.RunID, ev.NoteID, kind, beforeRev, beforeTitle, beforeContent, ev.After.Revision, ev.After.Title, ev.After.Content, now)
	return err
}

// recordEditorEdit notes a change a run made in an editor, from before to
// after. The editor saves it later: linkEditorEdit fills in after_revision
// then. beforeRev is nil when the editor had unsaved changes.
func (s *Store) recordEditorEdit(ctx context.Context, runID, noteID string, beforeRev *int64, before, after Fields) error {
	return s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `
			INSERT INTO agent_run_edits (run_id, note_id, kind, before_revision, before_title, before_content,
			                             after_revision, after_title, after_content, written_at, applied_from)
			VALUES (?, ?, 'edit', ?, ?, ?, NULL, ?, ?, ?, ?)
			ON CONFLICT(run_id, note_id) DO UPDATE SET after_revision = NULL, after_title = excluded.after_title,
				after_content = excluded.after_content, written_at = excluded.written_at,
				-- Changes the editor hasn't saved yet go out in one save: from the first of them.
				applied_from = CASE WHEN agent_run_edits.after_revision IS NULL THEN agent_run_edits.applied_from
					ELSE excluded.applied_from END`,
			runID, noteID, beforeRev, before.Title, before.Content, after.Title, after.Content, s.now().UnixMilli(),
			fieldsHash(before.Title, before.Content))
		return err
	})
}

// fieldsHash stands for a note's text where keeping the text itself would
// outlive locking or deleting the note.
func fieldsHash(title, content string) string {
	h := sha256.New()
	h.Write([]byte(title))
	h.Write([]byte{0})
	h.Write([]byte(content))
	return hex.EncodeToString(h.Sum(nil))
}

// linkEditorEdit matches a save to a run's change made in an editor that
// hadn't been saved: the same title and content. A save holding more than
// that isn't linked, so undo never takes the user's typing. agentOnly: the
// server had exactly what the editor showed before the change, so the save
// adds nothing of the user's.
func linkEditorEdit(ctx context.Context, tx *sql.Tx, ev notes.Event) (agentOnly bool, err error) {
	rows, err := tx.QueryContext(ctx, `UPDATE agent_run_edits SET after_revision = ?
		WHERE note_id = ? AND after_revision IS NULL AND after_title = ? AND after_content = ?
		RETURNING applied_from`,
		ev.After.Revision, ev.NoteID, ev.After.Title, ev.After.Content)
	if err != nil {
		return false, err
	}
	defer rows.Close()
	// Hashed only for a save that holds a run's change, which few do.
	prior := ""
	for rows.Next() {
		var from sql.NullString
		if err := rows.Scan(&from); err != nil {
			return false, err
		}
		if prior == "" {
			prior = fieldsHash("", "")
			if ev.Before != nil {
				prior = fieldsHash(ev.Before.Title, ev.Before.Content)
			}
		}
		agentOnly = agentOnly || from.String == prior
	}
	return agentOnly, rows.Err()
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
