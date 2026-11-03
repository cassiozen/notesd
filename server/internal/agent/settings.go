package agent

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"sync"
	"time"

	// Time zones resolve the same on a server without system time zone data,
	// such as a container from scratch.
	_ "time/tzdata"
)

const (
	keyTimeZone       = "time_zone"
	keyTimeZoneSource = "time_zone_source"
)

// Where a time zone came from.
const (
	TimeZoneDefault = "default" // none known: UTC
	TimeZoneAuto    = "auto"    // the first device that said
	TimeZoneUser    = "user"    // chosen in Settings
)

// AccountSettings are the account-wide settings agents use.
type AccountSettings struct {
	TimeZone       string `json:"timeZone"` // IANA name, such as Europe/Lisbon
	TimeZoneSource string `json:"timeZoneSource"`
}

// Location is the time zone to compute "today" in.
func (a AccountSettings) Location() *time.Location {
	loc, err := time.LoadLocation(a.TimeZone)
	if err != nil {
		return time.UTC
	}
	return loc
}

// tzCache remembers that a time zone is known, so a device saying its own on
// every request costs nothing once one is.
type tzCache struct {
	mu    sync.Mutex
	known bool
}

// Settings returns the account settings.
func (s *Store) Settings(ctx context.Context) (AccountSettings, error) {
	out := AccountSettings{TimeZone: "UTC", TimeZoneSource: TimeZoneDefault}
	rows, err := s.db.R.QueryContext(ctx, `SELECT key, value FROM account_settings WHERE key IN (?, ?)`, keyTimeZone, keyTimeZoneSource)
	if err != nil {
		return out, err
	}
	defer rows.Close()
	var tz, source string
	for rows.Next() {
		var k, v string
		if err := rows.Scan(&k, &v); err != nil {
			return out, err
		}
		switch k {
		case keyTimeZone:
			tz = v
		case keyTimeZoneSource:
			source = v
		}
	}
	if tz != "" {
		out.TimeZone, out.TimeZoneSource = tz, source
	}
	return out, rows.Err()
}

// SetTimeZone stores the time zone a user chose.
func (s *Store) SetTimeZone(ctx context.Context, name string) (AccountSettings, error) {
	name, err := checkTimeZone(name)
	if err != nil {
		return AccountSettings{}, err
	}
	if err := s.putTimeZone(ctx, name, TimeZoneUser, false); err != nil {
		return AccountSettings{}, err
	}
	return s.Settings(ctx)
}

// NoteDeviceTimeZone records a device's time zone, unless one is already
// known: the first device to connect sets it, and Settings changes it.
// Names that aren't time zones are ignored.
func (s *Store) NoteDeviceTimeZone(ctx context.Context, name string) error {
	s.tz.mu.Lock()
	defer s.tz.mu.Unlock()
	if s.tz.known {
		return nil
	}
	name, err := checkTimeZone(name)
	if err != nil {
		return nil
	}
	if err := s.putTimeZone(ctx, name, TimeZoneAuto, true); err != nil {
		return err
	}
	s.tz.known = true
	return nil
}

func (s *Store) putTimeZone(ctx context.Context, name, source string, onlyIfUnset bool) error {
	return s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		if onlyIfUnset {
			var v string
			err := tx.QueryRowContext(ctx, `SELECT value FROM account_settings WHERE key = ?`, keyTimeZone).Scan(&v)
			if err == nil {
				return nil
			}
			if !errors.Is(err, sql.ErrNoRows) {
				return err
			}
		}
		for k, v := range map[string]string{keyTimeZone: name, keyTimeZoneSource: source} {
			if _, err := tx.ExecContext(ctx, `INSERT INTO account_settings (key, value) VALUES (?, ?)
				ON CONFLICT(key) DO UPDATE SET value = excluded.value`, k, v); err != nil {
				return err
			}
		}
		return nil
	})
}

func checkTimeZone(name string) (string, error) {
	name = strings.TrimSpace(name)
	// "Local" is the server's own zone, which says nothing about the user.
	if name == "" || name == "Local" || len(name) > 64 {
		return "", &InputError{Field: "timeZone", Message: "choose a time zone"}
	}
	if _, err := time.LoadLocation(name); err != nil {
		return "", &InputError{Field: "timeZone", Message: "isn't a time zone"}
	}
	return name, nil
}
