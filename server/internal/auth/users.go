package auth

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

// SetupLinkTTL is how long a setup link works.
const SetupLinkTTL = 7 * 24 * time.Hour

// Link is a setup link's secret. The server never sees the URL it's sent
// in: the app that asked builds that from the address it was opened at.
type Link struct {
	Token     string `json:"token"`
	ExpiresAt int64  `json:"expiresAt"`
}

// Invite adds a non-admin account with no password yet, and a link for
// its person to choose one.
func (s *Service) Invite(ctx context.Context, username string) (User, Link, error) {
	var u User
	var l Link
	err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		var err error
		if u, err = s.insert(ctx, tx, username, "", false, false); err != nil {
			return err
		}
		l, err = s.putLink(ctx, tx, u.ID)
		u.LinkExpiresAt = l.ExpiresAt
		return err
	})
	return u, l, err
}

// NewSetupLink replaces an account's link. The account's password keeps
// working until the link is used.
func (s *Service) NewSetupLink(ctx context.Context, actorID, userID int64) (Link, error) {
	if actorID == userID {
		return Link{}, ErrSelf
	}
	var l Link
	err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		if err := exists(ctx, tx, userID); err != nil {
			return err
		}
		var err error
		l, err = s.putLink(ctx, tx, userID)
		return err
	})
	return l, err
}

func (s *Service) putLink(ctx context.Context, tx *sql.Tx, userID int64) (Link, error) {
	now := s.now()
	l := Link{Token: NewToken(), ExpiresAt: now.Add(SetupLinkTTL).UnixMilli()}
	_, err := tx.ExecContext(ctx, `
		INSERT INTO setup_links (user_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?)
		ON CONFLICT (user_id) DO UPDATE SET token_hash = excluded.token_hash,
			expires_at = excluded.expires_at, created_at = excluded.created_at`,
		userID, HashToken(l.Token), l.ExpiresAt, now.UnixMilli())
	return l, err
}

// PeekSetupLink returns whose a link is, so the page it opens can name
// them before they choose a password.
func (s *Service) PeekSetupLink(ctx context.Context, token string) (User, error) {
	return s.linkUser(ctx, s.db.R, token)
}

// UseSetupLink sets the password of the link's account, ends the link and
// signs the account out everywhere.
func (s *Service) UseSetupLink(ctx context.Context, token, password string) (User, error) {
	if err := validatePassword(password); err != nil {
		return User{}, err
	}
	hash, err := HashPassword(password)
	if err != nil {
		return User{}, err
	}
	var u User
	err = s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		if u, err = s.linkUser(ctx, tx, token); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?`,
			hash, s.now().UnixMilli(), u.ID); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM setup_links WHERE user_id = ?`, u.ID); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `DELETE FROM sessions WHERE user_id = ?`, u.ID)
		return err
	})
	u.Pending = false
	return u, err
}

type querier interface {
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

func (s *Service) linkUser(ctx context.Context, q querier, token string) (User, error) {
	var u User
	var hash string
	err := q.QueryRowContext(ctx, `
		SELECT u.id, u.username, u.is_admin, u.created_at, u.password_hash, l.expires_at
		FROM setup_links l JOIN users u ON u.id = l.user_id
		WHERE l.token_hash = ? AND l.expires_at > ?`, HashToken(token), s.now().UnixMilli()).Scan(
		&u.ID, &u.Username, &u.IsAdmin, &u.CreatedAt, &hash, &u.LinkExpiresAt)
	if errors.Is(err, sql.ErrNoRows) {
		return User{}, ErrLinkInvalid
	}
	u.Pending = hash == ""
	return u, err
}

// SetAdmin gives or takes away the right to change server-wide settings.
func (s *Service) SetAdmin(ctx context.Context, actorID, userID int64, admin bool) (User, error) {
	if actorID == userID {
		return User{}, ErrSelf
	}
	err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		if err := exists(ctx, tx, userID); err != nil {
			return err
		}
		if !admin {
			if err := keepAnAdmin(ctx, tx, userID); err != nil {
				return err
			}
		}
		_, err := tx.ExecContext(ctx, `UPDATE users SET is_admin = ?, updated_at = ? WHERE id = ?`,
			admin, s.now().UnixMilli(), userID)
		return err
	})
	if err != nil {
		return User{}, err
	}
	return s.User(ctx, userID)
}

// RemoveUserByID is RemoveUser for an admin acting in an app, who can't
// remove themselves there.
func (s *Service) RemoveUserByID(ctx context.Context, actorID, userID int64) (User, error) {
	if actorID == userID {
		return User{}, ErrSelf
	}
	return s.remove(ctx, `id = ?`, userID)
}

func exists(ctx context.Context, tx *sql.Tx, userID int64) error {
	var one int
	err := tx.QueryRowContext(ctx, `SELECT 1 FROM users WHERE id = ?`, userID).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNoUser
	}
	return err
}
