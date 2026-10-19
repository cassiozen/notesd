package search

import (
	"context"
	"database/sql"
	"html"
	"strings"
	"time"
	"unicode"

	"notes/server/internal/database"
	"notes/server/internal/images"
	"notes/server/internal/locks"
)

// Per-column BM25 weights (§8.2).
const (
	titleWeight   = 10.0
	contentWeight = 1.0
	maxTerms      = 16
	MaxLimit      = 50
)

// Sentinels marking highlights inside snippet() output. They are private-use
// code points, so after HTML-escaping the excerpt they can be swapped for
// <mark> tags without letting note text inject markup.
const (
	markOpen  = ""
	markClose = ""
)

// Result is one search hit. Excerpt is HTML-safe: note text is escaped and
// matches are wrapped in <mark>…</mark>.
type Result struct {
	ID        string `json:"id"`
	Title     string `json:"title"`
	Excerpt   string `json:"excerpt"`
	UpdatedAt int64  `json:"updatedAt"`
	// Locked notes match on their title only (§10.1).
	Locked  bool   `json:"locked"`
	SpaceID string `json:"spaceId"`
}

// BuildQuery turns free-form user input into an FTS5 MATCH expression. Each
// word becomes a quoted prefix term, so FTS5 operators and syntax characters
// in the input are inert and malformed input can never produce a query
// error. Terms are ANDed. It returns "" when the input has no searchable
// words.
func BuildQuery(input string) string {
	words := strings.FieldsFunc(input, func(r rune) bool {
		return !(unicode.IsLetter(r) || unicode.IsDigit(r) || unicode.Is(unicode.Mn, r))
	})
	if len(words) > maxTerms {
		words = words[:maxTerms]
	}
	terms := make([]string, 0, len(words))
	for _, w := range words {
		// Words contain only letters, digits and combining marks, so they
		// cannot contain a double quote; quoting makes keywords like OR and
		// NEAR literal.
		terms = append(terms, `"`+w+`"*`)
	}
	return strings.Join(terms, " ")
}

// Search runs a ranked full-text search over live notes matching filter.
func Search(ctx context.Context, db *database.DB, input string, filter Filter, limit, offset int) ([]Result, error) {
	match := BuildQuery(input)
	if match == "" {
		return []Result{}, nil
	}
	if limit <= 0 || limit > MaxLimit {
		limit = MaxLimit
	}
	if offset < 0 {
		offset = 0
	}
	cond, args := filter.SQL()
	rows, err := db.R.QueryContext(ctx, `
		SELECT n.id, n.title, n.updated_at,
		       snippet(notes_fts, 1, ?, ?, '…', 20), substr(n.content, 1, ?) = ?, n.space_id
		FROM notes_fts
		JOIN notes n ON n.seq = notes_fts.rowid
		WHERE notes_fts MATCH ?
		  AND n.deleted_at IS NULL
		  AND `+cond+`
		ORDER BY bm25(notes_fts, ?, ?), n.updated_at DESC
		LIMIT ? OFFSET ?`,
		append(append([]any{markOpen, markClose, len(locks.Prefix), locks.Prefix, match}, args...), titleWeight, contentWeight, limit, offset)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	results := []Result{}
	for rows.Next() {
		var r Result
		var excerpt sql.NullString
		if err := rows.Scan(&r.ID, &r.Title, &r.UpdatedAt, &excerpt, &r.Locked, &r.SpaceID); err != nil {
			return nil, err
		}
		r.Excerpt = renderExcerpt(excerpt.String)
		results = append(results, r)
	}
	return results, rows.Err()
}

// Count is how many live notes matching filter a search finds.
func Count(ctx context.Context, db *database.DB, input string, filter Filter) (n int, err error) {
	match := BuildQuery(input)
	if match == "" {
		return 0, nil
	}
	cond, args := filter.SQL()
	err = db.R.QueryRowContext(ctx, `
		SELECT count(*)
		FROM notes_fts
		JOIN notes n ON n.seq = notes_fts.rowid
		WHERE notes_fts MATCH ?
		  AND n.deleted_at IS NULL
		  AND `+cond, append([]any{match}, args...)...).Scan(&n)
	return n, err
}

func renderExcerpt(s string) string {
	s = html.EscapeString(s)
	s = strings.ReplaceAll(s, markOpen, "<mark>")
	return strings.ReplaceAll(s, markClose, "</mark>")
}

// IndexNote replaces the index entry and tags for the note stored at rowid.
// It must run in the same transaction as the write to notes.
func IndexNote(ctx context.Context, tx *sql.Tx, rowid int64, title, markdown string) error {
	if err := UnindexNote(ctx, tx, rowid); err != nil {
		return err
	}
	return insert(ctx, tx, rowid, title, markdown)
}

// UnindexNote removes the index entry and tags for rowid, if any.
func UnindexNote(ctx context.Context, tx *sql.Tx, rowid int64) error {
	if _, err := tx.ExecContext(ctx, `DELETE FROM notes_fts WHERE rowid = ?`, rowid); err != nil {
		return err
	}
	_, err := tx.ExecContext(ctx, `DELETE FROM note_tags WHERE note_seq = ?`, rowid)
	return err
}

func insert(ctx context.Context, tx *sql.Tx, rowid int64, title, markdown string) error {
	if locks.IsLocked(markdown) {
		// The body is ciphertext: only the title is searchable and tagged (§10.1).
		markdown = ""
	}
	if _, err := tx.ExecContext(ctx,
		`INSERT INTO notes_fts (rowid, title, body_text) VALUES (?, ?, ?)`,
		rowid, title, PlainText(markdown)); err != nil {
		return err
	}
	for _, t := range Tags(title, markdown) {
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO note_tags (note_seq, key, name) VALUES (?, ?, ?)`, rowid, t.Key, t.Name); err != nil {
			return err
		}
	}
	return nil
}

// RebuildIfNeeded rebuilds when a migration has asked for it.
func RebuildIfNeeded(ctx context.Context, db *database.DB) (rebuilt bool, err error) {
	var pending bool
	if err := db.R.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM rebuild_index)`).Scan(&pending); err != nil || !pending {
		return false, err
	}
	_, err = Rebuild(ctx, db)
	return err == nil, err
}

// Rebuild recreates the whole index, tags and plain notes' image references
// from the notes table without
// touching sync state (§8.1).
func Rebuild(ctx context.Context, db *database.DB) (int, error) {
	n := 0
	err := db.WriteTx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `DELETE FROM notes_fts; DELETE FROM note_tags; DELETE FROM rebuild_index`); err != nil {
			return err
		}
		// Image references are recomputed for plain notes below (SetRefs
		// releases any they drop). A locked note's were listed by its client
		// and can't be read from its ciphertext, so they stay.
		now := time.Now().UnixMilli()
		if _, err := tx.ExecContext(ctx, `UPDATE images SET released_at = ? WHERE id IN
			(SELECT r.image_id FROM note_images r JOIN notes n ON n.seq = r.note_seq WHERE n.deleted_at IS NOT NULL)`, now); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM note_images WHERE note_seq IN
			(SELECT seq FROM notes WHERE deleted_at IS NOT NULL)`); err != nil {
			return err
		}
		rows, err := tx.QueryContext(ctx, `SELECT seq, title, content FROM notes WHERE deleted_at IS NULL`)
		if err != nil {
			return err
		}
		type doc struct {
			seq            int64
			title, content string
		}
		var docs []doc
		for rows.Next() {
			var d doc
			if err := rows.Scan(&d.seq, &d.title, &d.content); err != nil {
				rows.Close()
				return err
			}
			docs = append(docs, d)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		for _, d := range docs {
			if err := insert(ctx, tx, d.seq, d.title, d.content); err != nil {
				return err
			}
			if !locks.IsLocked(d.content) {
				if err := images.SetRefs(ctx, tx, d.seq, images.Refs(d.content), now); err != nil {
					return err
				}
			}
		}
		if err := images.ReleaseUntracked(ctx, tx, now); err != nil {
			return err
		}
		n = len(docs)
		_, err = tx.ExecContext(ctx, `INSERT INTO notes_fts (notes_fts) VALUES ('optimize')`)
		return err
	})
	return n, err
}
