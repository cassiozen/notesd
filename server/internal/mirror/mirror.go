// Package mirror keeps a folder of Markdown files in step with every user's
// notes, for `notesd serve --mirror-dir`. One way only: the next change
// overwrites edits made to the files.
//
// Writes wake it but never wait for it. Once a user's notes go quiet it
// reads the sync feed (§5.2) from a cursor kept in a manifest beside the
// files, so a restart picks up where it stopped.
package mirror

import (
	"cmp"
	"context"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"

	"notes/server/internal/database"
	"notes/server/internal/mdexport"
)

// Notes are written once a user's changes stop for quiet, and at least
// every maxWait while they don't: a note typed into saves every few seconds.
const (
	quiet   = 10 * time.Second
	maxWait = 2 * time.Minute
)

// Account is a user whose notes are mirrored.
type Account struct {
	ID       int64
	Username string
}

// Config is what a Mirror needs from the server.
type Config struct {
	Dir      string
	Accounts func(context.Context) ([]Account, error)
	Open     func(ctx context.Context, userID int64) (*database.DB, error)
	Log      *slog.Logger
}

// Mirror writes users' notes under Dir/<username>.
type Mirror struct {
	cfg            Config
	quiet, maxWait time.Duration
	now            func() time.Time
	wake           chan struct{}

	mu      sync.Mutex
	pending map[int64]*dirty

	// Only the Run goroutine touches users.
	users map[int64]*user
}

// dirty is a user with changes not yet written.
type dirty struct {
	first time.Time // of the changes not yet written
	due   time.Time
}

// New returns a Mirror; Run starts it.
func New(cfg Config) *Mirror {
	return &Mirror{
		cfg:     cfg,
		quiet:   quiet,
		maxWait: maxWait,
		now:     time.Now,
		wake:    make(chan struct{}, 1),
		pending: map[int64]*dirty{},
		users:   map[int64]*user{},
	}
}

// Notify records that a user's notes changed. It never blocks.
func (m *Mirror) Notify(userID int64) {
	now := m.now()
	m.mu.Lock()
	d := m.pending[userID]
	if d == nil {
		d = &dirty{first: now}
		m.pending[userID] = d
	}
	d.due = minTime(now.Add(m.quiet), d.first.Add(m.maxWait))
	m.mu.Unlock()
	select {
	case m.wake <- struct{}{}:
	default:
	}
}

// retryLater schedules a user whose write failed, unless a change has
// already scheduled them.
func (m *Mirror) retryLater(userID int64) {
	now := m.now()
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.pending[userID] == nil {
		m.pending[userID] = &dirty{first: now, due: now.Add(m.maxWait)}
	}
}

// next is the user to write first and when, if any.
func (m *Mirror) next() (userID int64, due time.Time, ok bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for id, d := range m.pending {
		if !ok || d.due.Before(due) {
			userID, due, ok = id, d.due, true
		}
	}
	return userID, due, ok
}

// Run writes users' changes as they come due, one user at a time, until
// ctx ends. It first catches up on what changed while the server was down.
func (m *Mirror) Run(ctx context.Context) {
	accounts, err := m.cfg.Accounts(ctx)
	if err != nil && ctx.Err() == nil {
		m.cfg.Log.Error("markdown mirror", "err", err)
	}
	for _, a := range accounts {
		m.Notify(a.ID)
	}
	timer := time.NewTimer(time.Hour)
	defer timer.Stop()
	for {
		id, due, ok := m.next()
		wait := time.Hour
		if ok {
			wait = max(due.Sub(m.now()), 0)
		}
		timer.Reset(wait)
		select {
		case <-ctx.Done():
			return
		case <-m.wake:
			continue
		case <-timer.C:
		}
		if !ok || m.now().Before(due) {
			continue
		}
		m.mu.Lock()
		delete(m.pending, id)
		m.mu.Unlock()
		if err := m.flush(ctx, id); err != nil && ctx.Err() == nil {
			m.cfg.Log.Error("markdown mirror", "user_id", id, "err", err)
			m.retryLater(id)
		}
	}
}

// Check refuses a mirror directory that holds dataDir, or a user's folder
// that has files but isn't a mirror: the mirror would mix with them.
func (m *Mirror) Check(ctx context.Context, dataDir string) error {
	dir, err := filepath.Abs(m.cfg.Dir)
	if err != nil {
		return err
	}
	data, err := filepath.Abs(dataDir)
	if err != nil {
		return err
	}
	if rel, err := filepath.Rel(dir, data); err == nil && !strings.HasPrefix(rel, "..") {
		return fmt.Errorf("--mirror-dir %s holds the data directory; choose a folder outside it", m.cfg.Dir)
	}
	accounts, err := m.cfg.Accounts(ctx)
	if err != nil {
		return err
	}
	for id, name := range folderNames(accounts) {
		if _, err := m.user(id, name); err != nil {
			return err
		}
	}
	return nil
}

// flush writes one user's changes.
func (m *Mirror) flush(ctx context.Context, userID int64) error {
	accounts, err := m.cfg.Accounts(ctx)
	if err != nil {
		return err
	}
	name, ok := folderNames(accounts)[userID]
	if !ok {
		return nil // removed
	}
	u, err := m.user(userID, name)
	if err != nil {
		return err
	}
	db, err := m.cfg.Open(ctx, userID)
	if err != nil {
		return err
	}
	return u.flush(ctx, db, m.cfg.Log)
}

// user returns a user's mirror state, loading its manifest the first time.
func (m *Mirror) user(userID int64, folder string) (*user, error) {
	dir := filepath.Join(m.cfg.Dir, folder)
	if u := m.users[userID]; u != nil && u.dir == dir {
		return u, nil
	}
	u, err := loadUser(dir)
	if err != nil {
		return nil, err
	}
	m.users[userID] = u
	return u, nil
}

// folderNames names each account's folder after its username. Usernames
// that make the same folder name get their id, oldest account unchanged.
func folderNames(accounts []Account) map[int64]string {
	accounts = slices.Clone(accounts)
	slices.SortFunc(accounts, func(a, b Account) int { return cmp.Compare(a.ID, b.ID) })
	taken := map[string]bool{}
	names := map[int64]string{}
	for _, a := range accounts {
		name := mdexport.FileName(a.Username)
		if taken[mdexport.NameKey(name)] {
			name = fmt.Sprintf("%s (%d)", name, a.ID)
		}
		taken[mdexport.NameKey(name)] = true
		names[a.ID] = name
	}
	return names
}

// exists reports whether path is taken by anything, on a filesystem that
// may ignore case.
func exists(path string) (bool, error) {
	_, err := os.Lstat(path)
	if errors.Is(err, fs.ErrNotExist) {
		return false, nil
	}
	return err == nil, err
}

func minTime(a, b time.Time) time.Time {
	if b.Before(a) {
		return b
	}
	return a
}
