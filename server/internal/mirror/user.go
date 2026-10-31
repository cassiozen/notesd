package mirror

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"notes/server/internal/database"
	"notes/server/internal/images"
	"notes/server/internal/locks"
	"notes/server/internal/mdexport"
	"notes/server/internal/notes"
	"notes/server/internal/spaces"
	"notes/server/internal/sync"
)

const (
	manifestName = ".notesd-mirror.json"
	pageSize     = 100
)

// manifest is what a user's folder holds, as the mirror wrote it. Paths are
// slash-separated, from the user's folder.
type manifest struct {
	Generation string            `json:"generation"`
	Cursor     int64             `json:"cursor"`
	Spaces     map[string]string `json:"spaces"` // space id → folder
	Notes      map[string]*entry `json:"notes"`
	Images     map[string]string `json:"images"` // image id → path
}

type entry struct {
	Path   string   `json:"path"`
	Hash   string   `json:"hash"` // of the file as written
	Images []string `json:"images,omitempty"`
	// Shows an image the server doesn't have yet: written again once it does.
	Missing bool `json:"missing,omitempty"`
}

// user is one user's folder.
type user struct {
	dir string
	man manifest
	// NameKey of each path the mirror wrote → the note's id or the space's.
	files, folders map[string]string
	// Folders of deleted spaces, removed once the notes have left them.
	orphans []string
}

// loadUser reads a user's manifest, or starts one in a missing or empty
// folder. Hidden files, such as macOS's .DS_Store, don't count.
func loadUser(dir string) (*user, error) {
	u := &user{dir: dir}
	data, err := os.ReadFile(filepath.Join(dir, manifestName))
	switch {
	case err == nil:
		err := json.Unmarshal(data, &u.man)
		if err == nil {
			err = u.man.check()
		}
		if err != nil {
			return nil, fmt.Errorf("%s is damaged, so the mirror won't touch this folder: %w", filepath.Join(dir, manifestName), err)
		}
	case errors.Is(err, fs.ErrNotExist):
		entries, err := os.ReadDir(dir)
		if err != nil && !errors.Is(err, fs.ErrNotExist) {
			return nil, err
		}
		for _, e := range entries {
			if !strings.HasPrefix(e.Name(), ".") {
				return nil, fmt.Errorf("%s has files but isn't a notesd mirror; move them, or choose a new or empty --mirror-dir", dir)
			}
		}
	default:
		return nil, err
	}
	if u.man.Spaces == nil {
		u.man.Spaces = map[string]string{}
	}
	if u.man.Notes == nil {
		u.man.Notes = map[string]*entry{}
	}
	if u.man.Images == nil {
		u.man.Images = map[string]string{}
	}
	u.index()
	return u, nil
}

// check refuses paths a manifest written by the mirror can't hold. A sync
// tool may bring one from elsewhere, and the mirror deletes what it lists.
func (m *manifest) check() error {
	for _, f := range m.Spaces {
		if !oneName(f) {
			return fmt.Errorf("bad folder %q", f)
		}
	}
	for id, e := range m.Notes {
		if e == nil {
			return fmt.Errorf("note %q has no entry", id)
		}
		folder, name := path.Split(e.Path)
		if !oneName(strings.TrimSuffix(folder, "/")) || !oneName(name) || !strings.HasSuffix(name, ".md") {
			return fmt.Errorf("bad note path %q", e.Path)
		}
	}
	for id, p := range m.Images {
		folder, name := path.Split(p)
		if !images.ValidID(id) || folder != mdexport.ImagesDir+"/" || !oneName(name) || !strings.HasPrefix(name, id+".") {
			return fmt.Errorf("bad image path %q", p)
		}
	}
	return nil
}

// oneName reports whether s is a single file or folder name.
func oneName(s string) bool {
	return s != "" && s != "." && s != ".." && !strings.ContainsAny(s, `/\`) && filepath.IsLocal(s)
}

func (u *user) index() {
	u.files, u.folders = map[string]string{}, map[string]string{}
	for id, e := range u.man.Notes {
		u.files[mdexport.NameKey(e.Path)] = id
	}
	for id, f := range u.man.Spaces {
		u.folders[mdexport.NameKey(f)] = id
	}
}

func (u *user) save() error {
	data, err := json.Marshal(u.man)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(u.dir, 0o700); err != nil {
		return err
	}
	return mdexport.WriteFileAtomic(filepath.Join(u.dir, manifestName), data)
}

func (u *user) abs(p string) string { return filepath.Join(u.dir, filepath.FromSlash(p)) }

// flush brings the folder up to date with db. Stopped by ctx, it keeps what
// it wrote, and the next flush goes on from there.
func (u *user) flush(ctx context.Context, db *database.DB, log *slog.Logger) (err error) {
	if err := os.MkdirAll(u.dir, 0o700); err != nil {
		return err
	}
	defer func() {
		if serr := u.save(); err == nil {
			err = serr
		}
	}()
	if err := u.syncSpaces(ctx, db); err != nil {
		return err
	}
	store := images.NewStore(db)
	get := func(id string) (string, []byte, error) {
		img, data, err := store.Get(ctx, id)
		return img.ContentType, data, err
	}

	// A restored backup is another lineage (§11.4): its notes are written
	// from scratch, and files of notes it doesn't have are removed.
	var seen map[string]bool
	cursor := u.man.Cursor
	for {
		page, err := sync.Changes(ctx, db, cursor, pageSize, "")
		if seen == nil && (errors.Is(err, sync.ErrCursorAhead) || (err == nil && page.Generation != u.man.Generation)) {
			seen, cursor = map[string]bool{}, 0
			continue
		}
		if err != nil {
			return err
		}
		for _, n := range page.Changes {
			if err := ctx.Err(); err != nil {
				return err
			}
			if seen != nil {
				seen[n.ID] = true
			}
			if err := u.apply(ctx, db, n, get); err != nil {
				return fmt.Errorf("note %s: %w", n.ID, err)
			}
			if seen == nil {
				u.man.Cursor = n.ChangeSeq
			}
		}
		cursor = page.NextCursor
		if seen == nil {
			u.man.Cursor = cursor
		}
		if !page.HasMore {
			if seen != nil {
				for id := range u.man.Notes {
					if !seen[id] {
						if err := u.remove(id); err != nil {
							return err
						}
					}
				}
				u.man.Generation, u.man.Cursor = page.Generation, cursor
				log.Info("markdown mirror written in full", "dir", u.dir, "notes", len(u.man.Notes))
			}
			break
		}
	}

	for id, e := range u.man.Notes {
		if !e.Missing {
			continue
		}
		n, err := notes.NewStore(db).Get(ctx, id)
		if errors.Is(err, notes.ErrNotFound) {
			if err := u.remove(id); err != nil {
				return err
			}
			continue
		}
		if err != nil {
			return err
		}
		if err := u.apply(ctx, db, n, get); err != nil {
			return fmt.Errorf("note %s: %w", n.ID, err)
		}
	}
	for _, f := range u.orphans {
		// Fails, and stays, while it holds files the mirror didn't write.
		_ = os.Remove(u.abs(f))
	}
	u.orphans = nil
	return u.collectImages()
}

// syncSpaces gives each space a folder, renaming those whose space was.
func (u *user) syncSpaces(ctx context.Context, db *database.DB) error {
	list, err := spaces.NewStore(db).List(ctx)
	if err != nil {
		return err
	}
	live := map[string]bool{}
	for _, sp := range list {
		live[sp.ID] = true
		base := mdexport.FileName(sp.Name)
		old, had := u.man.Spaces[sp.ID]
		if had && numberedOf(old, base) {
			continue
		}
		name, err := u.freeName(".", base, "", u.folders, sp.ID, old)
		if err != nil {
			return err
		}
		if had {
			if err := u.moveFolder(old, name); err != nil {
				return err
			}
		}
		u.man.Spaces[sp.ID] = name
		u.index()
	}
	for id, f := range u.man.Spaces {
		if !live[id] {
			delete(u.man.Spaces, id)
			u.orphans = append(u.orphans, f)
		}
	}
	u.index()
	return nil
}

// moveFolder moves the notes in folder old to folder name: the whole folder
// when it holds only them, otherwise each, leaving the others' files behind.
func (u *user) moveFolder(old, name string) error {
	entries, err := os.ReadDir(u.abs(old))
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	ours := true
	for _, e := range entries {
		if _, ok := u.files[mdexport.NameKey(old+"/"+e.Name())]; !ok && !strings.HasPrefix(e.Name(), ".notesd-") {
			ours = false
		}
	}
	if len(entries) > 0 && ours {
		if err := os.Rename(u.abs(old), u.abs(name)); err != nil {
			return err
		}
	} else if len(entries) > 0 {
		if err := os.MkdirAll(u.abs(name), 0o700); err != nil {
			return err
		}
	}
	for _, e := range u.man.Notes {
		rest, ok := strings.CutPrefix(e.Path, old+"/")
		if !ok {
			continue
		}
		next := name + "/" + rest
		if !ours {
			if err := os.Rename(u.abs(e.Path), u.abs(next)); err != nil && !errors.Is(err, fs.ErrNotExist) {
				return err
			}
		}
		e.Path = next
	}
	return nil
}

// folder is the folder of a note's space.
func (u *user) folder(ctx context.Context, db *database.DB, spaceID string) (string, error) {
	if f, ok := u.man.Spaces[spaceID]; ok {
		return f, nil
	}
	// Created after this flush listed them.
	if err := u.syncSpaces(ctx, db); err != nil {
		return "", err
	}
	if f, ok := u.man.Spaces[spaceID]; ok {
		return f, nil
	}
	if f, ok := u.man.Spaces[spaces.Default]; ok {
		return f, nil
	}
	return "", fmt.Errorf("no folder for space %s", spaceID)
}

// apply writes, moves or removes one note's file.
func (u *user) apply(ctx context.Context, db *database.DB, n notes.Note, get mdexport.GetImage) error {
	// The server can't read a locked note, and its plain text, from before
	// it was locked, mustn't stay on disk.
	if n.DeletedAt != nil || locks.IsLocked(n.Content) {
		return u.remove(n.ID)
	}
	folder, err := u.folder(ctx, db, n.SpaceID)
	if err != nil {
		return err
	}
	next := &entry{}
	paths := map[string]string{}
	for _, id := range images.Refs(n.Content) {
		p, ok := u.man.Images[id]
		if !ok {
			if p, err = mdexport.WriteImage(u.dir, id, nil, get); err != nil {
				return err
			}
			if p == "" {
				next.Missing = true
				continue
			}
			u.man.Images[id] = p
		}
		paths[id] = p
		next.Images = append(next.Images, id)
	}
	body := []byte(mdexport.Markdown(n.Title, mdexport.PointImages(n.Content, "../", paths)))
	sum := sha256.Sum256(body)
	next.Hash = hex.EncodeToString(sum[:])

	prev := u.man.Notes[n.ID]
	base := mdexport.FileName(n.Title)
	if prev != nil && path.Dir(prev.Path) == folder && numberedOf(strings.TrimSuffix(path.Base(prev.Path), ".md"), base) {
		next.Path = prev.Path
	} else {
		name, err := u.freeName(folder, base, ".md", u.files, n.ID, "")
		if err != nil {
			return err
		}
		next.Path = folder + "/" + name
	}
	if err := os.MkdirAll(u.abs(folder), 0o700); err != nil {
		return err
	}
	if prev != nil && prev.Path != next.Path {
		// A rename, not write-then-delete: "Todo" retitled "todo" is the same
		// file where names ignore case.
		if err := os.Rename(u.abs(prev.Path), u.abs(next.Path)); err != nil && !errors.Is(err, fs.ErrNotExist) {
			return err
		}
		delete(u.files, mdexport.NameKey(prev.Path))
	}
	if prev == nil || prev.Path != next.Path || prev.Hash != next.Hash {
		if err := mdexport.WriteFileAtomic(u.abs(next.Path), body); err != nil {
			return err
		}
		t := time.UnixMilli(n.UpdatedAt)
		if err := os.Chtimes(u.abs(next.Path), t, t); err != nil {
			return err
		}
	}
	u.man.Notes[n.ID] = next
	u.files[mdexport.NameKey(next.Path)] = n.ID
	return nil
}

// remove deletes a note's file, if the mirror wrote one.
func (u *user) remove(id string) error {
	e := u.man.Notes[id]
	if e == nil {
		return nil
	}
	if err := os.Remove(u.abs(e.Path)); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	delete(u.man.Notes, id)
	delete(u.files, mdexport.NameKey(e.Path))
	return nil
}

// collectImages deletes the image files no mirrored note shows any more.
func (u *user) collectImages() error {
	shown := map[string]bool{}
	for _, e := range u.man.Notes {
		for _, id := range e.Images {
			shown[id] = true
		}
	}
	for id, p := range u.man.Images {
		if shown[id] {
			continue
		}
		if err := os.Remove(u.abs(p)); err != nil && !errors.Is(err, fs.ErrNotExist) {
			return err
		}
		delete(u.man.Images, id)
	}
	return nil
}

// freeName is the first of base, "base (2)"… (plus ext) in dir that neither
// another owner in owners nor a foreign file has. was: own's previous name.
func (u *user) freeName(dir, base, ext string, owners map[string]string, own, was string) (string, error) {
	imagesKey := mdexport.NameKey(mdexport.ImagesDir)
	for i := 1; ; i++ {
		name := mdexport.Numbered(base, i) + ext
		p := path.Join(dir, name)
		key := mdexport.NameKey(p)
		if key == imagesKey {
			continue
		}
		if owner, ok := owners[key]; ok {
			if owner == own {
				return name, nil
			}
			continue
		}
		if was != "" && key == mdexport.NameKey(was) {
			return name, nil
		}
		ok, err := taken(u.abs(dir), name)
		if err != nil {
			return "", err
		}
		if !ok {
			return name, nil
		}
	}
}

// numberedOf reports whether name is base or a numbered base: "Todo (2)".
func numberedOf(name, base string) bool {
	if name == base {
		return true
	}
	rest, ok := strings.CutPrefix(name, base+" (")
	if !ok {
		return false
	}
	num, ok := strings.CutSuffix(rest, ")")
	i, err := strconv.Atoi(num)
	return ok && err == nil && i >= 2 && strconv.Itoa(i) == num
}
