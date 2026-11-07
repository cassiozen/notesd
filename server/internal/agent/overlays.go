package agent

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

// Overlay kinds.
const (
	KindCard       = "card"       // something to read, dismissed with ×
	KindSuggestion = "suggestion" // a change, accepted with ✓ or rejected with ✗
)

// Limits on what one run shows.
const (
	maxCards       = 3
	maxSuggestions = 20
	maxCardRunes   = 2000
	maxWhyRunes    = 300
)

// Overlay is something a run shows on a note open in an editor: beside the
// note's text, never in it, so it's never saved, synced or searched. It
// lasts until the user answers it or the note's last editor closes.
type Overlay struct {
	ID     string `json:"id"`
	RunID  string `json:"runId"`
	NoteID string `json:"noteId"`
	Kind   string `json:"kind"`
	Place  Place  `json:"place"`
	// Body is a card's Markdown, or why a suggestion is made.
	Body string `json:"body"`
	// Change is a suggestion's edit, made only if the user accepts it.
	Change *edit `json:"change,omitempty"`
}

// Place is where an overlay shows: at the top or end of the note, at the
// cursor, or under the block holding a quote from it.
type Place struct {
	At    string `json:"at,omitempty"`
	After string `json:"after,omitempty"`
}

// Overlay statuses.
const (
	overlayOpen      = "open"
	overlayDismissed = "dismissed"
	overlayAccepted  = "accepted"
	overlayRejected  = "rejected"
	overlayStale     = "stale"  // its change no longer fits the note
	overlayClosed    = "closed" // the note's editors closed, or a later run of its rule replaced it
)

// Errors from AnswerOverlay.
var (
	ErrOverlayGone = errors.New("no open overlay with that id")
	ErrBadAnswer   = errors.New("not an answer this overlay takes")
)

// OverlaysMessage tells editors a note's open overlays (the agent socket's
// "overlays"): all of them, so a reconnect needs nothing else.
func OverlaysMessage(noteID string, list []Overlay) any {
	if list == nil {
		list = []Overlay{}
	}
	return struct {
		Type     string    `json:"type"`
		NoteID   string    `json:"noteId"`
		Overlays []Overlay `json:"overlays"`
	}{"overlays", noteID, list}
}

// addOverlays records what a run shows on its note, replacing what an
// earlier run of the same rule left open there.
func (s *Store) addOverlays(ctx context.Context, r queuedRun, list []Overlay) error {
	now := s.now().UnixMilli()
	return s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `UPDATE agent_overlays SET status = ?, answered_at = ?
			WHERE note_id = ? AND status = 'open' AND run_id != ?
			  AND run_id IN (SELECT id FROM agent_runs WHERE trigger = ? AND space_id = ?)`,
			overlayClosed, now, r.NoteID, r.ID, string(r.Trigger), r.SpaceID); err != nil {
			return err
		}
		for _, o := range list {
			place, err := json.Marshal(o.Place)
			if err != nil {
				return err
			}
			var change any
			if o.Change != nil {
				b, err := json.Marshal(o.Change)
				if err != nil {
					return err
				}
				change = string(b)
			}
			if _, err := tx.ExecContext(ctx, `INSERT INTO agent_overlays (id, run_id, note_id, kind, place, body, change, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)`, o.ID, r.ID, r.NoteID, o.Kind, string(place), o.Body, change, now); err != nil {
				return err
			}
		}
		return nil
	})
}

// OpenOverlays is what's showing on a note, in the order it was shown.
func (s *Store) OpenOverlays(ctx context.Context, noteID string) ([]Overlay, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT id, run_id, kind, place, body, change FROM agent_overlays
		WHERE note_id = ? AND status = 'open' ORDER BY rowid`, noteID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Overlay
	for rows.Next() {
		o := Overlay{NoteID: noteID}
		var place string
		var change sql.NullString
		if err := rows.Scan(&o.ID, &o.RunID, &o.Kind, &place, &o.Body, &change); err != nil {
			return nil, err
		}
		if err := json.Unmarshal([]byte(place), &o.Place); err != nil {
			return nil, fmt.Errorf("overlay %s place: %w", o.ID, err)
		}
		if change.Valid {
			o.Change = &edit{}
			if err := json.Unmarshal([]byte(change.String), o.Change); err != nil {
				return nil, fmt.Errorf("overlay %s change: %w", o.ID, err)
			}
		}
		out = append(out, o)
	}
	return out, rows.Err()
}

// AnswerOverlay records the user's answer to an open overlay: "dismiss" for
// a card; "accept", "reject" or "stale" (it no longer fits) for a
// suggestion. An accepted change was made in the editor, as applied says,
// and is recorded as its run's edit. It returns the overlay's note.
func (s *Store) AnswerOverlay(ctx context.Context, id, answer string, applied *EditorApplied) (noteID string, err error) {
	now := s.now().UnixMilli()
	err = s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		var runID, kind string
		err := tx.QueryRowContext(ctx, `SELECT run_id, note_id, kind FROM agent_overlays WHERE id = ? AND status = 'open'`, id).
			Scan(&runID, &noteID, &kind)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrOverlayGone
		}
		if err != nil {
			return err
		}
		status := map[string]string{"dismiss": overlayDismissed, "accept": overlayAccepted, "reject": overlayRejected, "stale": overlayStale}[answer]
		if status == "" || (kind == KindCard) != (status == overlayDismissed) || (status == overlayAccepted && applied == nil) {
			return ErrBadAnswer
		}
		if _, err := tx.ExecContext(ctx, `UPDATE agent_overlays SET status = ?, answered_at = ? WHERE id = ?`, status, now, id); err != nil {
			return err
		}
		if status != overlayAccepted {
			return nil
		}
		if err := recordEditorEditTx(ctx, tx, runID, noteID, applied.Revision, applied.Before, applied.Shows, now); err != nil {
			return err
		}
		// Its run made a change after all.
		_, err = tx.ExecContext(ctx, `UPDATE agent_runs SET outcome = ? WHERE id = ? AND outcome = ?`, OutcomeEdited, runID, OutcomeNoChange)
		return err
	})
	return noteID, err
}

// CloseOverlays ends what's showing on a note whose last editor closed it,
// and reports whether anything was.
func (s *Store) CloseOverlays(ctx context.Context, noteID string) (bool, error) {
	var n int64
	err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `UPDATE agent_overlays SET status = ?, answered_at = ? WHERE note_id = ? AND status = 'open'`,
			overlayClosed, s.now().UnixMilli(), noteID)
		if err != nil {
			return err
		}
		n, err = res.RowsAffected()
		return err
	})
	return n > 0, err
}

// newOverlayID sorts by time, like a run's.
func newOverlayID(now int64) string {
	return "ov_" + strings.TrimPrefix(newRunID(now), "run_")
}
