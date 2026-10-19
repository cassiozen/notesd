// Package notes owns note persistence: creation, revision-checked updates,
// tombstone deletion, listing, and the idempotency and change-sequence
// bookkeeping that every mutation performs (§4, §5.1, §6.4).
//
// Every mutation runs in one write transaction that
//  1. returns the stored response if the operation ID was already applied,
//  2. validates the base revision,
//  3. takes the next global change sequence,
//  4. writes the note and its search-index entry, and
//  5. records the operation ID with its response.
//
// Requests that fail (conflicts, validation errors) record nothing, so
// retrying them re-evaluates against current state. Because revisions only
// ever grow, a request rejected as stale stays rejected.
package notes

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"notes/server/internal/database"
	"notes/server/internal/images"
	"notes/server/internal/locks"
	"notes/server/internal/search"
	"notes/server/internal/spaces"
)

// Limits on client input.
const (
	MaxTitleBytes   = 1 << 10 // 1 KiB
	MaxContentBytes = 1 << 20 // 1 MiB
	MaxListLimit    = 200
	DefaultListSize = 50
	previewChars    = 160
	// Raw indexed text read per note to build a preview: blank lines and
	// runs of spaces shrink, so read more than the preview keeps.
	previewFetch = 2 * previewChars
	// Client-supplied timestamps further in the future than this are
	// replaced by the server clock.
	maxClockSkew = 24 * time.Hour
)

var (
	idPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)
	opPattern = regexp.MustCompile(`^[A-Za-z0-9_.:-]{1,128}$`)
)

// Note is the full state of a note as stored on the server. Tombstones have
// DeletedAt set and empty title and content.
type Note struct {
	ID         string  `json:"id"`
	Title      string  `json:"title"`
	Content    string  `json:"content"`
	Revision   int64   `json:"revision"`
	ChangeSeq  int64   `json:"changeSeq"`
	CreatedAt  int64   `json:"createdAt"`
	UpdatedAt  int64   `json:"updatedAt"`
	DeletedAt  *int64  `json:"deletedAt"`
	ConflictOf *string `json:"conflictOf,omitempty"`
	// The space the note is in (§7.7).
	SpaceID string `json:"spaceId"`
	// A locked note's images as its clients last listed them (§7.6), so a
	// device that can't read the body still knows them, for a copy it
	// makes of it. Absent for a plain note, whose content shows them.
	ImageIDs []string `json:"imageIds,omitempty"`
}

// queryer is a transaction or a pool.
type queryer interface {
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
}

// FillImageIDs sets a locked note's ImageIDs from note_images.
func FillImageIDs(ctx context.Context, q queryer, n *Note) error {
	if !locks.IsLocked(n.Content) {
		return nil
	}
	rows, err := q.QueryContext(ctx, `SELECT r.image_id FROM note_images r JOIN notes n ON n.seq = r.note_seq
		WHERE n.id = ? ORDER BY r.image_id`, n.ID)
	if err != nil {
		return err
	}
	defer rows.Close()
	n.ImageIDs = nil
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return err
		}
		n.ImageIDs = append(n.ImageIDs, id)
	}
	return rows.Err()
}

// Columns is the SELECT list matching ScanNote.
const Columns = `id, title, content, revision, change_seq, created_at, updated_at, deleted_at, conflict_of, space_id`

// ScanNote reads a row selected with Columns.
func ScanNote(row interface{ Scan(...any) error }) (Note, error) {
	var n Note
	var deleted sql.NullInt64
	var conflictOf sql.NullString
	err := row.Scan(&n.ID, &n.Title, &n.Content, &n.Revision, &n.ChangeSeq,
		&n.CreatedAt, &n.UpdatedAt, &deleted, &conflictOf, &n.SpaceID)
	if deleted.Valid {
		n.DeletedAt = &deleted.Int64
	}
	if conflictOf.Valid {
		n.ConflictOf = &conflictOf.String
	}
	return n, err
}

// Mutation statuses.
const (
	StatusCreated        = "created"
	StatusUpdated        = "updated"
	StatusUnchanged      = "unchanged"
	StatusDeleted        = "deleted"
	StatusAlreadyDeleted = "already_deleted"
)

// Result acknowledges an applied mutation (§5.1). Clients already hold the
// content they sent, so it is not echoed back.
type Result struct {
	ID        string `json:"id"`
	Status    string `json:"status"`
	Revision  int64  `json:"revision"`
	ChangeSeq int64  `json:"changeSeq"`
	UpdatedAt int64  `json:"updatedAt"`
	DeletedAt *int64 `json:"deletedAt,omitempty"`
	// The space a created or updated note is in: the one it named, or the
	// default if that space is gone (§7.7).
	SpaceID string `json:"spaceId,omitempty"`
	// HTTPStatus is the status code of the original response, so replays
	// answer identically.
	HTTPStatus int `json:"-"`
	// Replayed is true when the operation ID had already been applied and
	// this is the stored response.
	Replayed bool `json:"-"`
}

// Errors returned by the store.
var (
	ErrNotFound       = errors.New("note not found")
	ErrOperationReuse = errors.New("operation ID was already used for a different request")
)

// Conflict codes.
const (
	CodeRevisionConflict = "revision_conflict" // base revision is stale
	CodeNoteDeleted      = "note_deleted"      // note is a tombstone
	CodeNoteExists       = "note_exists"       // create with an ID already in use
)

// ConflictError rejects a mutation that does not apply to the current state.
// Current is the server's latest version for the client to resolve against.
type ConflictError struct {
	Code    string
	Current Note
}

func (e *ConflictError) Error() string { return e.Code }

// ValidationError reports invalid client input.
type ValidationError struct {
	Field   string
	Message string
}

func (e *ValidationError) Error() string { return e.Field + ": " + e.Message }

// Store persists notes.
type Store struct {
	db  *database.DB
	now func() time.Time
}

// NewStore returns a Store using the system clock.
func NewStore(db *database.DB) *Store {
	return &Store{db: db, now: time.Now}
}

// SetClock overrides the clock, for tests.
func (s *Store) SetClock(now func() time.Time) { s.now = now }

// CreateInput describes a new note. Timestamps are optional and
// informational; when absent or implausible the server clock is used.
type CreateInput struct {
	OperationID string  `json:"operationId"`
	ID          string  `json:"id"`
	Title       string  `json:"title"`
	Content     string  `json:"content"`
	CreatedAt   *int64  `json:"createdAt,omitempty"`
	UpdatedAt   *int64  `json:"updatedAt,omitempty"`
	ConflictOf  *string `json:"conflictOf,omitempty"`
	// The space to file the note in; absent is the default space.
	SpaceID *string `json:"spaceId,omitempty"`
	// The images a locked note shows, which the server can't read from its
	// ciphertext (§7.6). Ignored for a plain note. Not omitempty: the
	// request hash must tell absent (nil) from none ([]).
	ImageIDs []string `json:"imageIds"`
}

// UpdateInput replaces a note's title and content, provided BaseRevision is
// still the current revision.
type UpdateInput struct {
	OperationID  string `json:"operationId"`
	ID           string `json:"id"`
	BaseRevision int64  `json:"baseRevision"`
	Title        string `json:"title"`
	Content      string `json:"content"`
	UpdatedAt    *int64 `json:"updatedAt,omitempty"`
	// The space to move the note to; absent keeps its space.
	SpaceID *string `json:"spaceId,omitempty"`
	// As CreateInput.ImageIDs; nil (absent) keeps the images recorded, for
	// a client that sends a locked body it can't read, such as a merge's.
	ImageIDs []string `json:"imageIds"`
}

// DeleteInput turns a note into a tombstone, provided BaseRevision is still
// the current revision.
type DeleteInput struct {
	OperationID  string `json:"operationId"`
	ID           string `json:"id"`
	BaseRevision int64  `json:"baseRevision"`
}

// Create inserts a new note at revision 1.
func (s *Store) Create(ctx context.Context, in CreateInput) (Result, error) {
	if err := validateOp(in.OperationID); err != nil {
		return Result{}, err
	}
	if err := validateID("id", in.ID); err != nil {
		return Result{}, err
	}
	if err := validateText(in.Title, in.Content); err != nil {
		return Result{}, err
	}
	if in.ConflictOf != nil {
		if err := validateID("conflictOf", *in.ConflictOf); err != nil {
			return Result{}, err
		}
	}
	if err := validateImageIDs(in.ImageIDs); err != nil {
		return Result{}, err
	}
	if err := validateSpace(in.SpaceID); err != nil {
		return Result{}, err
	}
	now := s.now().UnixMilli()
	created := s.clientTime(in.CreatedAt, now)
	updated := s.clientTime(in.UpdatedAt, now)
	if updated < created {
		updated = created
	}

	return s.apply(ctx, in.OperationID, hashRequest("create", in), func(tx *sql.Tx) (Result, error) {
		if cur, err := loadForUpdate(ctx, tx, in.ID); err == nil {
			return Result{}, &ConflictError{Code: CodeNoteExists, Current: cur.Note}
		} else if !errors.Is(err, ErrNotFound) {
			return Result{}, err
		}
		space, err := resolveSpace(ctx, tx, in.SpaceID, spaces.Default)
		if err != nil {
			return Result{}, err
		}
		seq, err := nextChangeSeq(ctx, tx)
		if err != nil {
			return Result{}, err
		}
		res, err := tx.ExecContext(ctx, `
			INSERT INTO notes (id, title, content, revision, change_seq, created_at, updated_at, conflict_of, space_id)
			VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?)`,
			in.ID, in.Title, in.Content, seq, created, updated, in.ConflictOf, space)
		if err != nil {
			return Result{}, err
		}
		rowid, err := res.LastInsertId()
		if err != nil {
			return Result{}, err
		}
		if err := search.IndexNote(ctx, tx, rowid, in.Title, in.Content); err != nil {
			return Result{}, err
		}
		if err := images.SetRefs(ctx, tx, rowid, shownImages(in.Content, in.ImageIDs), now); err != nil {
			return Result{}, err
		}
		return Result{ID: in.ID, Status: StatusCreated, Revision: 1, ChangeSeq: seq,
			UpdatedAt: updated, SpaceID: space, HTTPStatus: 201}, nil
	})
}

// Update replaces a note's title and content.
func (s *Store) Update(ctx context.Context, in UpdateInput) (Result, error) {
	if err := validateOp(in.OperationID); err != nil {
		return Result{}, err
	}
	if err := validateID("id", in.ID); err != nil {
		return Result{}, err
	}
	if in.BaseRevision < 1 {
		return Result{}, &ValidationError{"baseRevision", "must be a positive revision"}
	}
	if err := validateText(in.Title, in.Content); err != nil {
		return Result{}, err
	}
	if err := validateImageIDs(in.ImageIDs); err != nil {
		return Result{}, err
	}
	if err := validateSpace(in.SpaceID); err != nil {
		return Result{}, err
	}
	now := s.now().UnixMilli()
	updated := s.clientTime(in.UpdatedAt, now)

	locking := false
	res, err := s.apply(ctx, in.OperationID, hashRequest("update", in), func(tx *sql.Tx) (Result, error) {
		cur, err := loadForUpdate(ctx, tx, in.ID)
		if err != nil {
			return Result{}, err
		}
		if cur.DeletedAt != nil {
			return Result{}, &ConflictError{Code: CodeNoteDeleted, Current: cur.Note}
		}
		if cur.Revision != in.BaseRevision {
			return Result{}, &ConflictError{Code: CodeRevisionConflict, Current: cur.Note}
		}
		space, err := resolveSpace(ctx, tx, in.SpaceID, cur.SpaceID)
		if err != nil {
			return Result{}, err
		}
		if cur.Title == in.Title && cur.Content == in.Content && cur.SpaceID == space {
			// The same body may come with its images listed (again): record them.
			if locks.IsLocked(in.Content) && in.ImageIDs != nil {
				if err := images.SetRefs(ctx, tx, cur.rowid, in.ImageIDs, now); err != nil {
					return Result{}, err
				}
			}
			return Result{ID: cur.ID, Status: StatusUnchanged, Revision: cur.Revision,
				ChangeSeq: cur.ChangeSeq, UpdatedAt: cur.UpdatedAt, SpaceID: space, HTTPStatus: 200}, nil
		}
		if updated < cur.CreatedAt {
			updated = cur.CreatedAt
		}
		seq, err := nextChangeSeq(ctx, tx)
		if err != nil {
			return Result{}, err
		}
		rev := cur.Revision + 1
		if _, err := tx.ExecContext(ctx, `
			UPDATE notes SET title = ?, content = ?, revision = ?, change_seq = ?, updated_at = ?, space_id = ?
			WHERE seq = ?`,
			in.Title, in.Content, rev, seq, updated, space, cur.rowid); err != nil {
			return Result{}, err
		}
		if err := search.IndexNote(ctx, tx, cur.rowid, in.Title, in.Content); err != nil {
			return Result{}, err
		}
		if !locks.IsLocked(in.Content) || in.ImageIDs != nil {
			if err := images.SetRefs(ctx, tx, cur.rowid, shownImages(in.Content, in.ImageIDs), now); err != nil {
				return Result{}, err
			}
		}
		locking = locks.IsLocked(in.Content) && !locks.IsLocked(cur.Content)
		return Result{ID: cur.ID, Status: StatusUpdated, Revision: rev, ChangeSeq: seq,
			UpdatedAt: updated, SpaceID: space, HTTPStatus: 200}, nil
	})
	if err == nil && locking {
		// The plain text is zeroed in the database (secure_delete) but its old
		// pages are still in the WAL (§10.1). Best effort: the save stands.
		if werr := s.db.ScrubWAL(ctx); werr != nil {
			slog.WarnContext(ctx, "scrub WAL after locking a note", "err", werr)
		}
	}
	return res, err
}

// Delete turns a note into a permanent tombstone (§6.4). The title and
// content are cleared; the ID, revision, change sequence and deletion time
// remain so that stale devices cannot resurrect it.
func (s *Store) Delete(ctx context.Context, in DeleteInput) (Result, error) {
	if err := validateOp(in.OperationID); err != nil {
		return Result{}, err
	}
	if err := validateID("id", in.ID); err != nil {
		return Result{}, err
	}
	if in.BaseRevision < 1 {
		return Result{}, &ValidationError{"baseRevision", "must be a positive revision"}
	}
	now := s.now().UnixMilli()

	return s.apply(ctx, in.OperationID, hashRequest("delete", in), func(tx *sql.Tx) (Result, error) {
		cur, err := loadForUpdate(ctx, tx, in.ID)
		if err != nil {
			return Result{}, err
		}
		if cur.DeletedAt != nil {
			// The outcome the client wants already holds, whatever its base.
			return Result{ID: cur.ID, Status: StatusAlreadyDeleted, Revision: cur.Revision,
				ChangeSeq: cur.ChangeSeq, UpdatedAt: cur.UpdatedAt, DeletedAt: cur.DeletedAt,
				HTTPStatus: 200}, nil
		}
		if cur.Revision != in.BaseRevision {
			// A stale deletion never discards a newer edit (§6.4).
			return Result{}, &ConflictError{Code: CodeRevisionConflict, Current: cur.Note}
		}
		seq, err := nextChangeSeq(ctx, tx)
		if err != nil {
			return Result{}, err
		}
		rev := cur.Revision + 1
		updated := max(now, cur.CreatedAt)
		if _, err := tx.ExecContext(ctx, `
			UPDATE notes SET title = '', content = '', revision = ?, change_seq = ?,
			                 updated_at = ?, deleted_at = ?
			WHERE seq = ?`,
			rev, seq, updated, now, cur.rowid); err != nil {
			return Result{}, err
		}
		if err := search.UnindexNote(ctx, tx, cur.rowid); err != nil {
			return Result{}, err
		}
		if err := images.SetRefs(ctx, tx, cur.rowid, nil, now); err != nil {
			return Result{}, err
		}
		return Result{ID: cur.ID, Status: StatusDeleted, Revision: rev, ChangeSeq: seq,
			UpdatedAt: updated, DeletedAt: &now, HTTPStatus: 200}, nil
	})
}

// Get returns a note, including tombstones.
func (s *Store) Get(ctx context.Context, id string) (Note, error) {
	if err := validateID("id", id); err != nil {
		return Note{}, ErrNotFound
	}
	n, err := ScanNote(s.db.R.QueryRowContext(ctx, `SELECT `+Columns+` FROM notes WHERE id = ?`, id))
	if errors.Is(err, sql.ErrNoRows) {
		return Note{}, ErrNotFound
	}
	if err != nil {
		return n, err
	}
	return n, FillImageIDs(ctx, s.db.R, &n)
}

// ListItem is a note summary for list views (§9).
type ListItem struct {
	ID        string `json:"id"`
	Title     string `json:"title"`
	Preview   string `json:"preview"`
	Revision  int64  `json:"revision"`
	CreatedAt int64  `json:"createdAt"`
	UpdatedAt int64  `json:"updatedAt"`
	// Locked notes have an encrypted body and so an empty preview (§10.1).
	Locked  bool   `json:"locked"`
	SpaceID string `json:"spaceId"`
}

// Count is the number of live (not deleted) notes matching filter.
func (s *Store) Count(ctx context.Context, filter search.Filter) (n int, err error) {
	cond, args := filter.SQL()
	err = s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM notes n WHERE n.deleted_at IS NULL AND `+cond, args...).Scan(&n)
	return n, err
}

// List returns live notes matching filter, most recently updated first.
// cursor is the opaque value returned by the previous page, or "" for the
// first page.
func (s *Store) List(ctx context.Context, filter search.Filter, limit int, cursor string) (items []ListItem, next string, err error) {
	if limit <= 0 {
		limit = DefaultListSize
	}
	if limit > MaxListLimit {
		limit = MaxListLimit
	}
	afterTime, afterID, err := decodeListCursor(cursor)
	if err != nil {
		return nil, "", &ValidationError{"cursor", "invalid cursor"}
	}
	cond, args := filter.SQL()
	rows, err := s.db.R.QueryContext(ctx, `
		SELECT n.id, n.title, n.revision, n.created_at, n.updated_at,
		       substr(COALESCE(f.body_text, ''), 1, ?), substr(n.content, 1, ?) = ?, n.space_id
		FROM notes n
		LEFT JOIN notes_fts f ON f.rowid = n.seq
		WHERE n.deleted_at IS NULL
		  AND (? = 0 OR (n.updated_at, n.id) < (?, ?))
		  AND `+cond+`
		ORDER BY n.updated_at DESC, n.id DESC
		LIMIT ?`,
		append(append([]any{previewFetch, len(locks.Prefix), locks.Prefix, boolInt(cursor != ""), afterTime, afterID}, args...), limit+1)...)
	if err != nil {
		return nil, "", err
	}
	defer rows.Close()
	items = []ListItem{}
	for rows.Next() {
		var it ListItem
		if err := rows.Scan(&it.ID, &it.Title, &it.Revision, &it.CreatedAt, &it.UpdatedAt, &it.Preview, &it.Locked, &it.SpaceID); err != nil {
			return nil, "", err
		}
		it.Preview = preview(it.Preview, utf8.RuneCountInString(it.Preview) == previewFetch)
		items = append(items, it)
	}
	if err := rows.Err(); err != nil {
		return nil, "", err
	}
	if len(items) > limit {
		items = items[:limit]
		last := items[len(items)-1]
		next = encodeListCursor(last.UpdatedAt, last.ID)
	}
	return items, next, nil
}

// ---- internals ----

type storedNote struct {
	Note
	rowid int64
}

func loadForUpdate(ctx context.Context, tx *sql.Tx, id string) (storedNote, error) {
	var sn storedNote
	var deleted sql.NullInt64
	var conflictOf sql.NullString
	err := tx.QueryRowContext(ctx, `SELECT seq, `+Columns+` FROM notes WHERE id = ?`, id).Scan(
		&sn.rowid, &sn.ID, &sn.Title, &sn.Content, &sn.Revision, &sn.ChangeSeq,
		&sn.CreatedAt, &sn.UpdatedAt, &deleted, &conflictOf, &sn.SpaceID)
	if errors.Is(err, sql.ErrNoRows) {
		return sn, ErrNotFound
	}
	if deleted.Valid {
		sn.DeletedAt = &deleted.Int64
	}
	if conflictOf.Valid {
		sn.ConflictOf = &conflictOf.String
	}
	if err != nil {
		return sn, err
	}
	// A conflict answers with this note; a device making a copy of it needs its images.
	return sn, FillImageIDs(ctx, tx, &sn.Note)
}

// nextChangeSeq allocates the next global change sequence inside the
// caller's write transaction, so the sequence and the note write commit (or
// roll back) together and cursors follow commit order (§5.2 rule 4).
func nextChangeSeq(ctx context.Context, tx *sql.Tx) (int64, error) {
	var seq int64
	err := tx.QueryRowContext(ctx,
		`UPDATE sync_state SET current_seq = current_seq + 1 WHERE id = 1 RETURNING current_seq`).Scan(&seq)
	return seq, err
}

type storedResponse struct {
	Status int    `json:"status"`
	Body   Result `json:"body"`
}

// apply implements idempotent execution of one mutation (§4.2).
func (s *Store) apply(ctx context.Context, opID, reqHash string, fn func(*sql.Tx) (Result, error)) (Result, error) {
	var out Result
	err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		var storedHash, storedJSON string
		err := tx.QueryRowContext(ctx,
			`SELECT request_hash, response_json FROM applied_operations WHERE operation_id = ?`,
			opID).Scan(&storedHash, &storedJSON)
		switch {
		case err == nil:
			if storedHash != reqHash {
				return ErrOperationReuse
			}
			var sr storedResponse
			if err := json.Unmarshal([]byte(storedJSON), &sr); err != nil {
				return fmt.Errorf("decode stored response for %s: %w", opID, err)
			}
			out = sr.Body
			out.HTTPStatus = sr.Status
			out.Replayed = true
			return nil
		case !errors.Is(err, sql.ErrNoRows):
			return err
		}

		res, err := fn(tx)
		if err != nil {
			return err
		}
		body, err := json.Marshal(storedResponse{Status: res.HTTPStatus, Body: res})
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO applied_operations (operation_id, request_hash, response_json, applied_at)
			 VALUES (?, ?, ?, ?)`,
			opID, reqHash, string(body), s.now().UnixMilli()); err != nil {
			return err
		}
		out = res
		return nil
	})
	return out, err
}

// hashRequest fingerprints a mutation by its kind and decoded input, so a
// reused operation ID with a different payload is detected regardless of
// JSON formatting.
// shownImages is the images a note shows: read from a plain note's
// content, as its client listed them for a locked one.
func shownImages(content string, listed []string) []string {
	if locks.IsLocked(content) {
		return listed
	}
	return images.Refs(content)
}

func validateImageIDs(ids []string) error {
	if len(ids) > images.MaxRefs {
		return &ValidationError{"imageIds", fmt.Sprintf("at most %d images", images.MaxRefs)}
	}
	for _, id := range ids {
		if !images.ValidID(id) {
			return &ValidationError{"imageIds", "each must be 64 lowercase hex characters"}
		}
	}
	return nil
}

func hashRequest(kind string, input any) string {
	b, _ := json.Marshal(struct {
		Kind  string `json:"kind"`
		Input any    `json:"input"`
	}{kind, input})
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

func (s *Store) clientTime(t *int64, now int64) int64 {
	if t == nil || *t <= 0 || *t > now+maxClockSkew.Milliseconds() {
		return now
	}
	return *t
}

// validateSpace checks a space id the client named, if any.
func validateSpace(id *string) error {
	if id == nil {
		return nil
	}
	return validateID("spaceId", *id)
}

// resolveSpace is the space a note goes to: the one the client named if it
// still exists (a deleted one means the default), or else def.
func resolveSpace(ctx context.Context, tx *sql.Tx, id *string, def string) (string, error) {
	if id == nil {
		return def, nil
	}
	return spaces.Resolve(ctx, tx, *id)
}

func validateOp(op string) error {
	if !opPattern.MatchString(op) {
		return &ValidationError{"operationId", "required; 1-128 characters of [A-Za-z0-9_.:-]"}
	}
	return nil
}

func validateID(field, id string) error {
	if !idPattern.MatchString(id) {
		return &ValidationError{field, "must be 1-64 characters of [A-Za-z0-9_-]"}
	}
	return nil
}

func validateText(title, content string) error {
	// The cap is on the Markdown: a locked note may hold as much as any
	// other, which its envelope's nonce, tag and base64 make longer.
	maxContent := MaxContentBytes
	if locks.IsLocked(content) {
		maxContent = locks.EnvelopeLen(MaxContentBytes)
	}
	switch {
	case len(title) > MaxTitleBytes:
		return &ValidationError{"title", fmt.Sprintf("exceeds %d bytes", MaxTitleBytes)}
	case len(content) > maxContent:
		return &ValidationError{"content", fmt.Sprintf("exceeds %d bytes", maxContent)}
	case !utf8.ValidString(title) || strings.ContainsRune(title, 0):
		return &ValidationError{"title", "must be valid UTF-8 without NUL characters"}
	case !utf8.ValidString(content) || strings.ContainsRune(content, 0):
		return &ValidationError{"content", "must be valid UTF-8 without NUL characters"}
	case locks.IsLocked(content) && !locks.ValidEnvelope(content):
		return &ValidationError{"content", "is not a valid locked note"}
	}
	return nil
}

// preview shortens indexed plain text for the list: one line per block
// (clients choose how to show the breaks), whitespace collapsed within lines,
// at most previewChars runes. more reports that the text was cut before this.
func preview(s string, more bool) string {
	var lines []string
	for _, l := range strings.Split(s, "\n") {
		if l = strings.Join(strings.Fields(l), " "); l != "" {
			lines = append(lines, l)
		}
	}
	s = strings.Join(lines, "\n")
	r := []rune(s)
	if len(r) <= previewChars && !more {
		return s
	}
	if len(r) > previewChars {
		r = r[:previewChars]
	}
	return strings.TrimSpace(string(r)) + "…"
}

func encodeListCursor(updatedAt int64, id string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(strconv.FormatInt(updatedAt, 10) + ":" + id))
}

func decodeListCursor(c string) (int64, string, error) {
	if c == "" {
		return 0, "", nil
	}
	raw, err := base64.RawURLEncoding.DecodeString(c)
	if err != nil {
		return 0, "", err
	}
	ts, id, ok := strings.Cut(string(raw), ":")
	if !ok || !idPattern.MatchString(id) {
		return 0, "", errors.New("malformed cursor")
	}
	t, err := strconv.ParseInt(ts, 10, 64)
	return t, id, err
}

func boolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}
