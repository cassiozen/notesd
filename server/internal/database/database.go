// Package database opens the SQLite database, applies migrations and provides
// transaction helpers.
//
// SQLite allows one writer at a time. Rather than letting concurrent writers
// collide on SQLITE_BUSY, all writes go through a pool with a single
// connection whose transactions start with BEGIN IMMEDIATE, so they queue in
// Go. Reads use a separate pool and, in WAL mode, never block or are blocked
// by the writer.
package database

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io/fs"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"

	"notes/server/migrations"

	_ "modernc.org/sqlite" // registers the "sqlite" driver
)

// DB holds the write and read connection pools for one database file.
type DB struct {
	W    *sql.DB // single connection; every transaction is BEGIN IMMEDIATE
	R    *sql.DB // read-only pool
	Path string
}

// Open opens (creating if needed) a user's notes database at path,
// configures it and applies pending migrations.
func Open(ctx context.Context, path string) (*DB, error) {
	return open(ctx, path, migrations.Notes)
}

// OpenSystem opens (creating if needed) the system database at path:
// users, sessions and server-wide settings.
func OpenSystem(ctx context.Context, path string) (*DB, error) {
	return open(ctx, path, migrations.System)
}

func open(ctx context.Context, path string, schema fs.FS) (*DB, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("create data directory: %w", err)
	}

	w, err := sql.Open("sqlite", dsn(path, false))
	if err != nil {
		return nil, err
	}
	w.SetMaxOpenConns(1)
	w.SetMaxIdleConns(1)
	w.SetConnMaxLifetime(0)
	w.SetConnMaxIdleTime(0)

	if err := migrate(ctx, w, schema); err != nil {
		w.Close()
		return nil, err
	}

	r, err := sql.Open("sqlite", dsn(path, true))
	if err != nil {
		w.Close()
		return nil, err
	}
	n := runtime.NumCPU()
	if n < 2 {
		n = 2
	}
	if n > 8 {
		n = 8
	}
	r.SetMaxOpenConns(n)
	r.SetMaxIdleConns(2)

	db := &DB{W: w, R: r, Path: path}
	if err := db.Ping(ctx); err != nil {
		db.Close()
		return nil, err
	}
	return db, nil
}

func dsn(path string, readOnly bool) string {
	q := url.Values{}
	q.Add("_pragma", "busy_timeout(5000)")
	q.Add("_pragma", "journal_mode(WAL)")
	q.Add("_pragma", "synchronous(NORMAL)") // durable across process crashes in WAL mode
	q.Add("_pragma", "foreign_keys(ON)")
	q.Add("_pragma", "temp_store(MEMORY)")
	// Deleted content is zeroed, so text from before a note was locked (or
	// deleted) doesn't linger in the file (§10.1). The WAL still holds the
	// old page images until ScrubWAL or a checkpoint overwrites them.
	q.Add("_pragma", "secure_delete(ON)")
	if readOnly {
		q.Add("_pragma", "query_only(ON)")
	} else {
		q.Set("_txlock", "immediate")
	}
	return "file:" + filepath.ToSlash(path) + "?" + q.Encode()
}

// Ping checks that both pools can execute a query.
func (db *DB) Ping(ctx context.Context) error {
	var one int
	if err := db.R.QueryRowContext(ctx, "SELECT 1").Scan(&one); err != nil {
		return fmt.Errorf("read pool: %w", err)
	}
	if err := db.W.PingContext(ctx); err != nil {
		return fmt.Errorf("write pool: %w", err)
	}
	return nil
}

// Close optimizes query planner statistics and closes both pools. Closing the
// last connection checkpoints the WAL.
func (db *DB) Close() error {
	_, _ = db.W.Exec("PRAGMA optimize")
	return errors.Join(db.R.Close(), db.W.Close())
}

// ScrubWAL checkpoints the WAL into the database file and truncates it, so
// the page images it held from before a write (a note's text before it was
// locked) are gone from the -wal file too. Readers still using those pages
// make it give up; the next checkpoint finishes the job.
func (db *DB) ScrubWAL(ctx context.Context) error {
	var busy, frames, done int
	if err := db.W.QueryRowContext(ctx, "PRAGMA wal_checkpoint(TRUNCATE)").Scan(&busy, &frames, &done); err != nil {
		return err
	}
	if busy != 0 {
		return errors.New("wal checkpoint: readers still busy")
	}
	return nil
}

// WriteTx runs fn in a write transaction, committing if it returns nil.
func (db *DB) WriteTx(ctx context.Context, fn func(*sql.Tx) error) error {
	return runTx(ctx, db.W, nil, fn)
}

// ReadTx runs fn in a read transaction on the query_only pool. The
// transaction is DEFERRED, so its snapshot is fixed by its first read and
// every later statement sees the same committed state.
func (db *DB) ReadTx(ctx context.Context, fn func(*sql.Tx) error) error {
	return runTx(ctx, db.R, nil, fn)
}

func runTx(ctx context.Context, pool *sql.DB, opts *sql.TxOptions, fn func(*sql.Tx) error) (err error) {
	tx, err := pool.BeginTx(ctx, opts)
	if err != nil {
		return err
	}
	defer func() {
		if p := recover(); p != nil {
			_ = tx.Rollback()
			panic(p)
		}
		if err != nil {
			_ = tx.Rollback()
		}
	}()
	if err = fn(tx); err != nil {
		return err
	}
	return tx.Commit()
}

// IntegrityCheck runs PRAGMA integrity_check and returns the problems found,
// or nil if the database is consistent.
func (db *DB) IntegrityCheck(ctx context.Context) ([]string, error) {
	rows, err := db.R.QueryContext(ctx, "PRAGMA integrity_check")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var problems []string
	for rows.Next() {
		var s string
		if err := rows.Scan(&s); err != nil {
			return nil, err
		}
		if s != "ok" {
			problems = append(problems, s)
		}
	}
	return problems, rows.Err()
}

type migration struct {
	version int
	name    string
	sql     string
}

func loadMigrations(schema fs.FS) ([]migration, error) {
	entries, err := fs.ReadDir(schema, ".")
	if err != nil {
		return nil, err
	}
	var out []migration
	for _, e := range entries {
		name := e.Name()
		if e.IsDir() || !strings.HasSuffix(name, ".sql") {
			continue
		}
		prefix, _, ok := strings.Cut(name, "_")
		v, err := strconv.Atoi(prefix)
		if !ok || err != nil || v <= 0 {
			return nil, fmt.Errorf("migration %q: name must start with a positive number and '_'", name)
		}
		body, err := fs.ReadFile(schema, name)
		if err != nil {
			return nil, err
		}
		out = append(out, migration{version: v, name: name, sql: string(body)})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].version < out[j].version })
	for i, m := range out {
		if m.version != i+1 {
			return nil, fmt.Errorf("migration %q: expected version %d", m.name, i+1)
		}
	}
	return out, nil
}

// LatestVersion is the notes schema version this binary migrates to.
func LatestVersion() int {
	ms, err := loadMigrations(migrations.Notes)
	if err != nil || len(ms) == 0 {
		return 0
	}
	return ms[len(ms)-1].version
}

func migrate(ctx context.Context, w *sql.DB, schema fs.FS) error {
	ms, err := loadMigrations(schema)
	if err != nil {
		return err
	}
	var current int
	if err := w.QueryRowContext(ctx, "PRAGMA user_version").Scan(&current); err != nil {
		return fmt.Errorf("read schema version: %w", err)
	}
	if latest := len(ms); current > latest {
		return fmt.Errorf("database schema version %d is newer than this binary supports (%d); upgrade notesd", current, latest)
	}
	for _, m := range ms[current:] {
		err := runTx(ctx, w, nil, func(tx *sql.Tx) error {
			if _, err := tx.ExecContext(ctx, m.sql); err != nil {
				return err
			}
			_, err := tx.ExecContext(ctx, fmt.Sprintf("PRAGMA user_version = %d", m.version))
			return err
		})
		if err != nil {
			return fmt.Errorf("apply migration %s: %w", m.name, err)
		}
	}
	return nil
}
