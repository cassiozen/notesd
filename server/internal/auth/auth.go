// Package auth implements accounts and sessions (§10): a few users, each
// with their own notes database, stored with their sessions in the system
// database. Admins may change server-wide settings; there are no other roles.
//
// Browsers get a "web" session: one secret carried in an HttpOnly cookie with
// a sliding 30-day expiry, plus a CSRF token the web app must echo on
// mutations. Native apps get a "device" session: a short-lived access token
// and a long-lived refresh token, both sent as bearer tokens. Refresh tokens
// are not rotated, so a refresh whose response is lost can simply be retried;
// they are revoked by deleting the session.
package auth

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"
	"unicode/utf8"

	"notes/server/internal/database"
)

const (
	KindWeb    = "web"
	KindDevice = "device"

	WebSessionTTL     = 30 * 24 * time.Hour
	DeviceAccessTTL   = time.Hour
	DeviceRefreshTTL  = 180 * 24 * time.Hour
	touchInterval     = time.Hour // how often a used session's expiry is extended
	maxUsernameLen    = 64
	maxSessionNameLen = 100
)

var (
	ErrInvalidCredentials = errors.New("invalid username or password")
	ErrUnauthenticated    = errors.New("not authenticated")
	ErrOwnerExists        = errors.New("an owner account already exists")
	ErrNoUser             = errors.New("no such user")
	ErrNoOwner            = errors.New("there's no account yet: create the first one, an admin, with create-owner or the app's setup")
	ErrUsernameTaken      = errors.New("that username is taken")
	ErrLastAdmin          = errors.New("can't remove the last admin")
	ErrNotFound           = errors.New("session not found")
)

// InputError reports an invalid username or password.
type InputError struct{ Field, Message string }

func (e *InputError) Error() string { return e.Field + ": " + e.Message }

// Session is an authenticated browser or device, and whose it is.
type Session struct {
	ID         string `json:"id"`
	Kind       string `json:"kind"`
	Name       string `json:"name"`
	CreatedAt  int64  `json:"createdAt"`
	LastUsedAt int64  `json:"lastUsedAt"`
	UserID     int64  `json:"-"`
	Username   string `json:"-"`
	IsAdmin    bool   `json:"-"`
	CSRFToken  string `json:"-"`
	// Renewed is set by Authenticate when it extended a web session's
	// expiry to ExpiresAt, so the cookie should be re-issued.
	Renewed   bool  `json:"-"`
	ExpiresAt int64 `json:"-"`
}

// User is an account.
type User struct {
	ID        int64
	Username  string
	IsAdmin   bool
	CreatedAt int64
}

// Credentials are the secrets issued at login. Fields that do not apply to
// the session kind are empty.
type Credentials struct {
	Session          Session
	AccessToken      string // cookie value for web, bearer for devices
	AccessExpiresAt  int64
	RefreshToken     string // devices only
	RefreshExpiresAt int64  // devices only
	CSRFToken        string // web only
}

// Service manages accounts and sessions in the system database.
type Service struct {
	db  *database.DB
	now func() time.Time
}

// NewService returns an auth service using the system clock.
func NewService(db *database.DB) *Service {
	return &Service{db: db, now: time.Now}
}

// SetClock overrides the clock, for tests.
func (s *Service) SetClock(now func() time.Time) { s.now = now }

// HasUsers reports whether any account has been created.
func (s *Service) HasUsers(ctx context.Context) (bool, error) {
	var n int
	err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM users`).Scan(&n)
	return n > 0, err
}

// CreateOwner creates the first account, an admin, once. First-run setup
// through the API, `notesd create-owner` and NOTESD_OWNER_* at startup all
// use it; the first to arrive wins (§10).
func (s *Service) CreateOwner(ctx context.Context, username, password string) error {
	// Setup is unauthenticated, so refuse before validating and hashing once an
	// account exists; the transaction below still settles a race.
	if has, err := s.HasUsers(ctx); err != nil || has {
		if err == nil {
			err = ErrOwnerExists
		}
		return err
	}
	_, err := s.create(ctx, username, password, true, true)
	return err
}

// AddUser creates another account, once the first exists. Only the command
// line calls it.
func (s *Service) AddUser(ctx context.Context, username, password string, admin bool) (User, error) {
	return s.create(ctx, username, password, admin, false)
}

func (s *Service) create(ctx context.Context, username, password string, admin, first bool) (User, error) {
	username = strings.TrimSpace(username)
	if err := validateUsername(username); err != nil {
		return User{}, err
	}
	if err := validatePassword(password); err != nil {
		return User{}, err
	}
	hash, err := HashPassword(password)
	if err != nil {
		return User{}, err
	}
	u := User{Username: username, IsAdmin: admin, CreatedAt: s.now().UnixMilli()}
	err = s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		var n, taken int
		if err := tx.QueryRowContext(ctx, `SELECT count(*), count(*) FILTER (WHERE username_key = ?) FROM users`,
			usernameKey(username)).Scan(&n, &taken); err != nil {
			return err
		}
		if first && n > 0 {
			return ErrOwnerExists
		}
		// Setup creates the first account, the admin; added ones come after,
		// or the server could be left with no admin at all.
		if !first && n == 0 {
			return ErrNoOwner
		}
		if taken > 0 {
			return ErrUsernameTaken
		}
		return tx.QueryRowContext(ctx, `
			INSERT INTO users (username, username_key, password_hash, is_admin, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?) RETURNING id`,
			username, usernameKey(username), hash, admin, u.CreatedAt, u.CreatedAt).Scan(&u.ID)
	})
	return u, err
}

// ResetPassword sets a user's password without the old one and signs out
// all their sessions. Only the command line calls it: it's how a forgotten
// password is recovered.
func (s *Service) ResetPassword(ctx context.Context, username, password string) error {
	u, _, err := s.userByName(ctx, username)
	if err != nil {
		return err
	}
	return s.setPassword(ctx, u.ID, password, "")
}

// ChangePassword verifies the user's current password, sets a new one and
// signs out their other sessions, all but keepSessionID.
func (s *Service) ChangePassword(ctx context.Context, userID int64, current, next, keepSessionID string) error {
	if err := s.CheckPassword(ctx, userID, current); err != nil {
		return err
	}
	return s.setPassword(ctx, userID, next, keepSessionID)
}

// CheckPassword verifies a user's password, for actions that need it again
// in an open session.
func (s *Service) CheckPassword(ctx context.Context, userID int64, password string) error {
	_, hash, err := s.user(ctx, `id = ?`, userID)
	if err != nil {
		return err
	}
	ok, err := VerifyPassword(password, hash)
	if err != nil {
		return err
	}
	if !ok {
		return ErrInvalidCredentials
	}
	return nil
}

func (s *Service) setPassword(ctx context.Context, userID int64, password, keepSessionID string) error {
	if err := validatePassword(password); err != nil {
		return err
	}
	hash, err := HashPassword(password)
	if err != nil {
		return err
	}
	return s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?`,
			hash, s.now().UnixMilli(), userID)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNoUser
		}
		_, err = tx.ExecContext(ctx, `DELETE FROM sessions WHERE user_id = ? AND id != ?`, userID, keepSessionID)
		return err
	})
}

// User returns an account by id.
func (s *Service) User(ctx context.Context, id int64) (User, error) {
	u, _, err := s.user(ctx, `id = ?`, id)
	return u, err
}

// ListUsers returns every account, oldest first.
func (s *Service) ListUsers(ctx context.Context) ([]User, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT id, username, is_admin, created_at FROM users ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []User
	for rows.Next() {
		var u User
		if err := rows.Scan(&u.ID, &u.Username, &u.IsAdmin, &u.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

// RemoveUser deletes an account and signs out its sessions. Their notes
// database is left for the caller to deal with. The last admin can't be
// removed: someone must be able to change server-wide settings.
func (s *Service) RemoveUser(ctx context.Context, username string) (User, error) {
	var u User
	err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		err := tx.QueryRowContext(ctx, `SELECT id, username, is_admin, created_at FROM users WHERE username_key = ?`,
			usernameKey(username)).Scan(&u.ID, &u.Username, &u.IsAdmin, &u.CreatedAt)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrNoUser
		}
		if err != nil {
			return err
		}
		if u.IsAdmin {
			var admins int
			if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM users WHERE is_admin`).Scan(&admins); err != nil {
				return err
			}
			if admins == 1 {
				return ErrLastAdmin
			}
		}
		_, err = tx.ExecContext(ctx, `DELETE FROM users WHERE id = ?`, u.ID)
		return err
	})
	return u, err
}

// UserByName returns an account by username, ignoring case.
func (s *Service) UserByName(ctx context.Context, username string) (User, error) {
	u, _, err := s.userByName(ctx, username)
	return u, err
}

func (s *Service) userByName(ctx context.Context, username string) (User, string, error) {
	return s.user(ctx, `username_key = ?`, usernameKey(username))
}

func (s *Service) user(ctx context.Context, where string, arg any) (u User, hash string, err error) {
	err = s.db.R.QueryRowContext(ctx,
		`SELECT id, username, is_admin, created_at, password_hash FROM users WHERE `+where, arg).Scan(
		&u.ID, &u.Username, &u.IsAdmin, &u.CreatedAt, &hash)
	if errors.Is(err, sql.ErrNoRows) {
		err = ErrNoUser
	}
	return
}

// Login verifies a user's credentials and opens a session of the given
// kind. name labels the session in the session list (e.g. "Pixel 8").
func (s *Service) Login(ctx context.Context, username, password, kind, name string) (Credentials, error) {
	if kind != KindWeb && kind != KindDevice {
		return Credentials{}, &InputError{"client", `must be "web" or "device"`}
	}
	if len(password) > MaxPasswordLen {
		return Credentials{}, ErrInvalidCredentials
	}
	u, hash, err := s.userByName(ctx, username)
	userOK := err == nil
	if errors.Is(err, ErrNoUser) {
		hash = dummyHash
	} else if err != nil {
		return Credentials{}, err
	}
	ok, err := VerifyPassword(password, hash)
	if err != nil {
		return Credentials{}, err
	}
	if !ok || !userOK {
		return Credentials{}, ErrInvalidCredentials
	}

	now := s.now()
	c := Credentials{
		Session: Session{
			ID: NewToken()[:22], Kind: kind, Name: truncate(name, maxSessionNameLen),
			CreatedAt: now.UnixMilli(), LastUsedAt: now.UnixMilli(),
			UserID: u.ID, Username: u.Username, IsAdmin: u.IsAdmin,
		},
		AccessToken: NewToken(),
	}
	var refreshHash, csrf sql.NullString
	var refreshExp sql.NullInt64
	if kind == KindWeb {
		c.AccessExpiresAt = now.Add(WebSessionTTL).UnixMilli()
		c.CSRFToken = NewToken()
		c.Session.CSRFToken = c.CSRFToken
		csrf = sql.NullString{String: c.CSRFToken, Valid: true}
	} else {
		c.AccessExpiresAt = now.Add(DeviceAccessTTL).UnixMilli()
		c.RefreshToken = NewToken()
		c.RefreshExpiresAt = now.Add(DeviceRefreshTTL).UnixMilli()
		refreshHash = sql.NullString{String: HashToken(c.RefreshToken), Valid: true}
		refreshExp = sql.NullInt64{Int64: c.RefreshExpiresAt, Valid: true}
	}

	err = s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		// Opportunistically clear expired sessions; there are only ever a
		// handful.
		if _, err := tx.ExecContext(ctx, `
			DELETE FROM sessions
			WHERE (kind = 'web' AND access_expires_at < ?)
			   OR (kind = 'device' AND refresh_expires_at < ?)`,
			now.UnixMilli(), now.UnixMilli()); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `
			INSERT INTO sessions (id, user_id, kind, name, access_hash, access_expires_at,
			                      refresh_hash, refresh_expires_at, csrf_token, created_at, last_used_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			c.Session.ID, u.ID, kind, c.Session.Name, HashToken(c.AccessToken), c.AccessExpiresAt,
			refreshHash, refreshExp, csrf, c.Session.CreatedAt, c.Session.LastUsedAt)
		return err
	})
	if err != nil {
		return Credentials{}, err
	}
	return c, nil
}

// Authenticate resolves an access token of the expected kind to its session.
// Web sessions slide: each use (at most hourly) pushes expiry out again.
func (s *Service) Authenticate(ctx context.Context, accessToken, kind string) (Session, error) {
	if accessToken == "" {
		return Session{}, ErrUnauthenticated
	}
	var sess Session
	var exp int64
	var csrf sql.NullString
	err := s.db.R.QueryRowContext(ctx, `
		SELECT s.id, s.kind, s.name, s.created_at, s.last_used_at, s.access_expires_at, s.csrf_token,
		       u.id, u.username, u.is_admin
		FROM sessions s JOIN users u ON u.id = s.user_id
		WHERE s.access_hash = ?`, HashToken(accessToken)).Scan(
		&sess.ID, &sess.Kind, &sess.Name, &sess.CreatedAt, &sess.LastUsedAt, &exp, &csrf,
		&sess.UserID, &sess.Username, &sess.IsAdmin)
	if errors.Is(err, sql.ErrNoRows) {
		return Session{}, ErrUnauthenticated
	}
	if err != nil {
		return Session{}, err
	}
	now := s.now()
	if sess.Kind != kind || exp <= now.UnixMilli() {
		return Session{}, ErrUnauthenticated
	}
	sess.CSRFToken = csrf.String
	sess.ExpiresAt = exp

	if now.UnixMilli()-sess.LastUsedAt >= touchInterval.Milliseconds() {
		sess.LastUsedAt = now.UnixMilli()
		newExp := exp
		if kind == KindWeb {
			newExp = now.Add(WebSessionTTL).UnixMilli()
		}
		// Best effort: a failed touch must not fail the request.
		if _, err := s.db.W.ExecContext(ctx,
			`UPDATE sessions SET last_used_at = ?, access_expires_at = ? WHERE id = ?`,
			sess.LastUsedAt, newExp, sess.ID); err == nil && newExp != exp {
			sess.ExpiresAt = newExp
			sess.Renewed = true
		}
	}
	return sess, nil
}

// Refresh issues a new access token for a device session and extends the
// refresh token's lifetime. The refresh token itself is unchanged.
func (s *Service) Refresh(ctx context.Context, refreshToken string) (Credentials, error) {
	if refreshToken == "" {
		return Credentials{}, ErrUnauthenticated
	}
	now := s.now()
	c := Credentials{
		AccessToken:      NewToken(),
		AccessExpiresAt:  now.Add(DeviceAccessTTL).UnixMilli(),
		RefreshToken:     refreshToken,
		RefreshExpiresAt: now.Add(DeviceRefreshTTL).UnixMilli(),
	}
	err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		err := tx.QueryRowContext(ctx, `
			UPDATE sessions
			SET access_hash = ?, access_expires_at = ?, refresh_expires_at = ?, last_used_at = ?
			WHERE refresh_hash = ? AND kind = 'device' AND refresh_expires_at > ?
			RETURNING id, kind, name, created_at, last_used_at, user_id`,
			HashToken(c.AccessToken), c.AccessExpiresAt, c.RefreshExpiresAt, now.UnixMilli(),
			HashToken(refreshToken), now.UnixMilli()).Scan(
			&c.Session.ID, &c.Session.Kind, &c.Session.Name, &c.Session.CreatedAt, &c.Session.LastUsedAt, &c.Session.UserID)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrUnauthenticated
		}
		if err != nil {
			return err
		}
		return tx.QueryRowContext(ctx, `SELECT username, is_admin FROM users WHERE id = ?`, c.Session.UserID).Scan(
			&c.Session.Username, &c.Session.IsAdmin)
	})
	if err != nil {
		return Credentials{}, err
	}
	return c, nil
}

// Logout ends one of a user's sessions.
func (s *Service) Logout(ctx context.Context, userID int64, sessionID string) error {
	return s.Revoke(ctx, userID, sessionID)
}

// Sessions lists a user's active sessions, most recently used first.
func (s *Service) Sessions(ctx context.Context, userID int64) ([]Session, error) {
	now := s.now().UnixMilli()
	rows, err := s.db.R.QueryContext(ctx, `
		SELECT id, kind, name, created_at, last_used_at FROM sessions
		WHERE user_id = ? AND ((kind = 'web' AND access_expires_at > ?) OR (kind = 'device' AND refresh_expires_at > ?))
		ORDER BY last_used_at DESC`, userID, now, now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Session{}
	for rows.Next() {
		var ss Session
		if err := rows.Scan(&ss.ID, &ss.Kind, &ss.Name, &ss.CreatedAt, &ss.LastUsedAt); err != nil {
			return nil, err
		}
		out = append(out, ss)
	}
	return out, rows.Err()
}

// Revoke deletes one of a user's sessions, signing that browser or device
// out. Another user's session is not found.
func (s *Service) Revoke(ctx context.Context, userID int64, sessionID string) error {
	res, err := s.db.W.ExecContext(ctx, `DELETE FROM sessions WHERE id = ? AND user_id = ?`, sessionID, userID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func validateUsername(u string) error {
	if u == "" || utf8.RuneCountInString(u) > maxUsernameLen || strings.ContainsAny(u, "\x00\r\n\t") {
		return &InputError{"username", "must be 1-64 characters without control characters"}
	}
	return nil
}

func validatePassword(p string) error {
	if utf8.RuneCountInString(p) < MinPasswordLen || len(p) > MaxPasswordLen {
		return &InputError{"password", "must be at least 8 characters (and at most 1024 bytes)"}
	}
	return nil
}

// usernameKey is how usernames are compared: trimmed, ignoring case.
func usernameKey(u string) string { return strings.ToLower(strings.TrimSpace(u)) }

func truncate(s string, n int) string {
	s = strings.TrimSpace(s)
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	return string([]rune(s)[:n])
}
