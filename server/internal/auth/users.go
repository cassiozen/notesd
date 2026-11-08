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
// its person to choose one. displayName is the admin's suggestion, which
// the person may change when they use the link.
func (s *Service) Invite(ctx context.Context, username, displayName string) (User, Link, error) {
	var u User
	var l Link
	err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		var err error
		if u, err = s.insert(ctx, tx, username, displayName, "", false, false); err != nil {
			return err
		}
		l, err = s.putLink(ctx, tx, u.ID)
		u.LinkExpiresAt = l.ExpiresAt
		return err
	})
	return u, l, err
}

// NewSetupLink replaces an account's link. The account's password keeps
// working until the link is used. The account comes back as it is now, so
// an app with a stale list still tells an invitation from a reset.
func (s *Service) NewSetupLink(ctx context.Context, actorID, userID int64) (User, Link, error) {
	if actorID == userID {
		return User{}, Link{}, ErrSelf
	}
	var u User
	var l Link
	err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		var err error
		if u, _, err = scanUser(ctx, tx, `id = ?`, userID); err != nil {
			return err
		}
		l, err = s.putLink(ctx, tx, userID)
		u.LinkExpiresAt = l.ExpiresAt
		return err
	})
	return u, l, err
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
// signs the account out everywhere. An invited account also takes
// displayName when given: the name its person settled on beside their
// password. A reset leaves the name alone.
func (s *Service) UseSetupLink(ctx context.Context, token, password string, displayName *string) (User, error) {
	if err := validatePassword(password); err != nil {
		return User{}, err
	}
	var name string
	if displayName != nil {
		var err error
		if name, err = cleanDisplayName(*displayName); err != nil {
			return User{}, err
		}
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
		if u.Pending && displayName != nil {
			if err = s.writeDisplayName(ctx, tx, u.ID, name); err != nil {
				return err
			}
			u.DisplayName = name
		}
		return s.writePassword(ctx, tx, u.ID, hash, "")
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
		SELECT u.id, u.username, u.display_name, u.avatar, u.is_admin, u.created_at, u.password_hash, l.expires_at
		FROM setup_links l JOIN users u ON u.id = l.user_id
		WHERE l.token_hash = ? AND l.expires_at > ?`, HashToken(token), s.now().UnixMilli()).Scan(
		&u.ID, &u.Username, &u.DisplayName, &u.Avatar, &u.IsAdmin, &u.CreatedAt, &hash, &u.LinkExpiresAt)
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
	var u User
	err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		if !admin {
			if err := keepAnAdmin(ctx, tx, userID); err != nil {
				return err
			}
		}
		err := tx.QueryRowContext(ctx, `
			UPDATE users SET is_admin = ?, updated_at = ? WHERE id = ?
			RETURNING id, username, display_name, avatar, is_admin, created_at, password_hash = ''`,
			admin, s.now().UnixMilli(), userID).Scan(&u.ID, &u.Username, &u.DisplayName, &u.Avatar, &u.IsAdmin, &u.CreatedAt, &u.Pending)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrNoUser
		}
		return err
	})
	return u, err
}

// RemoveUserByID is RemoveUser for an admin acting in an app, who can't
// remove themselves there.
func (s *Service) RemoveUserByID(ctx context.Context, actorID, userID int64) (User, error) {
	if actorID == userID {
		return User{}, ErrSelf
	}
	return s.remove(ctx, `id = ?`, userID)
}
