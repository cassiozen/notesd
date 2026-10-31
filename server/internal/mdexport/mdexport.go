// Package mdexport writes notes as plain Markdown files and app images as
// plain image files, for `notesd export` and the server's Markdown mirror.
package mdexport

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"

	"golang.org/x/text/unicode/norm"

	"notes/server/internal/images"
	"notes/server/internal/locks"
)

const maxFileNameBytes = 100

// ImagesDir is the folder, beside the notes, that images are written to.
const ImagesDir = "images"

// GetImage returns a stored image's type and bytes, or images.ErrNotFound.
type GetImage func(id string) (contentType string, data []byte, err error)

// Markdown matches the web app's Copy as Markdown: the title as a heading, then the body.
func Markdown(title, content string) string {
	if t := strings.TrimSpace(title); t != "" {
		return "# " + t + "\n\n" + content
	}
	return content
}

// FileName turns a title into a name safe on Linux, macOS and Windows, without the extension.
func FileName(title string) string {
	// Composed, as macOS and Windows compare names: "é" typed either way is one file.
	s := strings.Map(func(r rune) rune {
		if unicode.IsControl(r) || strings.ContainsRune(`/\:*?"<>|`, r) {
			return ' '
		}
		return r
	}, norm.NFC.String(title))
	s = strings.Join(strings.Fields(s), " ")
	if len(s) > maxFileNameBytes {
		s = s[:maxFileNameBytes]
		for !utf8.ValidString(s) {
			s = s[:len(s)-1]
		}
	}
	// Leading dots hide the file; Windows drops trailing dots and spaces.
	s = strings.TrimLeft(s, ".")
	s = strings.TrimRight(s, ". ")
	if s == "" {
		return "Untitled"
	}
	// Windows refuses its device names, with any extension: "CON", "con.md".
	if stem, _, _ := strings.Cut(s, "."); reservedNames[strings.ToUpper(strings.TrimRight(stem, " "))] {
		s = "_" + s
	}
	return s
}

// reservedNames are Windows device names, unusable as a file or folder name.
var reservedNames = func() map[string]bool {
	m := map[string]bool{"CON": true, "PRN": true, "AUX": true, "NUL": true}
	for i := 1; i <= 9; i++ {
		m[fmt.Sprintf("COM%d", i)] = true
		m[fmt.Sprintf("LPT%d", i)] = true
	}
	return m
}()

// NameKey is how filesystems compare names: macOS and Windows ignore case,
// and macOS composed and decomposed forms.
func NameKey(name string) string { return strings.ToLower(norm.NFC.String(name)) }

// Numbered is the i-th name for base: base itself, then "base (2)"….
func Numbered(base string, i int) string {
	if i < 2 {
		return base
	}
	return fmt.Sprintf("%s (%d)", base, i)
}

// UniqueName is the first of base.md, "base (2).md"… not in taken, which it
// then adds.
func UniqueName(base string, taken map[string]bool) string {
	name := base + ".md"
	for i := 2; taken[NameKey(name)]; i++ {
		name = Numbered(base, i) + ".md"
	}
	taken[NameKey(name)] = true
	return name
}

// appImageRef is an app image's destination as the apps write it (§7.6).
var appImageRef = regexp.MustCompile(`\]\(notesd:image/([0-9a-f]{64})\?w=[1-9]\d{0,5}&h=[1-9]\d{0,5}\)`)

// PointImages points references to paths' images (id → path from the root)
// at those files. Only images.Refs ids: the same text inside code stays.
func PointImages(content, up string, paths map[string]string) string {
	if len(paths) == 0 {
		return content
	}
	return appImageRef.ReplaceAllStringFunc(content, func(ref string) string {
		path := paths[appImageRef.FindStringSubmatch(ref)[1]]
		if path == "" {
			return ref
		}
		return "](" + up + path + ")"
	})
}

// WriteImage writes one image to dir/images and returns its path from dir,
// or "" if it isn't stored or is locked and key doesn't open it.
func WriteImage(dir, id string, key []byte, get GetImage) (string, error) {
	contentType, data, err := get(id)
	if errors.Is(err, images.ErrNotFound) {
		return "", nil
	}
	if err != nil {
		return "", fmt.Errorf("image %s: %w", id, err)
	}
	if contentType == images.Locked {
		if key == nil {
			return "", nil
		}
		if data, err = locks.DecryptImage(key, data); err != nil {
			return "", nil
		}
		contentType = images.Sniff(data)
	}
	ext := images.Ext(contentType)
	if ext == "" {
		return "", nil
	}
	if err := os.MkdirAll(filepath.Join(dir, ImagesDir), 0o700); err != nil {
		return "", err
	}
	path := ImagesDir + "/" + id + "." + ext
	if err := WriteFileAtomic(filepath.Join(dir, filepath.FromSlash(path)), data); err != nil {
		return "", err
	}
	return path, nil
}

// WriteFileAtomic replaces path with data through a temporary file beside
// it, so a reader such as a sync tool never sees half a file.
func WriteFileAtomic(path string, data []byte) error {
	f, err := os.CreateTemp(filepath.Dir(path), ".notesd-*.tmp")
	if err != nil {
		return err
	}
	tmp := f.Name()
	_, err = f.Write(data)
	if err == nil {
		err = f.Chmod(0o600)
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = os.Rename(tmp, path)
	}
	if err != nil {
		os.Remove(tmp)
	}
	return err
}
