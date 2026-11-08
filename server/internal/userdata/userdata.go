// Package userdata opens each user's notes database (architecture §10):
// users/<id>.db in the data directory, beside system.db. A user's notes,
// search index, sync state, spaces, locked-notes keyring and images are all
// in their file, so one user's requests can never reach another's data.
// A shared space's notes are in a file of their own, spaces/<id>.db (§7.9).
package userdata

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sync"

	"notes/server/internal/agent"
	"notes/server/internal/database"
	"notes/server/internal/images"
	"notes/server/internal/locks"
	"notes/server/internal/notes"
	"notes/server/internal/search"
	"notes/server/internal/spaces"
)

// Data is one user's notes database and the stores over it.
type Data struct {
	DB     *database.DB
	Notes  *notes.Store
	Locks  *locks.Store
	Images *images.Store
	Spaces *spaces.Store
	// Agent is nil in a shared space's file: agents run in an account's own spaces only (§7.11).
	Agent *agent.Store
}

// New wraps an account's open notes database.
func New(db *database.DB) *Data {
	d := newShared(db)
	d.Agent = agent.NewStore(db)
	return d
}

// newShared wraps a shared space's open notes database.
func newShared(db *database.DB) *Data {
	d := &Data{DB: db, Notes: notes.NewStore(db), Locks: locks.NewStore(db), Images: images.NewStore(db), Spaces: spaces.NewStore(db)}
	d.Notes.SetExposed(d.Spaces.Exposed)
	return d
}

// Path is where a user's notes database lives.
func Path(dataDir string, userID int64) string {
	return filepath.Join(dataDir, "users", fmt.Sprintf("%d.db", userID))
}

// SpacePath is where a shared space's notes database lives.
func SpacePath(dataDir, spaceID string) string {
	return filepath.Join(dataDir, "spaces", spaceID+".db")
}

// Registry opens users' and shared spaces' databases on first use and keeps
// them open. There are only ever a few, so nothing is closed until Close.
type Registry struct {
	dir    string
	mu     sync.Mutex
	open   map[int64]*Data
	spaces map[string]*Data
	// Read on every commit, so a feature installed after a file opened still hears it.
	hooks         sync.RWMutex
	onChange      []func(userID int64)
	onSpaceChange []func(spaceID string)
	onOpenUser    []func(userID int64, d *Data)
	onOpenSpace   []func(spaceID string, d *Data)
}

// NewRegistry returns a registry for the users and shared spaces in dataDir.
func NewRegistry(dataDir string) *Registry {
	return &Registry{dir: dataDir, open: map[int64]*Data{}, spaces: map[string]*Data{}}
}

// OnOpen adds user and space, which set up users' and spaces' stores, those
// open already and those that open later: what depends on more than one file.
// Either may be nil.
func (r *Registry) OnOpen(user func(userID int64, d *Data), space func(spaceID string, d *Data)) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if user != nil {
		r.onOpenUser = append(r.onOpenUser, user)
		for id, d := range r.open {
			user(id, d)
		}
	}
	if space != nil {
		r.onOpenSpace = append(r.onOpenSpace, space)
		for id, d := range r.spaces {
			space(id, d)
		}
	}
}

// OnSpaceChange is OnChange for shared spaces' files.
func (r *Registry) OnSpaceChange(fn func(spaceID string)) {
	r.hooks.Lock()
	defer r.hooks.Unlock()
	r.onSpaceChange = append(r.onSpaceChange, fn)
}

// OnChange has fn called after a write to a user's notes, spaces or images
// commits. fn must not block. Each feature adds its own.
func (r *Registry) OnChange(fn func(userID int64)) {
	r.hooks.Lock()
	defer r.hooks.Unlock()
	r.onChange = append(r.onChange, fn)
}

func (r *Registry) changed(userID int64) {
	r.hooks.RLock()
	defer r.hooks.RUnlock()
	for _, fn := range r.onChange {
		fn(userID)
	}
}

func (r *Registry) spaceChanged(spaceID string) {
	r.hooks.RLock()
	defer r.hooks.RUnlock()
	for _, fn := range r.onSpaceChange {
		fn(spaceID)
	}
}

// Open returns a user's data, opening (and creating, for a new user) their
// database the first time: migrations, then any pending rebuild of derived
// data. A user added from the command line while the server runs is picked
// up here.
func (r *Registry) Open(ctx context.Context, userID int64) (*Data, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if d, ok := r.open[userID]; ok {
		return d, nil
	}
	path := Path(r.dir, userID)
	db, err := database.Open(ctx, path)
	if err != nil {
		return nil, fmt.Errorf("open database %s: %w", path, err)
	}
	if _, err := search.RebuildIfNeeded(ctx, db); err != nil {
		db.Close()
		return nil, fmt.Errorf("rebuild search index of %s: %w", path, err)
	}
	d := New(db)
	d.onCommit(func() { r.changed(userID) })
	for _, fn := range r.onOpenUser {
		fn(userID, d)
	}
	r.open[userID] = d
	return d, nil
}

// OpenSpace returns a shared space's data, opening its database the first
// time, and creating it if it doesn't exist: callers check the space is
// shared first.
func (r *Registry) OpenSpace(ctx context.Context, spaceID string) (*Data, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.openSpace(ctx, spaceID)
}

// openSpace is OpenSpace with r.mu held.
func (r *Registry) openSpace(ctx context.Context, spaceID string) (*Data, error) {
	if d, ok := r.spaces[spaceID]; ok {
		return d, nil
	}
	path := SpacePath(r.dir, spaceID)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, err
	}
	db, err := database.Open(ctx, path)
	if err != nil {
		return nil, fmt.Errorf("open database %s: %w", path, err)
	}
	if _, err := search.RebuildIfNeeded(ctx, db); err != nil {
		db.Close()
		return nil, fmt.Errorf("rebuild search index of %s: %w", path, err)
	}
	if err := dropDefaultSpace(ctx, db); err != nil {
		db.Close()
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	d := newShared(db)
	d.Notes.ServeShared(spaceID)
	d.onCommit(func() { r.spaceChanged(spaceID) })
	for _, fn := range r.onOpenSpace {
		fn(spaceID, d)
	}
	r.spaces[spaceID] = d
	return d, nil
}

// dropDefaultSpace removes the default space the notes migrations give every file from a shared space's,
// which holds only its own (§7.9): its "Notes" would otherwise take that name from the shared space.
func dropDefaultSpace(ctx context.Context, db *database.DB) error {
	return db.WriteTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `DELETE FROM spaces WHERE id = ?`, spaces.Default)
		return err
	})
}

// OpenSpaceIfExists is OpenSpace for a background job: nil, without creating one, when the file is gone
// (the space was deleted since it was listed). Checked and opened under the lock RemoveSpace takes, so a
// removal can't land between the two and leave a new, empty file.
func (r *Registry) OpenSpaceIfExists(ctx context.Context, spaceID string) (*Data, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if d, open := r.spaces[spaceID]; open {
		return d, nil
	}
	if _, err := os.Stat(SpacePath(r.dir, spaceID)); errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	} else if err != nil {
		return nil, err
	}
	return r.openSpace(ctx, spaceID)
}

// RemoveSpace closes a shared space's database, if open, and deletes its
// files: one that was never finished, or a deleted space.
func (r *Registry) RemoveSpace(spaceID string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if d, ok := r.spaces[spaceID]; ok {
		delete(r.spaces, spaceID)
		if err := d.DB.Close(); err != nil {
			return err
		}
	}
	path := SpacePath(r.dir, spaceID)
	for _, suffix := range []string{"-wal", "-shm", ""} {
		if err := os.Remove(path + suffix); err != nil && !errors.Is(err, fs.ErrNotExist) {
			return err
		}
	}
	return nil
}

// EachSpace calls fn with every shared space's database open so far.
func (r *Registry) EachSpace(fn func(spaceID string, d *Data)) {
	r.mu.Lock()
	open := make(map[string]*Data, len(r.spaces))
	for id, d := range r.spaces {
		open[id] = d
	}
	r.mu.Unlock()
	for id, d := range open {
		fn(id, d)
	}
}

func (d *Data) onCommit(fn func()) {
	d.Notes.OnCommit(fn)
	d.Spaces.OnCommit(fn)
	d.Images.OnCommit(fn)
}

// Each calls fn with every database open so far.
func (r *Registry) Each(fn func(userID int64, d *Data)) {
	r.mu.Lock()
	open := make(map[int64]*Data, len(r.open))
	for id, d := range r.open {
		open[id] = d
	}
	r.mu.Unlock()
	for id, d := range open {
		fn(id, d)
	}
}

// Close closes every open database.
func (r *Registry) Close() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	var errs []error
	for id, d := range r.open {
		errs = append(errs, d.DB.Close())
		delete(r.open, id)
	}
	for id, d := range r.spaces {
		errs = append(errs, d.DB.Close())
		delete(r.spaces, id)
	}
	return errors.Join(errs...)
}
