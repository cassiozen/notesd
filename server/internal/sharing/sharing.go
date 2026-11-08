// Package sharing shares spaces between accounts (architecture §7.9): membership and invitations in
// system.db, and moves of notes between files, which no single file's store can do on its own.
package sharing

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"notes/server/internal/database"
	"notes/server/internal/notes"
	"notes/server/internal/spaces"
	"notes/server/internal/userdata"
)

// Errors.
var (
	ErrNotOwner      = errors.New("only the space's owner can do this")
	ErrNotFound      = errors.New("no such shared space")
	ErrNoInvite      = errors.New("no such invitation")
	ErrNoUser        = errors.New("no such account")
	ErrOwnerLeaving  = errors.New("the owner can't leave: hand the space to a member, or remove every member")
	ErrAlreadyMember = errors.New("already a member of this space")
	ErrSelf          = errors.New("you're already in this space")
	ErrSameFile      = errors.New("that space is in the same file: move the note with an update")
	ErrIDTaken       = errors.New("a shared space has this id")
)

// Service shares spaces.
type Service struct {
	system *database.DB
	files  *userdata.Registry
	now    func() time.Time
	// One move at a time: two in opposite directions would each hold one
	// file's writer while waiting for the other's.
	moving   sync.Mutex
	onChange func(userID int64)
}

// New returns a Service over the system database and every notes file.
func New(system *database.DB, files *userdata.Registry) *Service {
	return &Service{system: system, files: files, now: time.Now}
}

// SetClock overrides the clock, for tests.
func (s *Service) SetClock(now func() time.Time) { s.now = now }

// Invitation is one waiting for an account.
type Invitation struct {
	ID        string        `json:"id"`
	SpaceID   string        `json:"spaceId"`
	SpaceName string        `json:"spaceName"`
	SpaceIcon string        `json:"spaceIcon"`
	From      spaces.Person `json:"from"`
	CreatedAt int64         `json:"createdAt"`
}

// ready leaves out a space while it's still being shared: its notes are
// still in the owner's file.
const ready = `NOT EXISTS (SELECT 1 FROM space_moves m WHERE m.kind = 'space' AND m.space_id = ss.id)`

// Role is userID's role in a shared space: "" if they aren't in it.
func (s *Service) Role(ctx context.Context, userID int64, spaceID string) (string, error) {
	var owner int64
	err := s.system.R.QueryRowContext(ctx, `SELECT ss.owner_id FROM shared_spaces ss
		JOIN space_members m ON m.space_id = ss.id AND m.user_id = ?
		WHERE ss.id = ? AND `+ready, userID, spaceID).Scan(&owner)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return "", nil
	case err != nil:
		return "", err
	case owner == userID:
		return spaces.RoleOwner, nil
	}
	return spaces.RoleMember, nil
}

// Open returns the file of a shared space userID is in, or ErrNotFound.
func (s *Service) Open(ctx context.Context, userID int64, spaceID string) (*userdata.Data, error) {
	role, err := s.Role(ctx, userID, spaceID)
	if err != nil {
		return nil, err
	}
	if role == "" {
		return nil, ErrNotFound
	}
	return s.files.OpenSpace(ctx, spaceID)
}

// SpaceIDs lists the shared spaces userID is in.
func (s *Service) SpaceIDs(ctx context.Context, userID int64) ([]string, error) {
	rows, err := s.system.R.QueryContext(ctx, `SELECT ss.id FROM shared_spaces ss
		JOIN space_members m ON m.space_id = ss.id AND m.user_id = ? WHERE `+ready+` ORDER BY ss.id`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

// List returns the shared spaces userID is in, as they see them, without
// traits (the caller sets those with the account's own spaces).
func (s *Service) List(ctx context.Context, userID int64) ([]spaces.Space, error) {
	ids, err := s.SpaceIDs(ctx, userID)
	if err != nil {
		return nil, err
	}
	out := make([]spaces.Space, 0, len(ids))
	for _, id := range ids {
		sp, err := s.Space(ctx, userID, id)
		if err != nil {
			return nil, err
		}
		out = append(out, sp)
	}
	return out, nil
}

// Space returns a shared space userID is in, as they see it, without traits.
func (s *Service) Space(ctx context.Context, userID int64, spaceID string) (spaces.Space, error) {
	sp := spaces.Space{ID: spaceID, Traits: []spaces.Trait{}}
	var owner int64
	err := s.system.R.QueryRowContext(ctx, `SELECT ss.name, ss.icon, ss.created_at, ss.owner_id FROM shared_spaces ss
		JOIN space_members m ON m.space_id = ss.id AND m.user_id = ? WHERE ss.id = ? AND `+ready,
		userID, spaceID).Scan(&sp.Name, &sp.Icon, &sp.CreatedAt, &owner)
	if errors.Is(err, sql.ErrNoRows) {
		return sp, ErrNotFound
	} else if err != nil {
		return sp, err
	}
	shared := &spaces.Shared{Role: spaces.RoleMember}
	if owner == userID {
		shared.Role = spaces.RoleOwner
	}
	if shared.Members, err = s.members(ctx, spaceID); err != nil {
		return sp, err
	}
	if shared.Role == spaces.RoleOwner {
		if shared.Invites, err = s.invitees(ctx, spaceID); err != nil {
			return sp, err
		}
	}
	sp.Shared = shared
	d, err := s.files.OpenSpace(ctx, spaceID)
	if err != nil {
		return sp, err
	}
	err = d.DB.R.QueryRowContext(ctx, `SELECT count(*) FROM notes WHERE deleted_at IS NULL`).Scan(&sp.NoteCount)
	return sp, err
}

// members lists a shared space's members, the owner first, then by when
// they joined.
func (s *Service) members(ctx context.Context, spaceID string) ([]spaces.Person, error) {
	return people(s.system.R.QueryContext(ctx, `SELECT u.id, u.username FROM space_members m
		JOIN users u ON u.id = m.user_id JOIN shared_spaces ss ON ss.id = m.space_id
		WHERE m.space_id = ? ORDER BY u.id != ss.owner_id, m.joined_at, u.id`, spaceID))
}

func (s *Service) invitees(ctx context.Context, spaceID string) ([]spaces.Invitee, error) {
	rows, err := s.system.R.QueryContext(ctx, `SELECT i.id, u.id, u.username FROM space_invites i
		JOIN users u ON u.id = i.user_id WHERE i.space_id = ? ORDER BY i.created_at, i.id`, spaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []spaces.Invitee{}
	for rows.Next() {
		var in spaces.Invitee
		if err := rows.Scan(&in.ID, &in.UserID, &in.Username); err != nil {
			return nil, err
		}
		out = append(out, in)
	}
	return out, rows.Err()
}

// People lists every account, to invite: id and username, as any account
// may see them.
func (s *Service) People(ctx context.Context) ([]spaces.Person, error) {
	return people(s.system.R.QueryContext(ctx, `SELECT id, username FROM users ORDER BY username_key`))
}

func people(rows *sql.Rows, err error) ([]spaces.Person, error) {
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []spaces.Person{}
	for rows.Next() {
		var p spaces.Person
		if err := rows.Scan(&p.ID, &p.Username); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// Elsewhere reports whether spaceID's notes are in another file userID reaches: a shared space
// they're in, or one of their own (when writing to a shared space's file).
func (s *Service) Elsewhere(ctx context.Context, userID int64, spaceID string) (bool, error) {
	if role, err := s.Role(ctx, userID, spaceID); err != nil || role != "" {
		return role != "", err
	}
	if userID <= 0 {
		return false, nil
	}
	d, err := s.files.Open(ctx, userID)
	if err != nil {
		return false, err
	}
	return d.Notes.HasSpace(ctx, spaceID)
}

// Invite invites inviteeID into a space ownerID owns, sharing it first if
// it's still one of their own (§7.9): the first invitation is what shares
// a space.
func (s *Service) Invite(ctx context.Context, ownerID int64, spaceID string, inviteeID int64) (spaces.Invitee, error) {
	if inviteeID == ownerID {
		return spaces.Invitee{}, ErrSelf
	}
	var username string
	err := s.system.R.QueryRowContext(ctx, `SELECT username FROM users WHERE id = ?`, inviteeID).Scan(&username)
	if errors.Is(err, sql.ErrNoRows) {
		return spaces.Invitee{}, ErrNoUser
	} else if err != nil {
		return spaces.Invitee{}, err
	}
	role, err := s.Role(ctx, ownerID, spaceID)
	if err != nil {
		return spaces.Invitee{}, err
	}
	switch role {
	case spaces.RoleMember:
		return spaces.Invitee{}, ErrNotOwner
	case "":
		if err := s.share(ctx, ownerID, spaceID); err != nil {
			return spaces.Invitee{}, err
		}
	}
	in := spaces.Invitee{ID: newID(), UserID: inviteeID, Username: username}
	err = s.system.WriteTx(ctx, func(tx *sql.Tx) error {
		var member bool
		if err := tx.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM space_members WHERE space_id = ? AND user_id = ?)`,
			spaceID, inviteeID).Scan(&member); err != nil {
			return err
		}
		if member {
			return ErrAlreadyMember
		}
		// Inviting again keeps the first invitation.
		if err := tx.QueryRowContext(ctx, `INSERT INTO space_invites (id, space_id, user_id, invited_by, created_at)
			VALUES (?, ?, ?, ?, ?) ON CONFLICT (space_id, user_id) DO UPDATE SET id = id RETURNING id`,
			in.ID, spaceID, inviteeID, ownerID, s.now().UnixMilli()).Scan(&in.ID); err != nil {
			return err
		}
		return nil
	})
	if err != nil {
		return spaces.Invitee{}, err
	}
	s.changed(ctx, spaceID)
	return in, nil
}

// share turns one of ownerID's spaces into a shared space: its own file,
// with its notes and their images, and ownerID its owner (§7.9).
func (s *Service) share(ctx context.Context, ownerID int64, spaceID string) error {
	if spaceID == spaces.Default {
		return spaces.ErrDefault
	}
	own, err := s.files.Open(ctx, ownerID)
	if err != nil {
		return err
	}
	if ok, err := own.Notes.HasSpace(ctx, spaceID); err != nil {
		return err
	} else if !ok {
		return ErrNotFound
	}
	s.moving.Lock()
	defer s.moving.Unlock()
	moveID := newID()
	err = s.system.WriteTx(ctx, func(tx *sql.Tx) error {
		// Ids are the client's: one already shared (by someone who knows it) must never be touched.
		if taken, err := taken(ctx, tx, spaceID); err != nil {
			return err
		} else if taken {
			return ErrIDTaken
		}
		_, err := tx.ExecContext(ctx, `INSERT INTO space_moves (id, kind, space_id, source, dest, created_at)
			VALUES (?, 'space', ?, ?, ?, ?)`, moveID, spaceID, userRef(ownerID), spaceRef(spaceID), s.now().UnixMilli())
		return err
	})
	if err != nil {
		return err
	}
	// Left by an attempt that didn't get as far as moving anything.
	if err := s.files.RemoveSpace(spaceID); err != nil {
		return err
	}
	dest, err := s.files.OpenSpace(ctx, spaceID)
	if err != nil {
		return s.undoShare(ctx, moveID, spaceID, err)
	}
	err = own.Notes.ShareOut(ctx, spaceID, ownerID, func(row notes.SpaceRow, carried []notes.Carried) error {
		if err := dest.Notes.ShareIn(ctx, row, carried); err != nil {
			return err
		}
		return s.system.WriteTx(ctx, func(tx *sql.Tx) error {
			now := s.now().UnixMilli()
			if _, err := tx.ExecContext(ctx, `INSERT INTO shared_spaces (id, name, icon, owner_id, created_at)
				VALUES (?, ?, ?, ?, ?)`, spaceID, row.Name, row.Icon, ownerID, row.CreatedAt); err != nil {
				return err
			}
			_, err := tx.ExecContext(ctx, `INSERT INTO space_members (space_id, user_id, joined_at) VALUES (?, ?, ?)`,
				spaceID, ownerID, now)
			return err
		})
	})
	if err != nil {
		return s.undoShare(ctx, moveID, spaceID, err)
	}
	return s.clearMove(ctx, moveID)
}

// Taken reports whether a shared space has this id, or is being made with it.
func (s *Service) Taken(ctx context.Context, spaceID string) (bool, error) {
	var ok bool
	err := s.system.R.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM shared_spaces WHERE id = ?)
		OR EXISTS (SELECT 1 FROM space_moves WHERE kind = 'space' AND space_id = ?)`, spaceID, spaceID).Scan(&ok)
	return ok, err
}

func taken(ctx context.Context, tx *sql.Tx, spaceID string) (bool, error) {
	var ok bool
	err := tx.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM shared_spaces WHERE id = ?)
		OR EXISTS (SELECT 1 FROM space_moves WHERE kind = 'space' AND space_id = ?)`, spaceID, spaceID).Scan(&ok)
	return ok, err
}

// undoShare drops a share that failed before the owner's file let go of the
// space: the half-made file and anything recorded of it.
func (s *Service) undoShare(ctx context.Context, moveID, spaceID string, cause error) error {
	err := s.system.WriteTx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `DELETE FROM shared_spaces WHERE id = ?`, spaceID); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `DELETE FROM space_moves WHERE id = ?`, moveID)
		return err
	})
	return errors.Join(cause, err, s.files.RemoveSpace(spaceID))
}

func (s *Service) clearMove(ctx context.Context, moveID string) error {
	return s.system.WriteTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `DELETE FROM space_moves WHERE id = ?`, moveID)
		return err
	})
}

// Source is the file a note is moved out of, as a request reached it.
type Source struct {
	Data *userdata.Data
	// SpaceID is the shared space whose file it is; "" for the account's own.
	SpaceID string
}

// Move moves a note from src to a space in another file userID reaches
// (§7.9): one of their own, or a shared space they're in.
func (s *Service) Move(ctx context.Context, userID int64, src Source, in notes.MoveInput) (notes.Result, error) {
	in.UpdatedBy = userID
	dest, destRef, err := s.destination(ctx, userID, in.SpaceID)
	if err != nil {
		return notes.Result{}, err
	}
	srcRef := userRef(userID)
	if src.SpaceID != "" {
		srcRef = spaceRef(src.SpaceID)
	}
	if destRef == srcRef {
		return notes.Result{}, ErrSameFile
	}
	s.moving.Lock()
	defer s.moving.Unlock()
	moveID := newID()
	err = s.system.WriteTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `INSERT INTO space_moves (id, kind, space_id, note_id, source, dest, created_at)
			VALUES (?, 'note', ?, ?, ?, ?, ?)`, moveID, in.SpaceID, in.ID, srcRef, destRef, s.now().UnixMilli())
		return err
	})
	if err != nil {
		return notes.Result{}, err
	}
	res, err := src.Data.Notes.MoveOut(ctx, in, func(c notes.Carried) (notes.Result, error) {
		return dest.Notes.MoveIn(ctx, c, in.SpaceID)
	})
	if err != nil {
		// Logged until startup only if the destination kept a copy.
		if live, lerr := dest.Notes.Live(ctx, in.ID); lerr != nil || live {
			return res, errors.Join(err, lerr)
		}
	}
	return res, errors.Join(err, s.clearMove(ctx, moveID))
}

// destination is the file spaceID's notes are in, for userID.
func (s *Service) destination(ctx context.Context, userID int64, spaceID string) (*userdata.Data, string, error) {
	role, err := s.Role(ctx, userID, spaceID)
	if err != nil {
		return nil, "", err
	}
	if role != "" {
		d, err := s.files.OpenSpace(ctx, spaceID)
		return d, spaceRef(spaceID), err
	}
	own, err := s.files.Open(ctx, userID)
	if err != nil {
		return nil, "", err
	}
	if ok, err := own.Notes.HasSpace(ctx, spaceID); err != nil {
		return nil, "", err
	} else if !ok {
		return nil, "", &notes.ValidationError{Field: "spaceId", Message: "no such space"}
	}
	return own, userRef(userID), nil
}

// Recover finishes or undoes the moves a stop cut short (§7.9). It runs at
// startup, before requests, so nothing has changed either file since.
func (s *Service) Recover(ctx context.Context) error {
	type move struct{ id, kind, spaceID, noteID, source, dest string }
	rows, err := s.system.R.QueryContext(ctx,
		`SELECT id, kind, space_id, coalesce(note_id, ''), source, dest FROM space_moves ORDER BY created_at, id`)
	if err != nil {
		return err
	}
	var moves []move
	for rows.Next() {
		var m move
		if err := rows.Scan(&m.id, &m.kind, &m.spaceID, &m.noteID, &m.source, &m.dest); err != nil {
			rows.Close()
			return err
		}
		moves = append(moves, m)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, m := range moves {
		src, err := s.openRef(ctx, m.source)
		if err != nil {
			return fmt.Errorf("move %s: %w", m.id, err)
		}
		if m.kind == "space" {
			if err := s.recoverShare(ctx, m.id, m.spaceID, src); err != nil {
				return fmt.Errorf("share of space %s: %w", m.spaceID, err)
			}
			continue
		}
		dest, err := s.openRef(ctx, m.dest)
		if err != nil {
			return fmt.Errorf("move %s: %w", m.id, err)
		}
		if live, err := dest.Notes.Live(ctx, m.noteID); err != nil {
			return err
		} else if live {
			if err := src.Notes.FinishMoveOut(ctx, m.noteID, m.spaceID); err != nil {
				return fmt.Errorf("move of note %s: %w", m.noteID, err)
			}
		}
		if err := s.clearMove(ctx, m.id); err != nil {
			return err
		}
	}
	return nil
}

func (s *Service) recoverShare(ctx context.Context, moveID, spaceID string, own *userdata.Data) error {
	var shared bool
	if err := s.system.R.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM shared_spaces WHERE id = ?)`,
		spaceID).Scan(&shared); err != nil {
		return err
	}
	// shared_spaces is written after the space's file has every note, and
	// before the owner's file lets go of them.
	if !shared {
		return s.undoShare(ctx, moveID, spaceID, nil)
	}
	if ok, err := own.Notes.HasSpace(ctx, spaceID); err != nil {
		return err
	} else if ok {
		if err := own.Notes.FinishShareOut(ctx, spaceID); err != nil {
			return err
		}
	}
	return s.clearMove(ctx, moveID)
}

func (s *Service) openRef(ctx context.Context, ref string) (*userdata.Data, error) {
	kind, id, _ := strings.Cut(ref, ":")
	if kind == "space" {
		return s.files.OpenSpace(ctx, id)
	}
	var userID int64
	if _, err := fmt.Sscan(id, &userID); err != nil {
		return nil, fmt.Errorf("bad file %q", ref)
	}
	return s.files.Open(ctx, userID)
}

// Invitations lists the invitations waiting for userID.
func (s *Service) Invitations(ctx context.Context, userID int64) ([]Invitation, error) {
	rows, err := s.system.R.QueryContext(ctx, `SELECT i.id, ss.id, ss.name, ss.icon, u.id, u.username, i.created_at
		FROM space_invites i JOIN shared_spaces ss ON ss.id = i.space_id JOIN users u ON u.id = i.invited_by
		WHERE i.user_id = ? ORDER BY i.created_at, i.id`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Invitation{}
	for rows.Next() {
		var in Invitation
		if err := rows.Scan(&in.ID, &in.SpaceID, &in.SpaceName, &in.SpaceIcon, &in.From.ID, &in.From.Username,
			&in.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, in)
	}
	return out, rows.Err()
}

// Accept has userID join the space an invitation of theirs is for.
func (s *Service) Accept(ctx context.Context, userID int64, inviteID string) (spaces.Space, error) {
	var spaceID string
	err := s.system.WriteTx(ctx, func(tx *sql.Tx) error {
		err := tx.QueryRowContext(ctx, `DELETE FROM space_invites WHERE id = ? AND user_id = ? RETURNING space_id`,
			inviteID, userID).Scan(&spaceID)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrNoInvite
		} else if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO space_members (space_id, user_id, joined_at) VALUES (?, ?, ?)
			ON CONFLICT DO NOTHING`, spaceID, userID, s.now().UnixMilli())
		return err
	})
	if err != nil {
		return spaces.Space{}, err
	}
	s.changed(ctx, spaceID)
	return s.Space(ctx, userID, spaceID)
}

// Decline drops an invitation of userID's.
func (s *Service) Decline(ctx context.Context, userID int64, inviteID string) error {
	return s.dropInvite(ctx, `id = ? AND user_id = ?`, inviteID, userID)
}

// CancelInvite drops an invitation into a space ownerID owns.
func (s *Service) CancelInvite(ctx context.Context, ownerID int64, spaceID, inviteID string) error {
	if err := s.mustOwn(ctx, ownerID, spaceID); err != nil {
		return err
	}
	return s.dropInvite(ctx, `id = ? AND space_id = ?`, inviteID, spaceID)
}

func (s *Service) dropInvite(ctx context.Context, where string, args ...any) error {
	var spaceID string
	err := s.system.WriteTx(ctx, func(tx *sql.Tx) error {
		err := tx.QueryRowContext(ctx, `DELETE FROM space_invites WHERE `+where+` RETURNING space_id`, args...).Scan(&spaceID)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrNoInvite
		}
		return err
	})
	if err == nil {
		s.changed(ctx, spaceID)
	}
	return err
}

// RemoveMember removes userID from a shared space: by its owner, or by
// themselves to leave. The owner can't leave (ErrOwnerLeaving).
func (s *Service) RemoveMember(ctx context.Context, actorID int64, spaceID string, userID int64) error {
	role, err := s.Role(ctx, actorID, spaceID)
	if err != nil {
		return err
	}
	switch {
	case role == "":
		return ErrNotFound
	case actorID == userID && role == spaces.RoleOwner:
		return ErrOwnerLeaving
	case actorID != userID && role != spaces.RoleOwner:
		return ErrNotOwner
	}
	err = s.system.WriteTx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `DELETE FROM space_members WHERE space_id = ? AND user_id = ?`, spaceID, userID)
		if err != nil {
			return err
		}
		if n, err := res.RowsAffected(); err != nil {
			return err
		} else if n == 0 {
			return ErrNoUser
		}
		return nil
	})
	if err == nil {
		s.changed(ctx, spaceID)
	}
	return err
}

// Transfer hands a shared space ownerID owns to another of its members.
func (s *Service) Transfer(ctx context.Context, ownerID int64, spaceID string, toID int64) error {
	if err := s.mustOwn(ctx, ownerID, spaceID); err != nil {
		return err
	}
	if role, err := s.Role(ctx, toID, spaceID); err != nil {
		return err
	} else if role == "" {
		return ErrNoUser
	}
	err := s.system.WriteTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `UPDATE shared_spaces SET owner_id = ? WHERE id = ?`, toID, spaceID)
		return err
	})
	if err == nil {
		s.changed(ctx, spaceID)
	}
	return err
}

// Rename changes a shared space's name, and its icon unless icon is nil.
func (s *Service) Rename(ctx context.Context, ownerID int64, spaceID, name string, icon *string) (spaces.Space, error) {
	if err := s.mustOwn(ctx, ownerID, spaceID); err != nil {
		return spaces.Space{}, err
	}
	name, err := spaces.NormalizeName(name)
	if err != nil {
		return spaces.Space{}, err
	}
	if icon != nil && !spaces.ValidIcon(*icon) {
		return spaces.Space{}, &spaces.InputError{Field: "icon", Message: "must be at most 64 characters of [a-z0-9-]"}
	}
	err = s.system.WriteTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `UPDATE shared_spaces SET name = ?, icon = coalesce(?, icon) WHERE id = ?`,
			name, icon, spaceID)
		return err
	})
	if err != nil {
		return spaces.Space{}, err
	}
	s.changed(ctx, spaceID)
	return s.Space(ctx, ownerID, spaceID)
}

// Delete deletes an empty shared space ownerID owns, file and all.
func (s *Service) Delete(ctx context.Context, ownerID int64, spaceID string) error {
	if err := s.mustOwn(ctx, ownerID, spaceID); err != nil {
		return err
	}
	d, err := s.files.OpenSpace(ctx, spaceID)
	if err != nil {
		return err
	}
	var n int
	if err := d.DB.R.QueryRowContext(ctx, `SELECT count(*) FROM notes WHERE deleted_at IS NULL`).Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return &spaces.NotEmptyError{Count: n}
	}
	members, err := s.memberIDs(ctx, spaceID)
	if err != nil {
		return err
	}
	err = s.system.WriteTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `DELETE FROM shared_spaces WHERE id = ?`, spaceID)
		return err
	})
	if err != nil {
		return err
	}
	s.notify(members)
	return s.files.RemoveSpace(spaceID)
}

// HandOff gives each space userID owns to the member who joined first, in the transaction that
// removes the account; a space no one else is in keeps its file for the host (§7.9).
func HandOff(ctx context.Context, tx *sql.Tx, userID int64) error {
	rows, err := tx.QueryContext(ctx, `SELECT id FROM shared_spaces WHERE owner_id = ?`, userID)
	if err != nil {
		return err
	}
	var owned []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return err
		}
		owned = append(owned, id)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, id := range owned {
		var next int64
		err := tx.QueryRowContext(ctx, `SELECT user_id FROM space_members WHERE space_id = ? AND user_id != ?
			ORDER BY joined_at, user_id LIMIT 1`, id, userID).Scan(&next)
		switch {
		case errors.Is(err, sql.ErrNoRows):
			_, err = tx.ExecContext(ctx, `DELETE FROM shared_spaces WHERE id = ?`, id)
		case err == nil:
			_, err = tx.ExecContext(ctx, `UPDATE shared_spaces SET owner_id = ? WHERE id = ?`, next, id)
		}
		if err != nil {
			return err
		}
	}
	return nil
}

func (s *Service) mustOwn(ctx context.Context, userID int64, spaceID string) error {
	role, err := s.Role(ctx, userID, spaceID)
	switch {
	case err != nil:
		return err
	case role == "":
		return ErrNotFound
	case role != spaces.RoleOwner:
		return ErrNotOwner
	}
	return nil
}

func (s *Service) memberIDs(ctx context.Context, spaceID string) ([]int64, error) {
	list, err := s.members(ctx, spaceID)
	ids := make([]int64, len(list))
	for i, p := range list {
		ids[i] = p.ID
	}
	return ids, err
}

// OnChange has fn told whose spaces changed (membership, names), so their Markdown mirrors catch up.
func (s *Service) OnChange(fn func(userID int64)) { s.onChange = fn }

func (s *Service) changed(ctx context.Context, spaceID string) {
	if s.onChange == nil {
		return
	}
	ids, err := s.memberIDs(ctx, spaceID)
	if err == nil {
		s.notify(ids)
	}
}

func (s *Service) notify(ids []int64) {
	if s.onChange != nil {
		for _, id := range ids {
			s.onChange(id)
		}
	}
}

// Members returns the accounts in a shared space, for its change hooks.
func (s *Service) Members(ctx context.Context, spaceID string) ([]int64, error) {
	return s.memberIDs(ctx, spaceID)
}

func userRef(id int64) string   { return fmt.Sprintf("user:%d", id) }
func spaceRef(id string) string { return "space:" + id }

func newID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	return hex.EncodeToString(b[:])
}

// SortSpaces orders spaces as GET /spaces lists them: the default first, then by name ignoring case.
func SortSpaces(list []spaces.Space) {
	sort.SliceStable(list, func(i, j int) bool {
		if (list[i].ID == spaces.Default) != (list[j].ID == spaces.Default) {
			return list[i].ID == spaces.Default
		}
		return spaces.Key(list[i].Name) < spaces.Key(list[j].Name)
	})
}

// Install sets up every notes file as it opens: writes naming a space in
// another file are refused, and accounts get the shared trait.
func (s *Service) Install() {
	s.files.OnOpen(func(userID int64, d *userdata.Data) {
		d.Notes.SetElsewhere(s.Elsewhere)
		d.Spaces.AddTrait("shared", s.Trait(userID))
	}, func(spaceID string, d *userdata.Data) {
		d.Notes.SetElsewhere(s.Elsewhere)
	})
}
