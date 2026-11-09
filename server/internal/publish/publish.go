// Package publish turns a space into a public website at /@<handle>/ (§7.10), rendered in the background to
// files under <data>/public/ so a busy page costs a file read, never the database (only search reads it).
package publish

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"notes/server/internal/database"
	"notes/server/internal/sharing"
	"notes/server/internal/spaces"
	"notes/server/internal/userdata"
)

// Key is the published trait's key (§7.8).
const Key = "published"

// MaxCSS caps a space's custom CSS.
const MaxCSS = 64 << 10

// Errors.
var (
	ErrNotPublished = errors.New("the space isn't published")
	ErrHandleTaken  = errors.New("another space has that address")
	// ErrDefault: every account keeps one space no trait exposes, where a locked note refused elsewhere
	// is kept (§7.8).
	ErrDefault = errors.New("the default space can't be published")
)

// InputError reports an invalid setting.
type InputError struct{ Field, Message string }

func (e *InputError) Error() string { return e.Field + ": " + e.Message }

// Site is a published space's address, as system.db has it.
type Site struct {
	File      string // the notes file it's in: user:<id> or space:<id>
	SpaceID   string
	Handle    string
	Origin    string
	Published bool
}

// URL is the site's address.
func (s Site) URL() string { return s.Origin + "/@" + s.Handle + "/" }

// where is the site's address without its scheme, as the apps show it.
func (s Site) where() string {
	u, err := url.Parse(s.Origin)
	if err != nil {
		return s.Origin + "/@" + s.Handle
	}
	return u.Host + "/@" + s.Handle
}

// Settings are a space's publishing settings, as its owner sees them.
type Settings struct {
	Handle    string `json:"handle"`
	URL       string `json:"url"`
	Origin    string `json:"origin"`
	Published bool   `json:"published"`
	CSS       string `json:"css"`
}

// Input changes settings; a nil field keeps what's stored.
type Input struct {
	Handle *string `json:"handle"`
	// Origin is the app's own address (scheme://host), which the server can't know (§10): the site's
	// absolute URLs (the feed) are built from it.
	Origin *string `json:"origin"`
	CSS    *string `json:"css"`
}

// Config sets up a Service.
type Config struct {
	DataDir string
	// Quiet and MaxWait pace re-rendering a site: once its notes have been quiet this long, or at
	// most this long after the first change. Defaults 10s and 2min.
	Quiet, MaxWait time.Duration
	Log            *slog.Logger
}

// Service publishes spaces.
type Service struct {
	system  *database.DB
	files   *userdata.Registry
	share   *sharing.Service
	dataDir string
	dir     string // <data>/public
	quiet   time.Duration
	maxWait time.Duration
	log     *slog.Logger
	now     func() time.Time

	mu        sync.RWMutex
	sites     map[string]Site      // published ones, by handle
	manifests map[string]*manifest // by handle, as last written
	failed    map[string]bool      // by handle: the last render failed
	readers   map[string]*database.DB

	renderMu sync.Mutex // one render at a time: CPU and disk stay for the apps
	pendMu   sync.Mutex
	pending  map[string]bool // files changed since the publisher last looked
	wake     chan struct{}

	limits *limits
}

// New returns a Service. Call Install before any notes file opens, and Run once serving.
func New(system *database.DB, files *userdata.Registry, share *sharing.Service, cfg Config) *Service {
	s := &Service{
		system: system, files: files, share: share, dataDir: cfg.DataDir, dir: filepath.Join(cfg.DataDir, "public"),
		quiet: cfg.Quiet, maxWait: cfg.MaxWait, log: cfg.Log, now: time.Now,
		sites: map[string]Site{}, manifests: map[string]*manifest{}, failed: map[string]bool{},
		readers: map[string]*database.DB{}, pending: map[string]bool{}, wake: make(chan struct{}, 1),
		limits: newLimits(),
	}
	if s.quiet == 0 {
		s.quiet = 10 * time.Second
	}
	if s.maxWait == 0 {
		s.maxWait = 2 * time.Minute
	}
	if s.log == nil {
		s.log = slog.Default()
	}
	return s
}

// Install has every notes file report whether a space is published (exposing it, §7.8), accounts get the
// published trait, a share carry a space's address to its new file, and changes reach the publisher.
func (s *Service) Install() {
	s.files.OnOpen(func(userID int64, d *userdata.Data) {
		d.Spaces.AddExposure(Key, s.exposes(sharing.UserRef(userID)))
		d.Spaces.AddTrait(Key, s.trait(userID))
	}, func(spaceID string, d *userdata.Data) {
		d.Spaces.AddExposure(Key, s.exposes(sharing.SpaceRef(spaceID)))
	})
	s.files.OnChange(func(userID int64) { s.changed(sharing.UserRef(userID)) })
	s.files.OnSpaceChange(func(spaceID string) { s.changed(sharing.SpaceRef(spaceID)) })
	s.share.OnShare(shareHook)
}

// RemoveAccount drops the addresses of an account's own spaces, in the transaction that removes it.
// Shared spaces it published keep theirs: they name the space's file, not the account.
func RemoveAccount(ctx context.Context, tx *sql.Tx, userID int64) error {
	_, err := tx.ExecContext(ctx, `DELETE FROM published_spaces WHERE file = ?`, sharing.UserRef(userID))
	return err
}

// shareHook moves a space's address to its own file as it's shared.
func shareHook(ctx context.Context, tx *sql.Tx, ownerID int64, spaceID string) error {
	_, err := tx.ExecContext(ctx, `UPDATE published_spaces SET file = ? WHERE file = ? AND space_id = ?`,
		sharing.SpaceRef(spaceID), sharing.UserRef(ownerID), spaceID)
	return err
}

// exposes reports whether file's space is published: anyone can read it.
func (s *Service) exposes(file string) spaces.ExposureSource {
	return func(ctx context.Context, spaceID string) (bool, error) {
		var on bool
		err := s.system.R.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM published_spaces
			WHERE file = ? AND space_id = ? AND published = 1)`, file, spaceID).Scan(&on)
		return on, err
	}
}

// trait is the published trait as userID sees it: their own spaces, and the shared ones they're in.
func (s *Service) trait(userID int64) spaces.TraitSource {
	return func(ctx context.Context, ids []string) (map[string]spaces.Trait, error) {
		out := map[string]spaces.Trait{}
		for _, id := range ids {
			site, err := s.lookup(ctx, s.system.R, id, sharing.UserRef(userID), sharing.SpaceRef(id))
			if errors.Is(err, ErrNotPublished) || err == nil && !site.Published {
				continue
			} else if err != nil {
				return nil, err
			}
			t := spaces.Trait{
				Label: "Published", Summary: "At " + site.where(), Exposure: "Public at " + site.where(),
				Audience: []string{"public"},
			}
			s.mu.RLock()
			if s.failed[site.Handle] {
				t.Problem = "Publishing failed"
			}
			s.mu.RUnlock()
			out[id] = t
		}
		return out, nil
	}
}

type querier interface {
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

// lookup is a space's row, in whichever of files has it.
func (s *Service) lookup(ctx context.Context, q querier, spaceID string, files ...string) (Site, error) {
	for _, f := range files {
		site := Site{File: f, SpaceID: spaceID}
		err := q.QueryRowContext(ctx, `SELECT handle, origin, published FROM published_spaces
			WHERE file = ? AND space_id = ?`, f, spaceID).Scan(&site.Handle, &site.Origin, &site.Published)
		if err == nil {
			return site, nil
		} else if !errors.Is(err, sql.ErrNoRows) {
			return Site{}, err
		}
	}
	return Site{}, ErrNotPublished
}

// Get is a space's publishing settings; ErrNotPublished if it never was. d is file's notes.
func (s *Service) Get(ctx context.Context, file string, d *userdata.Data, spaceID string) (Settings, error) {
	site, err := s.lookup(ctx, s.system.R, spaceID, file)
	if err != nil {
		return Settings{}, err
	}
	var css string
	if err := d.DB.R.QueryRowContext(ctx, `SELECT coalesce((SELECT css FROM space_publish WHERE space_id = ?), '')`,
		spaceID).Scan(&css); err != nil {
		return Settings{}, err
	}
	return Settings{Handle: site.Handle, URL: site.URL(), Origin: site.Origin, Published: site.Published, CSS: css}, nil
}

var handlePattern = regexp.MustCompile(`^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$`)

// Publish publishes a space, or changes how it's published, then renders it. Refused while the space
// holds locked notes (notes.LockedInSpaceError): publishing exposes it (§7.8). d is file's notes.
func (s *Service) Publish(ctx context.Context, file string, d *userdata.Data, spaceID string, in Input) (Settings, error) {
	if spaceID == spaces.Default {
		return Settings{}, ErrDefault
	}
	sp, err := d.Spaces.Get(ctx, spaceID)
	if err != nil {
		return Settings{}, err
	}
	if in.Handle != nil && !handlePattern.MatchString(*in.Handle) {
		return Settings{}, &InputError{"handle", "must be 1-64 characters of a-z, 0-9 and -, not starting or ending with -"}
	}
	var origin string
	if in.Origin != nil {
		if origin, err = cleanOrigin(*in.Origin); err != nil {
			return Settings{}, err
		}
	}
	if in.CSS != nil && len(*in.CSS) > MaxCSS {
		return Settings{}, &InputError{"css", fmt.Sprintf("must be at most %d KB", MaxCSS>>10)}
	}
	var site Site
	err = d.Notes.WithoutLocked(ctx, spaceID, func(tx *sql.Tx) error {
		now := s.now().UnixMilli()
		if _, err := tx.ExecContext(ctx, `INSERT INTO space_publish (space_id, css, updated_at) VALUES (?, coalesce(?, ''), ?)
			ON CONFLICT (space_id) DO UPDATE SET css = coalesce(?, css), updated_at = excluded.updated_at`,
			spaceID, in.CSS, now, in.CSS); err != nil {
			return err
		}
		return s.system.WriteTx(ctx, func(stx *sql.Tx) error {
			old, err := s.lookup(ctx, stx, spaceID, file)
			if err != nil && !errors.Is(err, ErrNotPublished) {
				return err
			}
			site = Site{File: file, SpaceID: spaceID, Handle: old.Handle, Origin: old.Origin, Published: true}
			if origin != "" {
				site.Origin = origin
			}
			if site.Origin == "" {
				return &InputError{"origin", "is required: the app's address, such as https://notes.example.com"}
			}
			switch {
			case in.Handle != nil && *in.Handle != old.Handle:
				if taken, err := handleTaken(ctx, stx, *in.Handle); err != nil {
					return err
				} else if taken {
					return ErrHandleTaken
				}
				site.Handle = *in.Handle
			case site.Handle == "":
				if site.Handle, err = freeHandle(ctx, stx, sp.Name); err != nil {
					return err
				}
			}
			_, err = stx.ExecContext(ctx, `INSERT INTO published_spaces (file, space_id, handle, origin, published, updated_at)
				VALUES (?, ?, ?, ?, 1, ?)
				ON CONFLICT (file, space_id) DO UPDATE SET handle = excluded.handle, origin = excluded.origin,
					published = 1, updated_at = excluded.updated_at`,
				file, spaceID, site.Handle, site.Origin, now)
			return err
		})
	})
	if err != nil {
		return Settings{}, err
	}
	s.reload(ctx)
	if err := s.render(ctx, site); err != nil {
		s.log.Error("publish space", "handle", site.Handle, "err", err)
	}
	return s.Get(ctx, file, d, spaceID)
}

// Unpublish takes a space's site down, keeping its address for it.
func (s *Service) Unpublish(ctx context.Context, file, spaceID string) error {
	var n int64
	err := s.system.WriteTx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `UPDATE published_spaces SET published = 0, updated_at = ?
			WHERE file = ? AND space_id = ?`, s.now().UnixMilli(), file, spaceID)
		if err != nil {
			return err
		}
		n, err = res.RowsAffected()
		return err
	})
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrNotPublished
	}
	s.reload(ctx)
	return nil
}

// Forget drops a deleted space's address, freeing its handle.
func (s *Service) Forget(ctx context.Context, file, spaceID string) error {
	err := s.system.WriteTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `DELETE FROM published_spaces WHERE file = ? AND space_id = ?`, file, spaceID)
		return err
	})
	if err == nil {
		s.reload(ctx)
	}
	return err
}

// cleanOrigin is an app's address as scheme://host[:port].
func cleanOrigin(raw string) (string, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" || u.User != nil ||
		strings.Trim(u.Path, "/") != "" || u.RawQuery != "" || u.Fragment != "" {
		return "", &InputError{"origin", "must be an address such as https://notes.example.com"}
	}
	return u.Scheme + "://" + strings.ToLower(u.Host), nil
}

func handleTaken(ctx context.Context, tx *sql.Tx, handle string) (bool, error) {
	var taken bool
	err := tx.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM published_spaces WHERE handle = ?)`, handle).Scan(&taken)
	return taken, err
}

// freeHandle is a handle from a space's name ("Garden Notes": garden-notes), numbered if taken.
func freeHandle(ctx context.Context, tx *sql.Tx, name string) (string, error) {
	base := slug(name)
	if base == "post" {
		base = "space"
	}
	base = strings.Trim(base[:min(len(base), 56)], "-")
	for n := 1; ; n++ {
		h := base
		if n > 1 {
			h += "-" + strconv.Itoa(n)
		}
		taken, err := handleTaken(ctx, tx, h)
		if err != nil || !taken {
			return h, err
		}
	}
}

// Reload reads the published spaces' addresses again, after a change made elsewhere in system.db (an
// account removed): sites no longer published stop being served at once rather than at the next check.
func (s *Service) Reload(ctx context.Context) { s.reload(ctx) }

// reload reads every published space's address, and takes down the sites of those no longer published.
func (s *Service) reload(ctx context.Context) {
	rows, err := s.system.R.QueryContext(ctx, `SELECT file, space_id, handle, origin FROM published_spaces WHERE published = 1`)
	if err != nil {
		s.log.Error("published spaces", "err", err)
		return
	}
	sites := map[string]Site{}
	for rows.Next() {
		site := Site{Published: true}
		if err := rows.Scan(&site.File, &site.SpaceID, &site.Handle, &site.Origin); err != nil {
			rows.Close()
			s.log.Error("published spaces", "err", err)
			return
		}
		sites[site.Handle] = site
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		s.log.Error("published spaces", "err", err)
		return
	}
	s.mu.Lock()
	var gone []string
	for h := range s.sites {
		if _, ok := sites[h]; !ok {
			gone = append(gone, h)
			delete(s.manifests, h)
			delete(s.failed, h)
		}
	}
	s.sites = sites
	s.mu.Unlock()
	for _, h := range gone {
		s.takeDown(h)
	}
}

// takeDown removes a site's files.
func (s *Service) takeDown(handle string) {
	s.renderMu.Lock()
	defer s.renderMu.Unlock()
	s.mu.RLock()
	_, back := s.sites[handle]
	s.mu.RUnlock()
	if back {
		return // published again meanwhile
	}
	if err := os.RemoveAll(filepath.Join(s.dir, handle)); err != nil {
		s.log.Error("take down site", "handle", handle, "err", err)
	}
}

// open is a notes file's data, nil when it's gone.
func (s *Service) open(ctx context.Context, file string) (*userdata.Data, error) {
	kind, id, _ := strings.Cut(file, ":")
	if kind == "space" {
		return s.files.OpenSpaceIfExists(ctx, id)
	}
	userID, err := strconv.ParseInt(id, 10, 64)
	if err != nil {
		return nil, fmt.Errorf("bad file %q", file)
	}
	return s.files.Open(ctx, userID)
}

// render builds a site from its space's notes and writes it.
func (s *Service) render(ctx context.Context, site Site) error {
	s.renderMu.Lock()
	defer s.renderMu.Unlock()
	err := s.renderLocked(ctx, site)
	s.mu.Lock()
	if _, ok := s.sites[site.Handle]; ok {
		s.failed[site.Handle] = err != nil
	}
	s.mu.Unlock()
	return err
}

func (s *Service) renderLocked(ctx context.Context, site Site) error {
	s.mu.RLock()
	current, ok := s.sites[site.Handle]
	s.mu.RUnlock()
	if !ok || current != site {
		return nil // taken down or changed since: the next render is that one's
	}
	d, err := s.open(ctx, site.File)
	if err != nil {
		return err
	}
	if d == nil {
		return fmt.Errorf("%s is gone", site.File)
	}
	src, err := load(ctx, d, site.SpaceID, site.URL())
	if errors.Is(err, spaces.ErrNotFound) {
		// The space was deleted: its address goes with it.
		return s.Forget(ctx, site.File, site.SpaceID)
	} else if err != nil {
		return err
	}
	src.BasePath = "/@" + site.Handle + "/"
	built, err := build(src, src.Icon)
	if err != nil {
		return err
	}
	built.manifest.SpaceID = site.SpaceID
	if err := s.write(site.Handle, built); err != nil {
		return err
	}
	s.mu.Lock()
	s.manifests[site.Handle] = built.manifest
	s.mu.Unlock()
	return nil
}

// write puts a site's files in its folder: only those that changed, each replaced whole, then the
// manifest, then away with files it no longer has.
func (s *Service) write(handle string, built *site) error {
	dir := filepath.Join(s.dir, handle)
	for name, data := range built.files {
		p := filepath.Join(dir, filepath.FromSlash(name))
		if old, err := os.ReadFile(p); err == nil && string(old) == string(data) {
			continue
		}
		if err := writeFile(p, data); err != nil {
			return err
		}
	}
	m, err := json.Marshal(built.manifest)
	if err != nil {
		return err
	}
	if err := writeFile(filepath.Join(dir, "manifest.json"), m); err != nil {
		return err
	}
	return filepath.WalkDir(dir, func(p string, e os.DirEntry, err error) error {
		if err != nil || e.IsDir() {
			return err
		}
		rel, _ := filepath.Rel(dir, p)
		if _, keep := built.files[filepath.ToSlash(rel)]; keep || rel == "manifest.json" {
			return nil
		}
		return os.Remove(p)
	})
}

func writeFile(p string, data []byte) error {
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(p), ".tmp-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), p)
}

// changed notes that a file's notes or spaces changed.
func (s *Service) changed(file string) {
	s.pendMu.Lock()
	s.pending[file] = true
	s.pendMu.Unlock()
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

// Run keeps every published site up to date until ctx ends: all of them at first, then each once its
// space has been quiet (Config.Quiet), or at most MaxWait after its first change.
func (s *Service) Run(ctx context.Context) {
	s.reload(ctx)
	s.sweep()
	s.loadManifests()
	s.mu.RLock()
	all := make([]Site, 0, len(s.sites))
	for _, site := range s.sites {
		all = append(all, site)
	}
	s.mu.RUnlock()
	for _, site := range all {
		if ctx.Err() != nil {
			return
		}
		if err := s.render(ctx, site); err != nil {
			s.log.Error("publish space", "handle", site.Handle, "err", err)
		}
	}

	type due struct{ first, last time.Time }
	dirty := map[string]due{}
	timer := time.NewTimer(time.Hour)
	defer timer.Stop()
	reconcile := time.NewTicker(time.Minute)
	defer reconcile.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-s.wake:
			s.pendMu.Lock()
			files := s.pending
			s.pending = map[string]bool{}
			s.pendMu.Unlock()
			// A share moves a space's address to another file: read them again first.
			s.reload(ctx)
			now := s.now()
			s.mu.RLock()
			for h, site := range s.sites {
				if files[site.File] {
					d := dirty[h]
					if d.first.IsZero() {
						d.first = now
					}
					d.last = now
					dirty[h] = d
				}
			}
			s.mu.RUnlock()
		case <-reconcile.C:
			s.reload(ctx)
			s.sweep()
		case <-timer.C:
		}
		now := s.now()
		next := time.Hour
		for h, d := range dirty {
			wait := min(d.last.Add(s.quiet).Sub(now), d.first.Add(s.maxWait).Sub(now))
			if wait > 0 {
				next = min(next, wait)
				continue
			}
			delete(dirty, h)
			s.mu.RLock()
			site, ok := s.sites[h]
			s.mu.RUnlock()
			if !ok {
				continue
			}
			if err := s.render(ctx, site); err != nil && ctx.Err() == nil {
				s.log.Error("publish space", "handle", h, "err", err)
			}
		}
		if !timer.Stop() {
			select {
			case <-timer.C:
			default:
			}
		}
		timer.Reset(next)
	}
}

// loadManifests serves what was rendered before a restart until it's rendered again.
func (s *Service) loadManifests() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for h := range s.sites {
		b, err := os.ReadFile(filepath.Join(s.dir, h, "manifest.json"))
		if err != nil {
			continue
		}
		var m manifest
		if json.Unmarshal(b, &m) == nil {
			s.manifests[h] = &m
		}
	}
}

// sweep removes folders under public/ no published space has.
func (s *Service) sweep() {
	entries, err := os.ReadDir(s.dir)
	if err != nil {
		return
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, e := range entries {
		if _, ok := s.sites[e.Name()]; !ok {
			go s.takeDown(e.Name())
		}
	}
}

// Close closes the public's read connections.
func (s *Service) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	var errs []error
	for _, db := range s.readers {
		errs = append(errs, db.Close())
	}
	s.readers = map[string]*database.DB{}
	return errors.Join(errs...)
}

// reader is the public's own small pool of read connections to a file, so a burst of searches never
// takes the connections the apps use.
func (s *Service) reader(ctx context.Context, file string) (*database.DB, error) {
	s.mu.RLock()
	db := s.readers[file]
	s.mu.RUnlock()
	if db != nil {
		return db, nil
	}
	// Opened by the registry first: its migrations run before anyone reads.
	if d, err := s.open(ctx, file); err != nil || d == nil {
		return nil, errors.Join(err, errors.New("no such file"))
	}
	kind, id, _ := strings.Cut(file, ":")
	p := userdata.SpacePath(s.dataDir, id)
	if kind == "user" {
		n, _ := strconv.ParseInt(id, 10, 64)
		p = userdata.Path(s.dataDir, n)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if db := s.readers[file]; db != nil {
		return db, nil
	}
	db, err := database.OpenReader(ctx, p, publicReaders)
	if err != nil {
		return nil, err
	}
	s.readers[file] = db
	return db, nil
}
