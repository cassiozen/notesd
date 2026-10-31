// Package images stores the images notes reference (architecture §7.6):
// content-addressed blobs, kept in SQLite with the notes so one backup
// holds both. Which notes show which images is tracked in note_images:
// plain notes' references are read from their content, locked notes' are
// listed by their clients. An image no note has shown for Grace is deleted.
package images

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"regexp"
	"time"

	"notes/server/internal/database"
)

// MaxBytes caps one image as uploaded. Clients downscale first (2048 px on
// the long side), so a real image is far smaller.
const MaxBytes = 10 << 20

// Locked is the type of a locked note's image: AES-256-GCM ciphertext the
// server can't read (§10.1).
const Locked = "application/vnd.notesd.locked"

// Types are the content types an image may be stored as. SVG is not one: it
// is a document that can run script, not a picture.
var Types = map[string]bool{
	"image/jpeg": true,
	"image/png":  true,
	"image/webp": true,
	"image/gif":  true,
	Locked:       true,
}

// Errors.
var (
	ErrNotFound     = errors.New("image not found")
	ErrHashMismatch = errors.New("the body's SHA-256 is not the image id")
	ErrBadID        = errors.New("image id must be 64 lowercase hex characters")
)

// TypeError reports a content type outside Types, or a body that isn't the
// image type it claims.
type TypeError struct{ Message string }

func (e *TypeError) Error() string { return e.Message }

// Image is one stored image's metadata.
type Image struct {
	ID          string `json:"id"`
	ContentType string `json:"contentType"`
	Size        int64  `json:"size"`
	CreatedAt   int64  `json:"createdAt"`
}

var idPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

// ValidID reports whether id is a SHA-256 in lowercase hex.
func ValidID(id string) bool { return idPattern.MatchString(id) }

// Store persists images.
type Store struct {
	db       *database.DB
	now      func() time.Time
	onCommit func()
}

// NewStore returns a Store using the system clock.
func NewStore(db *database.DB) *Store { return &Store{db: db, now: time.Now} }

// SetClock overrides the clock, for tests.
func (s *Store) SetClock(now func() time.Time) { s.now = now }

// OnCommit has fn called after each write commits. fn must not block: it
// runs on the request's goroutine.
func (s *Store) OnCommit(fn func()) { s.onCommit = fn }

func (s *Store) committed() {
	if s.onCommit != nil {
		s.onCommit()
	}
}

// Put stores data as image id. created is false when the image already
// existed, which makes a retried upload harmless.
func (s *Store) Put(ctx context.Context, id, contentType string, data []byte) (img Image, created bool, err error) {
	if !ValidID(id) {
		return Image{}, false, ErrBadID
	}
	if err := checkType(contentType, data); err != nil {
		return Image{}, false, err
	}
	sum := sha256.Sum256(data)
	if hex.EncodeToString(sum[:]) != id {
		return Image{}, false, ErrHashMismatch
	}
	img = Image{ID: id, ContentType: contentType, Size: int64(len(data)), CreatedAt: s.now().UnixMilli()}
	err = s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx,
			`INSERT INTO images (id, content_type, size, data, created_at, released_at) VALUES (?, ?, ?, ?, ?, ?)
			 ON CONFLICT (id) DO NOTHING`,
			img.ID, img.ContentType, img.Size, data, img.CreatedAt, img.CreatedAt)
		if err != nil {
			return err
		}
		n, err := res.RowsAffected()
		if err != nil {
			return err
		}
		if created = n == 1; created {
			return nil
		}
		// Uploaded again for a note about to show it: restart its Grace, so
		// it isn't deleted before that note is saved.
		if _, err := tx.ExecContext(ctx, `UPDATE images SET released_at = ? WHERE id = ?`, img.CreatedAt, id); err != nil {
			return err
		}
		// Same bytes, so the same image; keep the stored metadata.
		return tx.QueryRowContext(ctx,
			`SELECT content_type, size, created_at FROM images WHERE id = ?`, id,
		).Scan(&img.ContentType, &img.Size, &img.CreatedAt)
	})
	if err != nil {
		return Image{}, false, err
	}
	s.committed()
	return img, created, nil
}

// Info returns an image's metadata without reading its bytes.
func (s *Store) Info(ctx context.Context, id string) (Image, error) {
	if !ValidID(id) {
		return Image{}, ErrNotFound
	}
	img := Image{ID: id}
	err := s.db.R.QueryRowContext(ctx,
		`SELECT content_type, size, created_at FROM images WHERE id = ?`, id,
	).Scan(&img.ContentType, &img.Size, &img.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return Image{}, ErrNotFound
	}
	return img, err
}

// Get returns an image and its bytes.
func (s *Store) Get(ctx context.Context, id string) (Image, []byte, error) {
	if !ValidID(id) {
		return Image{}, nil, ErrNotFound
	}
	img := Image{ID: id}
	var data []byte
	err := s.db.R.QueryRowContext(ctx,
		`SELECT content_type, size, data, created_at FROM images WHERE id = ?`, id,
	).Scan(&img.ContentType, &img.Size, &data, &img.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return Image{}, nil, ErrNotFound
	}
	if err != nil {
		return Image{}, nil, err
	}
	return img, data, nil
}

// Check re-hashes every image and returns the ids whose bytes no longer
// match, for `notesd check`.
func (s *Store) Check(ctx context.Context) ([]string, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT id FROM images ORDER BY id`)
	if err != nil {
		return nil, err
	}
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return nil, err
		}
		ids = append(ids, id)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	// One image at a time, so checking never holds every blob in memory.
	var bad []string
	for _, id := range ids {
		var data []byte
		if err := s.db.R.QueryRowContext(ctx, `SELECT data FROM images WHERE id = ?`, id).Scan(&data); err != nil {
			return nil, fmt.Errorf("image %s: %w", id, err)
		}
		sum := sha256.Sum256(data)
		if hex.EncodeToString(sum[:]) != id {
			bad = append(bad, id)
		}
	}
	return bad, nil
}

// Sniff returns the image type data is, from its leading bytes, or "" if
// it is none of the plain Types (a decrypted image carries no type).
func Sniff(data []byte) string {
	for _, t := range []string{"image/jpeg", "image/png", "image/webp", "image/gif"} {
		if checkType(t, data) == nil {
			return t
		}
	}
	return ""
}

// Ext is the file extension for a plain image type, without the dot.
func Ext(contentType string) string {
	switch contentType {
	case "image/jpeg":
		return "jpg"
	case "image/png":
		return "png"
	case "image/webp":
		return "webp"
	case "image/gif":
		return "gif"
	}
	return ""
}

// magic holds each image type's leading bytes. WebP is RIFF....WEBP.
var magic = map[string][][]byte{
	"image/jpeg": {{0xFF, 0xD8, 0xFF}},
	"image/png":  {{0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}},
	"image/gif":  {[]byte("GIF87a"), []byte("GIF89a")},
}

func checkType(contentType string, data []byte) error {
	if !Types[contentType] {
		return &TypeError{Message: "images must be JPEG, PNG, WebP or GIF"}
	}
	if len(data) == 0 {
		return &TypeError{Message: "the image is empty"}
	}
	if contentType == Locked {
		// nonce(12) ‖ ciphertext ‖ tag(16): anything shorter can't be one.
		if len(data) < 12+16+1 {
			return &TypeError{Message: "an encrypted image is too short"}
		}
		return nil
	}
	if contentType == "image/webp" {
		if len(data) >= 12 && bytes.Equal(data[:4], []byte("RIFF")) && bytes.Equal(data[8:12], []byte("WEBP")) {
			return nil
		}
		return &TypeError{Message: "the body is not a WebP image"}
	}
	for _, m := range magic[contentType] {
		if bytes.HasPrefix(data, m) {
			return nil
		}
	}
	return &TypeError{Message: "the body isn't the image type it claims (" + contentType + ")"}
}
