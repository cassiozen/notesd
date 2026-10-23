// Package userdata opens each user's notes database (architecture §10):
// users/<id>.db in the data directory, beside system.db. A user's notes,
// search index, sync state, spaces, locked-notes keyring and images are all
// in their file, so one user's requests can never reach another's data.
package userdata

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"sync"

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
}

// New wraps an open notes database.
func New(db *database.DB) *Data {
	return &Data{DB: db, Notes: notes.NewStore(db), Locks: locks.NewStore(db), Images: images.NewStore(db), Spaces: spaces.NewStore(db)}
}

// Path is where a user's notes database lives.
func Path(dataDir string, userID int64) string {
	return filepath.Join(dataDir, "users", fmt.Sprintf("%d.db", userID))
}

// Registry opens users' databases on first use and keeps them open. There
// are only ever a few users, so nothing is closed until Close.
type Registry struct {
	dir  string
	mu   sync.Mutex
	open map[int64]*Data
}

// NewRegistry returns a registry for the users in dataDir.
func NewRegistry(dataDir string) *Registry {
	return &Registry{dir: dataDir, open: map[int64]*Data{}}
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
	r.open[userID] = d
	return d, nil
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
	return errors.Join(errs...)
}
