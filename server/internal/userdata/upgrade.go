package userdata

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io/fs"
	"net/url"
	"os"
	"path/filepath"
	"strings"

	"notes/server/internal/database"
)

// legacyName is the single database of a data directory from before there
// were several users: notes, the owner, sessions and settings in one file.
const legacyName = "notes.db"

// Upgrade moves a data directory from the single-user layout, once. The
// owner becomes user 1, an admin; their sessions (so signed-in devices stay
// signed in) and the settings move to system.db; then notes.db is renamed to
// users/1.db, where its notes migration drops the moved tables.
//
// It is safe to interrupt: the copy is one transaction, and a later run
// finishes a rename it didn't get to. Nothing else may have notes.db open.
func Upgrade(ctx context.Context, dataDir string, system *database.DB) error {
	legacy := filepath.Join(dataDir, legacyName)
	if _, err := os.Stat(legacy); errors.Is(err, fs.ErrNotExist) {
		return nil
	} else if err != nil {
		return err
	}
	// Before anything is written: a conflict must leave both files as they are.
	dest := Path(dataDir, 1)
	if _, err := os.Stat(dest); err == nil {
		return fmt.Errorf("both %s and %s exist; keep one", legacy, dest)
	}
	var users int
	if err := system.R.QueryRowContext(ctx, `SELECT count(*) FROM users`).Scan(&users); err != nil {
		return err
	}
	if users == 0 {
		if err := importAccounts(ctx, legacy, system); err != nil {
			return fmt.Errorf("move accounts out of %s: %w", legacy, err)
		}
	} else {
		// Accounts already moved (or made since): notes.db is user 1's, if
		// user 1 exists.
		var one bool
		if err := system.R.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM users WHERE id = 1)`).Scan(&one); err != nil {
			return err
		}
		if !one {
			return fmt.Errorf("%s is left from an older notesd, but its owner (user 1) is gone; move or delete it", legacy)
		}
	}
	// Even with no owner (never set up), the notes stay: the first user
	// created gets id 1 and with it this file.
	if err := os.MkdirAll(filepath.Dir(dest), 0o700); err != nil {
		return err
	}
	// The WAL first: a database without its WAL loses the changes in it,
	// while a WAL renamed ahead of its database is found once that follows.
	for _, suffix := range []string{"-wal", "-shm"} {
		if err := os.Rename(legacy+suffix, dest+suffix); err != nil && !errors.Is(err, fs.ErrNotExist) {
			return err
		}
	}
	if err := os.Rename(legacy, dest); err != nil {
		return err
	}
	return scrub(ctx, dest)
}

// scrub migrates the moved file, which drops the tables now in system.db,
// and folds its WAL into it. A WAL left by a server that didn't shut down
// cleanly can still hold those tables' old pages (password and session
// hashes, the Resend key); the checkpoint overwrites them with the zeroed
// pages secure_delete wrote.
func scrub(ctx context.Context, path string) error {
	db, err := database.Open(ctx, path)
	if err != nil {
		return fmt.Errorf("open %s: %w", path, err)
	}
	defer db.Close()
	if err := db.ScrubWAL(ctx); err != nil {
		return fmt.Errorf("scrub %s: %w", path, err)
	}
	return nil
}

// importAccounts copies the owner, sessions and settings from the legacy
// database, as it is: it isn't migrated first, which would drop them.
func importAccounts(ctx context.Context, legacy string, system *database.DB) error {
	src, err := sql.Open("sqlite", "file:"+filepath.ToSlash(legacy)+"?"+url.Values{"_pragma": {"busy_timeout(5000)"}}.Encode())
	if err != nil {
		return err
	}
	defer src.Close()
	var version int
	if err := src.QueryRowContext(ctx, `PRAGMA user_version`).Scan(&version); err != nil {
		return err
	}
	if version == 0 {
		return nil // never migrated: no tables at all
	}
	var hasSettings bool
	if err := src.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM sqlite_schema WHERE name = 'settings')`).Scan(&hasSettings); err != nil {
		return err
	}

	type owner struct {
		username, hash   string
		created, updated int64
	}
	var o owner
	err = src.QueryRowContext(ctx, `SELECT username, password_hash, created_at, updated_at FROM users WHERE id = 1`).Scan(
		&o.username, &o.hash, &o.created, &o.updated)
	if errors.Is(err, sql.ErrNoRows) {
		return nil // never set up: no account to move
	}
	if err != nil {
		return err
	}

	sessions, err := src.QueryContext(ctx, `
		SELECT id, kind, name, access_hash, access_expires_at, refresh_hash, refresh_expires_at,
		       csrf_token, created_at, last_used_at
		FROM sessions`)
	if err != nil {
		return err
	}
	type session struct {
		id, kind, name, access string
		accessExp              int64
		refresh, csrf          sql.NullString
		refreshExp             sql.NullInt64
		created, used          int64
	}
	var ss []session
	for sessions.Next() {
		var s session
		if err := sessions.Scan(&s.id, &s.kind, &s.name, &s.access, &s.accessExp, &s.refresh, &s.refreshExp,
			&s.csrf, &s.created, &s.used); err != nil {
			sessions.Close()
			return err
		}
		ss = append(ss, s)
	}
	sessions.Close()
	if err := sessions.Err(); err != nil {
		return err
	}

	settings := map[string]string{}
	if hasSettings {
		rows, err := src.QueryContext(ctx, `SELECT key, value FROM settings`)
		if err != nil {
			return err
		}
		for rows.Next() {
			var k, v string
			if err := rows.Scan(&k, &v); err != nil {
				rows.Close()
				return err
			}
			settings[k] = v
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
	}

	return system.WriteTx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO users (id, username, username_key, password_hash, is_admin, created_at, updated_at)
			VALUES (1, ?, ?, ?, 1, ?, ?)`,
			o.username, strings.ToLower(strings.TrimSpace(o.username)), o.hash, o.created, o.updated); err != nil {
			return err
		}
		for _, s := range ss {
			if _, err := tx.ExecContext(ctx, `
				INSERT INTO sessions (id, user_id, kind, name, access_hash, access_expires_at,
				                      refresh_hash, refresh_expires_at, csrf_token, created_at, last_used_at)
				VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
				s.id, s.kind, s.name, s.access, s.accessExp, s.refresh, s.refreshExp, s.csrf, s.created, s.used); err != nil {
				return err
			}
		}
		for k, v := range settings {
			if _, err := tx.ExecContext(ctx, `INSERT INTO settings (key, value) VALUES (?, ?)`, k, v); err != nil {
				return err
			}
		}
		return nil
	})
}
