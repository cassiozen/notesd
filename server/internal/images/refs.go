package images

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"regexp"
	"slices"
	"time"

	"github.com/yuin/goldmark"
	"github.com/yuin/goldmark/ast"
	"github.com/yuin/goldmark/extension"
	"github.com/yuin/goldmark/text"

	"notes/server/internal/locks"
)

// Grace is how long an image no note shows is kept before it is deleted:
// long enough for an undo, a conflicted or recovered copy (§6.2, §6.4), or a
// device that was offline to bring the reference back.
const Grace = 30 * 24 * time.Hour

// MaxRefs caps the image ids a locked note's save may list.
const MaxRefs = 1000

// RefPattern is an app image's destination as the apps write it (§7.6),
// capturing the id.
var RefPattern = regexp.MustCompile(`^notesd:image/([0-9a-f]{64})\?w=[1-9]\d{0,5}&h=[1-9]\d{0,5}$`)

var parser = goldmark.New(goldmark.WithExtensions(extension.GFM)).Parser()

// Refs lists the app images markdown shows, each once, in order. Only
// image syntax counts: the same text inside code is not a reference.
func Refs(markdown string) []string {
	var ids []string
	seen := map[string]bool{}
	doc := parser.Parse(text.NewReader([]byte(markdown)))
	_ = ast.Walk(doc, func(node ast.Node, entering bool) (ast.WalkStatus, error) {
		if img, ok := node.(*ast.Image); ok && entering {
			if m := RefPattern.FindStringSubmatch(string(img.Destination)); m != nil && !seen[m[1]] {
				seen[m[1]] = true
				ids = append(ids, m[1])
			}
		}
		return ast.WalkContinue, nil
	})
	return ids
}

// SetRefs records that the note at noteSeq shows exactly ids (none for a
// deleted note). An image it no longer shows is released at now, starting
// its Grace. Runs in the transaction that writes the note.
func SetRefs(ctx context.Context, tx *sql.Tx, noteSeq int64, ids []string, now int64) error {
	keep := map[string]bool{}
	for _, id := range ids {
		keep[id] = true
	}
	rows, err := tx.QueryContext(ctx, `SELECT image_id FROM note_images WHERE note_seq = ?`, noteSeq)
	if err != nil {
		return err
	}
	var dropped []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return err
		}
		if !keep[id] {
			dropped = append(dropped, id)
		}
		delete(keep, id)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, id := range dropped {
		if _, err := tx.ExecContext(ctx, `DELETE FROM note_images WHERE note_seq = ? AND image_id = ?`, noteSeq, id); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE images SET released_at = ? WHERE id = ?`, now, id); err != nil {
			return err
		}
	}
	for id := range keep {
		if _, err := tx.ExecContext(ctx, `INSERT INTO note_images (note_seq, image_id) VALUES (?, ?)`, noteSeq, id); err != nil {
			return err
		}
	}
	return nil
}

// collectable is an image no note shows that was released before the cutoff.
const collectable = `FROM images i WHERE i.released_at IS NOT NULL AND i.released_at < ?
	AND NOT EXISTS (SELECT 1 FROM note_images r WHERE r.image_id = i.id)`

// collectBatch bounds each delete's transaction, so the writer is never held long.
const collectBatch = 100

// Collect deletes the images no note has shown since before cutoff (now
// minus Grace) and returns how many and their bytes. With dryRun it only
// counts them.
func (s *Store) Collect(ctx context.Context, cutoff time.Time, dryRun bool) (n int, size int64, err error) {
	at := cutoff.UnixMilli()
	if dryRun {
		err = s.db.R.QueryRowContext(ctx, `SELECT count(*), coalesce(sum(i.size), 0) `+collectable, at).Scan(&n, &size)
		return n, size, err
	}
	for {
		batch := 0
		err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
			rows, err := tx.QueryContext(ctx, `SELECT i.id, i.size `+collectable+` LIMIT ?`, at, collectBatch)
			if err != nil {
				return err
			}
			var ids []string
			var bytes int64
			for rows.Next() {
				var id string
				var sz int64
				if err := rows.Scan(&id, &sz); err != nil {
					rows.Close()
					return err
				}
				ids = append(ids, id)
				bytes += sz
			}
			rows.Close()
			if err := rows.Err(); err != nil {
				return err
			}
			for _, id := range ids {
				if _, err := tx.ExecContext(ctx, `DELETE FROM images WHERE id = ?`, id); err != nil {
					return err
				}
			}
			batch = len(ids)
			n += batch
			size += bytes
			return nil
		})
		if err != nil {
			return n, size, fmt.Errorf("collect images: %w", err)
		}
		if batch < collectBatch {
			return n, size, nil
		}
	}
}

// Missing lists the images live notes show that aren't stored, for
// `notesd check`. Plain notes' are read from their content, not from
// note_images, which may still await its rebuild (check doesn't run it);
// locked notes' only the record has.
func (s *Store) Missing(ctx context.Context) ([]string, error) {
	shown := map[string]bool{}
	err := s.db.ReadTx(ctx, func(tx *sql.Tx) error {
		rows, err := tx.QueryContext(ctx, `SELECT content FROM notes WHERE deleted_at IS NULL`)
		if err != nil {
			return err
		}
		for rows.Next() {
			var content string
			if err := rows.Scan(&content); err != nil {
				rows.Close()
				return err
			}
			if !locks.IsLocked(content) {
				for _, id := range Refs(content) {
					shown[id] = true
				}
			}
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		rows, err = tx.QueryContext(ctx, `SELECT r.image_id FROM note_images r JOIN notes n ON n.seq = r.note_seq
			WHERE n.deleted_at IS NULL AND substr(n.content, 1, ?) = ?`, len(locks.Family), locks.Family)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var id string
			if err := rows.Scan(&id); err != nil {
				return err
			}
			shown[id] = true
		}
		return rows.Err()
	})
	if err != nil {
		return nil, err
	}
	var missing []string
	for id := range shown {
		if _, err := s.Info(ctx, id); errors.Is(err, ErrNotFound) {
			missing = append(missing, id)
		} else if err != nil {
			return nil, err
		}
	}
	slices.Sort(missing)
	return missing, nil
}

// ReleaseUntracked starts the Grace of plain images from before references
// were tracked that no note shows. Locked ones, of any version, wait for
// their note's next save: the server can't see which locked note shows them.
func ReleaseUntracked(ctx context.Context, tx *sql.Tx, now int64) error {
	_, err := tx.ExecContext(ctx, `UPDATE images SET released_at = ?
		WHERE released_at IS NULL AND content_type != ? AND substr(content_type, 1, ?) != ?
		AND NOT EXISTS (SELECT 1 FROM note_images r WHERE r.image_id = images.id)`,
		now, Locked, len(Locked)+1, Locked+".")
	return err
}
