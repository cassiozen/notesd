package auth

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"image/jpeg"
	"strings"
	"unicode"
	"unicode/utf8"
)

const (
	maxDisplayNameLen = 64
	// AvatarSize is the largest photo side the server takes. Apps crop to a
	// square and scale down to it first.
	AvatarSize = 512
	// AvatarMaxBytes caps an upload; a 512 px JPEG is far smaller.
	AvatarMaxBytes = 1 << 20
)

// ErrNoAvatar is a photo that doesn't exist, or that the viewer may not see.
var ErrNoAvatar = errors.New("no such photo")

// CanSeeProfile is who sees whose name and photo: your own, and an admin
// everyone's, as People lists them. Everyone else sees no one else.
func CanSeeProfile(viewer Session, userID int64) bool {
	return viewer.UserID == userID || viewer.IsAdmin
}

// SetDisplayName changes the name an account shows instead of its username;
// ” goes back to the username.
func (s *Service) SetDisplayName(ctx context.Context, userID int64, name string) (User, error) {
	name, err := cleanDisplayName(name)
	if err != nil {
		return User{}, err
	}
	return s.updateProfile(ctx, userID, func(tx *sql.Tx) error {
		return s.writeDisplayName(ctx, tx, userID, name)
	})
}

// SetAvatar replaces an account's photo with upload, a square JPEG. Only its
// pixels are kept: re-encoding drops EXIF (a phone's location) and any trailing data.
func (s *Service) SetAvatar(ctx context.Context, userID int64, upload []byte) (User, error) {
	data, err := reencodeAvatar(upload)
	if err != nil {
		return User{}, err
	}
	sum := sha256.Sum256(data)
	version := hex.EncodeToString(sum[:8])
	return s.updateProfile(ctx, userID, func(tx *sql.Tx) error {
		now := s.now().UnixMilli()
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO avatars (user_id, data, updated_at) VALUES (?, ?, ?)
			ON CONFLICT (user_id) DO UPDATE SET data = excluded.data, updated_at = excluded.updated_at`,
			userID, data, now); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `UPDATE users SET avatar = ?, updated_at = ? WHERE id = ?`, version, now, userID)
		return err
	})
}

// RemoveAvatar takes an account's photo away; apps show its initial again.
func (s *Service) RemoveAvatar(ctx context.Context, userID int64) (User, error) {
	return s.updateProfile(ctx, userID, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `DELETE FROM avatars WHERE user_id = ?`, userID); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `UPDATE users SET avatar = '', updated_at = ? WHERE id = ?`, s.now().UnixMilli(), userID)
		return err
	})
}

// Avatar returns an account's photo and its version, if viewer may see it.
// Not allowed is ErrNoAvatar too, so asking can't tell whether an account exists.
func (s *Service) Avatar(ctx context.Context, viewer Session, userID int64) (data []byte, version string, err error) {
	if !CanSeeProfile(viewer, userID) {
		return nil, "", ErrNoAvatar
	}
	err = s.db.R.QueryRowContext(ctx, `
		SELECT a.data, u.avatar FROM avatars a JOIN users u ON u.id = a.user_id WHERE a.user_id = ?`,
		userID).Scan(&data, &version)
	if errors.Is(err, sql.ErrNoRows) {
		err = ErrNoAvatar
	}
	return data, version, err
}

func (s *Service) updateProfile(ctx context.Context, userID int64, write func(tx *sql.Tx) error) (User, error) {
	var u User
	err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		if err := write(tx); err != nil {
			return err
		}
		var err error
		u, _, err = scanUser(ctx, tx, `id = ?`, userID)
		return err
	})
	return u, err
}

func (s *Service) writeDisplayName(ctx context.Context, tx *sql.Tx, userID int64, name string) error {
	res, err := tx.ExecContext(ctx, `UPDATE users SET display_name = ?, updated_at = ? WHERE id = ?`,
		name, s.now().UnixMilli(), userID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNoUser
	}
	return nil
}

func cleanDisplayName(name string) (string, error) {
	name = strings.TrimSpace(name)
	if utf8.RuneCountInString(name) > maxDisplayNameLen || strings.IndexFunc(name, unicode.IsControl) >= 0 {
		return "", &InputError{"displayName", "must be at most 64 characters, on one line"}
	}
	return name, nil
}

func reencodeAvatar(upload []byte) ([]byte, error) {
	bad := &InputError{"avatar", "must be a square JPEG of at most 512 pixels a side"}
	// The size first, from the header: a huge image is refused before it's decoded.
	cfg, err := jpeg.DecodeConfig(bytes.NewReader(upload))
	if err != nil || cfg.Width != cfg.Height || cfg.Width > AvatarSize {
		return nil, bad
	}
	img, err := jpeg.Decode(bytes.NewReader(upload))
	if err != nil {
		return nil, bad
	}
	var out bytes.Buffer
	if err := jpeg.Encode(&out, img, &jpeg.Options{Quality: 88}); err != nil {
		return nil, err
	}
	return out.Bytes(), nil
}
