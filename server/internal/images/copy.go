package images

import (
	"context"
	"database/sql"
)

// Blob is a stored image with its bytes, as a move carries it to another
// file (architecture §7.9).
type Blob struct {
	ID          string
	ContentType string
	Data        []byte
	CreatedAt   int64
}

// Shown returns the stored images the note at noteSeq shows. An image it
// shows that isn't stored is left out: there's nothing to carry.
func Shown(ctx context.Context, tx *sql.Tx, noteSeq int64) ([]Blob, error) {
	rows, err := tx.QueryContext(ctx, `SELECT i.id, i.content_type, i.data, i.created_at
		FROM note_images r JOIN images i ON i.id = r.image_id WHERE r.note_seq = ? ORDER BY i.id`, noteSeq)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Blob
	for rows.Next() {
		var b Blob
		if err := rows.Scan(&b.ID, &b.ContentType, &b.Data, &b.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, b)
	}
	return out, rows.Err()
}

// PutTx stores b in the caller's transaction, unless this file already has
// it (the same id is the same bytes). The note that shows it records its
// reference in the same transaction.
func PutTx(ctx context.Context, tx *sql.Tx, b Blob) error {
	_, err := tx.ExecContext(ctx,
		`INSERT INTO images (id, content_type, size, data, created_at) VALUES (?, ?, ?, ?, ?)
		 ON CONFLICT (id) DO NOTHING`,
		b.ID, b.ContentType, len(b.Data), b.Data, b.CreatedAt)
	return err
}
