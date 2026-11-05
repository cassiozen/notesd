package mdimport

import (
	"archive/zip"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"golang.org/x/sys/unix"
)

// Limits on what an import reads. An archive's own size fields can lie, so
// reads are capped as they happen, not checked up front.
const (
	// MaxFiles caps the entries a folder or zip may hold.
	MaxFiles = 100_000
	// maxNoteFileBytes is read from a note file: a note is at most 1 MiB,
	// plus room for front matter.
	maxNoteFileBytes = 4 << 20
)

// File is one regular file in a Source, by its path from the root.
type File struct {
	Path    string // slash-separated, relative to the root, never above it
	ModTime time.Time
	open    func() (io.ReadCloser, error)
}

// Source is a folder or zip of files to import.
type Source struct {
	Files []File
	// Skipped lists entries left out while listing: symbolic links and
	// paths that would leave the root.
	Skipped []Issue
	byPath  map[string]*File
	byLower map[string][]*File
	byBase  map[string][]*File
	closer  io.Closer
}

// Close releases the zip or folder.
func (s *Source) Close() error {
	if s.closer != nil {
		return s.closer.Close()
	}
	return nil
}

// Open reads a folder or a .zip file.
func Open(root string) (*Source, error) {
	// WalkDir doesn't descend into a root that is itself a link.
	root, err := filepath.EvalSymlinks(root)
	if err != nil {
		return nil, err
	}
	info, err := os.Stat(root)
	if err != nil {
		return nil, err
	}
	if info.IsDir() {
		return openDir(root)
	}
	if strings.EqualFold(filepath.Ext(root), ".zip") {
		return openZip(root)
	}
	return nil, fmt.Errorf("%s is neither a folder nor a .zip file", root)
}

func openDir(root string) (*Source, error) {
	dir, err := os.Open(root)
	if err != nil {
		return nil, err
	}
	s := &Source{closer: dir}
	err = filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if p == root {
			return nil
		}
		rel, err := filepath.Rel(root, p)
		if err != nil {
			return err
		}
		rel = filepath.ToSlash(rel)
		if hidden(rel) {
			if d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		// WalkDir never follows symbolic links; reading through one could
		// reach files outside the folder being imported.
		if d.Type()&fs.ModeSymlink != 0 {
			s.Skipped = append(s.Skipped, Issue{rel, "symbolic link, not followed"})
			return nil
		}
		if d.IsDir() || !d.Type().IsRegular() {
			return nil
		}
		if len(s.Files) >= MaxFiles {
			return fmt.Errorf("more than %d files; import a smaller folder", MaxFiles)
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		s.Files = append(s.Files, File{Path: rel, ModTime: info.ModTime(), open: func() (io.ReadCloser, error) {
			return openIn(dir, rel)
		}})
		return nil
	})
	if err != nil {
		dir.Close()
		return nil, err
	}
	s.index()
	return s, nil
}

// openIn opens rel under dir a part at a time, following no symbolic link,
// so a folder swapped for a link after the walk can't lead outside dir.
func openIn(dir *os.File, rel string) (io.ReadCloser, error) {
	parts := strings.Split(rel, "/")
	fd, owned := int(dir.Fd()), false
	for i, part := range parts {
		flags := unix.O_RDONLY | unix.O_NOFOLLOW | unix.O_CLOEXEC | unix.O_DIRECTORY
		if i == len(parts)-1 {
			// Nonblocking so a FIFO put in the file's place doesn't hang.
			flags = unix.O_RDONLY | unix.O_NOFOLLOW | unix.O_CLOEXEC | unix.O_NONBLOCK
		}
		next, err := unix.Openat(fd, part, flags, 0)
		if owned {
			unix.Close(fd)
		}
		if err != nil {
			return nil, &fs.PathError{Op: "open", Path: rel, Err: err}
		}
		fd, owned = next, true
	}
	var st unix.Stat_t
	if err := unix.Fstat(fd, &st); err != nil {
		unix.Close(fd)
		return nil, &fs.PathError{Op: "stat", Path: rel, Err: err}
	}
	if st.Mode&unix.S_IFMT != unix.S_IFREG {
		unix.Close(fd)
		return nil, errors.New("not a regular file")
	}
	if err := unix.SetNonblock(fd, false); err != nil {
		unix.Close(fd)
		return nil, err
	}
	return os.NewFile(uintptr(fd), rel), nil
}

func openZip(name string) (*Source, error) {
	zr, err := zip.OpenReader(name)
	if err != nil {
		return nil, fmt.Errorf("read %s: %w", name, err)
	}
	s := &Source{closer: zr}
	for _, zf := range zr.File {
		if zf.FileInfo().IsDir() {
			continue
		}
		p, ok := localPath(zf.Name)
		if !ok {
			s.Skipped = append(s.Skipped, Issue{zf.Name, "path leaves the archive, not read"})
			continue
		}
		if hidden(p) || strings.HasPrefix(p, "__MACOSX/") {
			continue
		}
		if zf.Mode()&fs.ModeSymlink != 0 {
			s.Skipped = append(s.Skipped, Issue{p, "symbolic link, not followed"})
			continue
		}
		if !zf.Mode().IsRegular() {
			continue
		}
		if len(s.Files) >= MaxFiles {
			zr.Close()
			return nil, fmt.Errorf("more than %d files; import a smaller archive", MaxFiles)
		}
		s.Files = append(s.Files, File{Path: p, ModTime: zf.Modified, open: zf.Open})
	}
	s.stripCommonFolder()
	s.index()
	return s, nil
}

// localPath cleans a zip entry's name, refusing one that is absolute or
// climbs above the root.
func localPath(name string) (string, bool) {
	name = strings.ReplaceAll(name, `\`, "/")
	if strings.HasPrefix(name, "/") || len(name) >= 2 && name[1] == ':' {
		return "", false
	}
	p := path.Clean(name)
	if p == "." || p == ".." || strings.HasPrefix(p, "../") {
		return "", false
	}
	return p, true
}

// stripCommonFolder drops a folder every file is in: zipping a folder puts
// its name on every path, which would otherwise tag every note.
func (s *Source) stripCommonFolder() {
	if len(s.Files) == 0 {
		return
	}
	first, _, ok := strings.Cut(s.Files[0].Path, "/")
	if !ok {
		return
	}
	prefix := first + "/"
	for _, f := range s.Files {
		if !strings.HasPrefix(f.Path, prefix) {
			return
		}
	}
	for i := range s.Files {
		s.Files[i].Path = strings.TrimPrefix(s.Files[i].Path, prefix)
	}
}

func (s *Source) index() {
	sort.Slice(s.Files, func(i, j int) bool { return s.Files[i].Path < s.Files[j].Path })
	s.byPath = map[string]*File{}
	s.byLower = map[string][]*File{}
	s.byBase = map[string][]*File{}
	for i := range s.Files {
		f := &s.Files[i]
		s.byPath[f.Path] = f
		lower := strings.ToLower(f.Path)
		s.byLower[lower] = append(s.byLower[lower], f)
		base := strings.ToLower(path.Base(f.Path))
		s.byBase[base] = append(s.byBase[base], f)
	}
}

// Lookup finds the file p names, exactly, then ignoring case (exports made
// on macOS or Windows may not match a link's case).
func (s *Source) Lookup(p string) *File {
	if f := s.byPath[p]; f != nil {
		return f
	}
	if fs := s.byLower[strings.ToLower(p)]; len(fs) == 1 {
		return fs[0]
	}
	return nil
}

// LookupBase finds a file by name anywhere in the source, as Obsidian
// resolves `![[name]]`: the one with the shortest path when several share it.
func (s *Source) LookupBase(name string) *File {
	fs := s.byBase[strings.ToLower(name)]
	var best *File
	for _, f := range fs {
		if best == nil || len(f.Path) < len(best.Path) {
			best = f
		}
	}
	return best
}

// LookupOnlyBase finds the one file with this name, if only one has it: a
// link whose folder is wrong may still name it, but not if that's ambiguous.
func (s *Source) LookupOnlyBase(name string) *File {
	if fs := s.byBase[strings.ToLower(name)]; len(fs) == 1 {
		return fs[0]
	}
	return nil
}

// Read returns up to limit bytes of f, and whether it held more.
func (f *File) Read(limit int64) (data []byte, tooLarge bool, err error) {
	r, err := f.open()
	if err != nil {
		return nil, false, err
	}
	defer r.Close()
	data, err = io.ReadAll(io.LimitReader(r, limit+1))
	if err != nil {
		return nil, false, err
	}
	if int64(len(data)) > limit {
		return nil, true, nil
	}
	return data, false, nil
}

// hidden reports a path with a part starting with ".", such as .obsidian,
// .git, .trash or notesd's own .notesd-mirror.json.
func hidden(p string) bool {
	for _, part := range strings.Split(p, "/") {
		if strings.HasPrefix(part, ".") {
			return true
		}
	}
	return false
}
