// Package spaces stores the list of spaces (§7.7): separate contexts that
// each hold their own notes. Clients choose space ids, so a space can be
// created offline, and download the whole list on every sync, like the
// lock keyring.
package spaces

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"notes/server/internal/database"
)

// Default is the id of the space every install has. It can be renamed but
// not deleted, and notes go to it when they name no space or one that is gone.
const Default = "default"

// MaxNameRunes caps a space's name.
const MaxNameRunes = 64

// Space is one space, with how many live notes it holds.
type Space struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	CreatedAt int64  `json:"createdAt"`
	NoteCount int    `json:"noteCount"`
}

// Errors.
var (
	ErrDefault = errors.New("the default space can't be deleted")
)

// NotEmptyError refuses deleting a space that still holds notes.
type NotEmptyError struct{ Count int }

func (e *NotEmptyError) Error() string {
	return fmt.Sprintf("the space holds %d notes; move or delete them first", e.Count)
}

// InputError reports an invalid request.
type InputError struct{ Field, Message string }

func (e *InputError) Error() string { return e.Field + ": " + e.Message }

var idPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

// Store persists spaces.
type Store struct {
	db  *database.DB
	now func() time.Time
}

// NewStore returns a Store using the system clock.
func NewStore(db *database.DB) *Store { return &Store{db: db, now: time.Now} }

// List returns every space, the default first, then by name.
func (s *Store) List(ctx context.Context) ([]Space, error) {
	rows, err := s.db.R.QueryContext(ctx, `
		SELECT s.id, s.name, s.created_at,
		       (SELECT count(*) FROM notes n WHERE n.space_id = s.id AND n.deleted_at IS NULL)
		FROM spaces s ORDER BY s.id != ?, s.name_key, s.id`, Default)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Space{}
	for rows.Next() {
		var sp Space
		if err := rows.Scan(&sp.ID, &sp.Name, &sp.CreatedAt, &sp.NoteCount); err != nil {
			return nil, err
		}
		out = append(out, sp)
	}
	return out, rows.Err()
}

// Put creates the space id or renames it. A name another space has, ignoring
// case, gets " 2", " 3"… appended, so two devices creating "Work" offline end
// up with "Work" and "Work 2". It returns the space as stored.
func (s *Store) Put(ctx context.Context, id, name string) (Space, error) {
	if !idPattern.MatchString(id) {
		return Space{}, &InputError{"id", "must be 1-64 characters of [A-Za-z0-9_-]"}
	}
	name, err := normalizeName(name)
	if err != nil {
		return Space{}, err
	}
	var out Space
	err = s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		stored := name
		for i := 2; ; i++ {
			var taken bool
			if err := tx.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM spaces WHERE name_key = ? AND id != ?)`,
				Key(stored), id).Scan(&taken); err != nil {
				return err
			}
			if !taken {
				break
			}
			stored = fmt.Sprintf("%s %d", name, i)
		}
		if err := tx.QueryRowContext(ctx, `
			INSERT INTO spaces (id, name, name_key, created_at) VALUES (?, ?, ?, ?)
			ON CONFLICT (id) DO UPDATE SET name = excluded.name, name_key = excluded.name_key
			RETURNING id, name, created_at`,
			id, stored, Key(stored), s.now().UnixMilli()).Scan(&out.ID, &out.Name, &out.CreatedAt); err != nil {
			return err
		}
		return tx.QueryRowContext(ctx, `SELECT count(*) FROM notes WHERE space_id = ? AND deleted_at IS NULL`,
			id).Scan(&out.NoteCount)
	})
	return out, err
}

// Delete removes an empty space. Deleting one that is already gone succeeds.
func (s *Store) Delete(ctx context.Context, id string) error {
	if id == Default {
		return ErrDefault
	}
	return s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		var n int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM notes WHERE space_id = ? AND deleted_at IS NULL`,
			id).Scan(&n); err != nil {
			return err
		}
		if n > 0 {
			return &NotEmptyError{Count: n}
		}
		_, err := tx.ExecContext(ctx, `DELETE FROM spaces WHERE id = ?`, id)
		return err
	})
}

// Resolve returns id if that space exists, or the default: a note filed
// offline into a space deleted elsewhere lands there.
func Resolve(ctx context.Context, tx *sql.Tx, id string) (string, error) {
	var ok bool
	err := tx.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM spaces WHERE id = ?)`, id).Scan(&ok)
	if err != nil || !ok {
		return Default, err
	}
	return id, nil
}

// Key identifies a name ignoring case.
func Key(name string) string { return strings.ToLower(name) }

// normalizeName trims and collapses whitespace and checks what is left.
func normalizeName(name string) (string, error) {
	if !utf8.ValidString(name) {
		return "", &InputError{"name", "must be valid UTF-8"}
	}
	name = strings.Join(strings.Fields(name), " ")
	if strings.IndexFunc(name, unicode.IsControl) >= 0 {
		return "", &InputError{"name", "can't contain control characters"}
	}
	switch {
	case name == "":
		return "", &InputError{"name", "required"}
	case utf8.RuneCountInString(name) > MaxNameRunes:
		return "", &InputError{"name", fmt.Sprintf("at most %d characters", MaxNameRunes)}
	}
	return name, nil
}
