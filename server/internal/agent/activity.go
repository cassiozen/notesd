package agent

import (
	"context"
	"database/sql"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
	"time"

	"notes/server/internal/locks"
)

// ErrRunNotFound means there's no run with that id.
var ErrRunNotFound = errors.New("run not found")

// Why an edit can't be undone.
const (
	BlockedUndone  = "undone"  // it already was
	BlockedRunning = "running" // the run hasn't finished
	BlockedDeleted = "deleted" // the note was deleted since
	BlockedLocked  = "locked"  // the note was locked since
	BlockedChanged = "changed" // the note changed since the run wrote it
)

// Run is a run as the activity log shows it.
type Run struct {
	ID      string  `json:"id"`
	Trigger Trigger `json:"trigger"`
	Status  string  `json:"status"`
	SpaceID string  `json:"spaceId"`
	NoteID  string  `json:"noteId"`
	// NoteTitle is the note's title now, or as the event recorded it for a
	// note since deleted; NoteDeleted says it's gone.
	NoteTitle        string    `json:"noteTitle"`
	NoteDeleted      bool      `json:"noteDeleted"`
	Outcome          string    `json:"outcome"`
	Failure          string    `json:"failure"`
	Message          string    `json:"message"`
	Model            string    `json:"model"`
	ToolCalls        int       `json:"toolCalls"`
	PromptTokens     int64     `json:"promptTokens"`
	CompletionTokens int64     `json:"completionTokens"`
	CreatedAt        int64     `json:"createdAt"`
	StartedAt        *int64    `json:"startedAt"`
	FinishedAt       *int64    `json:"finishedAt"`
	Edits            []RunEdit `json:"edits"`
	rowid            int64
}

// RunEdit is a note a run changed or created.
type RunEdit struct {
	NoteID    string `json:"noteId"`
	Kind      string `json:"kind"` // edit or create
	Title     string `json:"title"`
	WrittenAt int64  `json:"writtenAt"`
	UndoneAt  *int64 `json:"undoneAt"`
	Undoable  bool   `json:"undoable"`
	// Blocked says why it can't be undone: one of the Blocked constants.
	Blocked string `json:"blocked,omitempty"`
}

// NoteDiff is what a run did to one note.
type NoteDiff struct {
	NoteID      string     `json:"noteId"`
	Kind        string     `json:"kind"`
	TitleBefore string     `json:"titleBefore"`
	TitleAfter  string     `json:"titleAfter"`
	Lines       []DiffLine `json:"lines"`
}

// RunDetail is a run with what it changed, line by line.
type RunDetail struct {
	Run
	Diffs []NoteDiff `json:"diffs"`
}

// RunFilter narrows the activity log to a space or a note.
type RunFilter struct {
	Space string
	Note  string
}

// MaxRunsPage bounds one page of the activity log.
const MaxRunsPage = 100

const runColumns = `r.rowid, r.id, r.trigger, r.status, r.space_id, r.note_id, r.outcome, r.failure, r.message, r.model,
	r.tool_calls, r.prompt_tokens, r.completion_tokens, r.created_at, r.started_at, r.finished_at,
	coalesce(CASE WHEN n.deleted_at IS NULL THEN n.title END, json_extract(r.event_json, '$.title'), ''),
	n.id IS NULL OR n.deleted_at IS NOT NULL`

// Runs lists runs newest first, limit at a time; next continues the list,
// empty at its end.
func (s *Store) Runs(ctx context.Context, f RunFilter, limit int, cursor string) (runs []Run, next string, err error) {
	if limit <= 0 || limit > MaxRunsPage {
		limit = MaxRunsPage
	}
	at, rowid := int64(1<<62), int64(1<<62)
	if cursor != "" {
		if at, rowid, err = parseRunCursor(cursor); err != nil {
			return nil, "", err
		}
	}
	err = s.db.ReadTx(ctx, func(tx *sql.Tx) error {
		rows, err := tx.QueryContext(ctx, `SELECT `+runColumns+`
			FROM agent_runs r LEFT JOIN notes n ON n.id = r.note_id
			WHERE (? = '' OR r.space_id = ?) AND (? = '' OR r.note_id = ?)
			  AND (r.created_at < ? OR (r.created_at = ? AND r.rowid < ?))
			ORDER BY r.created_at DESC, r.rowid DESC LIMIT ?`,
			f.Space, f.Space, f.Note, f.Note, at, at, rowid, limit+1)
		if err != nil {
			return err
		}
		runs, err = scanRuns(rows)
		if err != nil {
			return err
		}
		if len(runs) > limit {
			runs = runs[:limit]
			last := runs[limit-1]
			next = base64.RawURLEncoding.EncodeToString(fmt.Appendf(nil, "%d.%d", last.CreatedAt, last.rowid))
		}
		return s.fillEdits(ctx, tx, runs)
	})
	if runs == nil {
		runs = []Run{}
	}
	return runs, next, err
}

// Run returns one run, with the diff of each note it changed.
func (s *Store) Run(ctx context.Context, id string) (RunDetail, error) {
	var d RunDetail
	err := s.db.ReadTx(ctx, func(tx *sql.Tx) error {
		rows, err := tx.QueryContext(ctx, `SELECT `+runColumns+`
			FROM agent_runs r LEFT JOIN notes n ON n.id = r.note_id WHERE r.id = ?`, id)
		if err != nil {
			return err
		}
		runs, err := scanRuns(rows)
		if err != nil {
			return err
		}
		if len(runs) == 0 {
			return ErrRunNotFound
		}
		if err := s.fillEdits(ctx, tx, runs); err != nil {
			return err
		}
		d.Run = runs[0]
		erows, err := tx.QueryContext(ctx, `SELECT note_id, kind, before_title, before_content, after_title, after_content
			FROM agent_run_edits WHERE run_id = ? ORDER BY written_at, note_id`, id)
		if err != nil {
			return err
		}
		defer erows.Close()
		d.Diffs = []NoteDiff{}
		for erows.Next() {
			var nd NoteDiff
			var before, after string
			if err := erows.Scan(&nd.NoteID, &nd.Kind, &nd.TitleBefore, &before, &nd.TitleAfter, &after); err != nil {
				return err
			}
			nd.Lines = Diff(before, after)
			d.Diffs = append(d.Diffs, nd)
		}
		return erows.Err()
	})
	return d, err
}

func scanRuns(rows *sql.Rows) ([]Run, error) {
	defer rows.Close()
	var out []Run
	for rows.Next() {
		var r Run
		if err := rows.Scan(&r.rowid, &r.ID, &r.Trigger, &r.Status, &r.SpaceID, &r.NoteID, &r.Outcome, &r.Failure,
			&r.Message, &r.Model, &r.ToolCalls, &r.PromptTokens, &r.CompletionTokens, &r.CreatedAt, &r.StartedAt,
			&r.FinishedAt, &r.NoteTitle, &r.NoteDeleted); err != nil {
			return nil, err
		}
		r.Edits = []RunEdit{}
		out = append(out, r)
	}
	return out, rows.Err()
}

// fillEdits adds each run's edits, and whether each can be undone now.
func (s *Store) fillEdits(ctx context.Context, tx *sql.Tx, runs []Run) error {
	if len(runs) == 0 {
		return nil
	}
	byID := make(map[string]*Run, len(runs))
	args := []any{len(locks.Family), locks.Family}
	for i := range runs {
		byID[runs[i].ID] = &runs[i]
		args = append(args, runs[i].ID)
	}
	rows, err := tx.QueryContext(ctx, `
		SELECT e.run_id, e.note_id, e.kind, e.after_title, e.written_at, e.undone_at, e.after_revision,
		       n.revision, n.id IS NULL OR n.deleted_at IS NOT NULL, coalesce(substr(n.content, 1, ?) = ?, 0)
		FROM agent_run_edits e LEFT JOIN notes n ON n.id = e.note_id
		WHERE e.run_id IN (?`+strings.Repeat(", ?", len(runs)-1)+`)
		ORDER BY e.written_at, e.note_id`, args...)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var runID string
		var e RunEdit
		var afterRev, rev sql.NullInt64
		var deleted, locked bool
		if err := rows.Scan(&runID, &e.NoteID, &e.Kind, &e.Title, &e.WrittenAt, &e.UndoneAt, &afterRev,
			&rev, &deleted, &locked); err != nil {
			return err
		}
		r := byID[runID]
		switch {
		case e.UndoneAt != nil:
			e.Blocked = BlockedUndone
		case r.Status == StatusQueued || r.Status == StatusRunning:
			e.Blocked = BlockedRunning
		case deleted:
			e.Blocked = BlockedDeleted
		case locked:
			e.Blocked = BlockedLocked
		case !afterRev.Valid || rev.Int64 != afterRev.Int64:
			e.Blocked = BlockedChanged
		}
		e.Undoable = e.Blocked == ""
		r.Edits = append(r.Edits, e)
	}
	return rows.Err()
}

func parseRunCursor(c string) (at, rowid int64, err error) {
	raw, err := base64.RawURLEncoding.DecodeString(c)
	if err == nil {
		_, err = fmt.Sscanf(string(raw), "%d.%d", &at, &rowid)
	}
	// Only what Runs writes: Sscanf would accept anything after the numbers.
	if err != nil || fmt.Sprintf("%d.%d", at, rowid) != string(raw) {
		return 0, 0, &InputError{Field: "cursor", Message: "not a cursor from this list"}
	}
	return at, rowid, nil
}

// Usage is the tokens the account's agents used today, in its time zone.
type Usage struct {
	Day    string `json:"day"`
	Tokens int64  `json:"tokens"`
	Runs   int    `json:"runs"`
}

// UsageToday reports today's usage.
func (s *Store) UsageToday(ctx context.Context) (Usage, error) {
	st, err := s.Settings(ctx)
	if err != nil {
		return Usage{}, err
	}
	u := Usage{Day: s.now().In(st.Location()).Format(time.DateOnly)}
	err = s.db.R.QueryRowContext(ctx, `SELECT prompt_tokens + completion_tokens, runs FROM agent_usage WHERE day = ?`, u.Day).
		Scan(&u.Tokens, &u.Runs)
	if errors.Is(err, sql.ErrNoRows) {
		err = nil
	}
	return u, err
}
