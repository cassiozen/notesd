package main

import (
	"bufio"
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"golang.org/x/term"
	"golang.org/x/text/unicode/norm"

	"notes/server/internal/database"
	"notes/server/internal/images"
	"notes/server/internal/locks"
	"notes/server/internal/notes"
	"notes/server/internal/spaces"
)

const (
	maxFileNameBytes = 100
	imagesDir        = "images"
)

// exported counts what an export wrote and what it left out.
type exported struct {
	Written       int
	Locked        int // skipped: no key given
	Unreadable    int // locked, but the key didn't open them
	Images        int // image files written to images/
	MissingImages int // left as notesd:image/ references: not on the server, or locked and not opened
}

// getImage returns a stored image's type and bytes, or images.ErrNotFound.
type getImage func(id string) (contentType string, data []byte, err error)

func liveNotes(ctx context.Context, db *database.DB) ([]notes.Note, error) {
	var all []notes.Note
	err := db.ReadTx(ctx, func(tx *sql.Tx) error {
		rows, err := tx.QueryContext(ctx, `SELECT `+notes.Columns+` FROM notes
			WHERE deleted_at IS NULL ORDER BY created_at, id`)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			n, err := notes.ScanNote(rows)
			if err != nil {
				return err
			}
			all = append(all, n)
		}
		return rows.Err()
	})
	if err != nil {
		return nil, fmt.Errorf("read notes: %w", err)
	}
	return all, nil
}

// spaceFolders names a folder for each space (§7.7), or is nil when there is
// only one space and the export stays flat.
func spaceFolders(ctx context.Context, db *database.DB) (map[string]string, error) {
	list, err := spaces.NewStore(db).List(ctx)
	if err != nil || len(list) < 2 {
		return nil, err
	}
	taken := map[string]bool{nameKey(imagesDir): true}
	folders := map[string]string{}
	for _, sp := range list {
		name := fileName(sp.Name)
		for i := 2; taken[nameKey(name)]; i++ {
			name = fmt.Sprintf("%s (%d)", fileName(sp.Name), i)
		}
		taken[nameKey(name)] = true
		folders[sp.ID] = name
	}
	return folders, nil
}

// writeExport writes each note to dir as Markdown, one file each, inside its
// space's folder when folders names them. Locked notes are decrypted with
// key, or skipped without one. The images notes show are written to
// dir/images, and their references pointed there.
func writeExport(dir string, all []notes.Note, folders map[string]string, key []byte, get getImage) (exported, error) {
	var res exported
	if err := checkExportDir(dir); err != nil {
		return res, err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return res, err
	}
	// Per folder, keyed case-insensitively: macOS and Windows filesystems are.
	taken := map[string]map[string]bool{}
	// Image id → its path in the export, or "" if it couldn't be written.
	written := map[string]string{}
	for _, n := range all {
		content := n.Content
		if locks.IsLocked(content) {
			if key == nil {
				res.Locked++
				continue
			}
			plain, err := locks.Decrypt(key, content)
			if err != nil {
				res.Unreadable++
				continue
			}
			content = plain
		}
		folder := folders[n.SpaceID]
		up := ""
		if folder != "" {
			up = "../"
			if err := os.MkdirAll(filepath.Join(dir, folder), 0o700); err != nil {
				return res, err
			}
		}
		content, err := exportImages(dir, up, content, key, get, written, &res)
		if err != nil {
			return res, err
		}
		if taken[folder] == nil {
			taken[folder] = map[string]bool{}
		}
		path := filepath.Join(dir, folder, uniqueName(fileName(n.Title), taken[folder]))
		if err := os.WriteFile(path, []byte(markdown(n.Title, content)), 0o600); err != nil {
			return res, err
		}
		t := time.UnixMilli(n.UpdatedAt)
		if err := os.Chtimes(path, t, t); err != nil {
			return res, err
		}
		res.Written++
	}
	return res, nil
}

// appImageRef is an app image's destination as the apps write it (§7.6).
var appImageRef = regexp.MustCompile(`\]\(notesd:image/([0-9a-f]{64})\?w=[1-9]\d{0,5}&h=[1-9]\d{0,5}\)`)

// exportImages writes the app images content shows to dir/images (once
// each, tracked in written) and points their references at the files, from
// a note up levels below dir ("" or "../"). A locked image is decrypted
// with key; one that can't be had or opened keeps its reference.
func exportImages(dir, up, content string, key []byte, get getImage, written map[string]string, res *exported) (string, error) {
	// Only real images: the same text inside code is left alone.
	shown := map[string]bool{}
	for _, id := range images.Refs(content) {
		shown[id] = true
	}
	if len(shown) == 0 {
		return content, nil
	}
	for id := range shown {
		if _, done := written[id]; done {
			continue
		}
		path, err := writeImage(dir, id, key, get)
		if err != nil {
			return "", err
		}
		written[id] = path
		if path == "" {
			res.MissingImages++
		} else {
			res.Images++
		}
	}
	return appImageRef.ReplaceAllStringFunc(content, func(ref string) string {
		id := appImageRef.FindStringSubmatch(ref)[1]
		if !shown[id] || written[id] == "" {
			return ref
		}
		return "](" + up + written[id] + ")"
	}), nil
}

// writeImage writes one image as a plain image file and returns its path
// relative to dir, or "" if it isn't stored or is locked and key doesn't open it.
func writeImage(dir, id string, key []byte, get getImage) (string, error) {
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
	if err := os.MkdirAll(filepath.Join(dir, imagesDir), 0o700); err != nil {
		return "", err
	}
	path := imagesDir + "/" + id + "." + ext
	if err := os.WriteFile(filepath.Join(dir, filepath.FromSlash(path)), data, 0o600); err != nil {
		return "", err
	}
	return path, nil
}

// checkExportDir accepts a missing or empty dir, so an export never
// overwrites files or mixes with an earlier one.
func checkExportDir(dir string) error {
	entries, err := os.ReadDir(dir)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	if len(entries) > 0 {
		return fmt.Errorf("%s is not empty; export into a new or empty directory", dir)
	}
	return nil
}

// askLockPassword has the owner confirm that locked notes will be written as
// plain text, then reads the lock password. Typed every time, never from the
// environment, so a script or a leaked variable can't decrypt the notes.
func askLockPassword(dir string, locked int, stdin *os.File, stderr io.Writer) (string, error) {
	fd := int(stdin.Fd())
	if !term.IsTerminal(fd) {
		return "", errors.New("--include-locked needs a terminal to type the lock password at")
	}
	fmt.Fprintf(stderr, "%d locked notes, currently encrypted, will be written to %s as plain text.\n"+
		"Anyone who can read that folder can read them. Continue? [y/N] ", locked, dir)
	answer, err := bufio.NewReader(stdin).ReadString('\n')
	if err != nil && !errors.Is(err, io.EOF) {
		return "", err
	}
	if a := strings.ToLower(strings.TrimSpace(answer)); a != "y" && a != "yes" {
		return "", errors.New("export cancelled; nothing was written")
	}
	fmt.Fprint(stderr, "lock password: ")
	raw, err := term.ReadPassword(fd)
	fmt.Fprintln(stderr)
	if err != nil {
		return "", fmt.Errorf("read lock password: %w", err)
	}
	return string(raw), nil
}

// lockKey opens the locked-notes key with the lock password.
func lockKey(ctx context.Context, db *database.DB, password string) ([]byte, error) {
	keys, err := locks.NewStore(db).List(ctx)
	if err != nil {
		return nil, err
	}
	key, err := locks.UnlockWithPassword(keys, password)
	if errors.Is(err, locks.ErrNoPassword) {
		return nil, errors.New("there is no lock password on this server to open locked notes with")
	}
	return key, err
}

// markdown matches the web app's Copy as Markdown: the title as a heading, then the body.
func markdown(title, content string) string {
	if t := strings.TrimSpace(title); t != "" {
		return "# " + t + "\n\n" + content
	}
	return content
}

// fileName turns a title into a name safe on Linux, macOS and Windows, without the extension.
func fileName(title string) string {
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

// nameKey is how filesystems compare names: macOS and Windows ignore case,
// and macOS composed and decomposed forms.
func nameKey(name string) string { return strings.ToLower(norm.NFC.String(name)) }

func uniqueName(base string, taken map[string]bool) string {
	name := base + ".md"
	for i := 2; taken[nameKey(name)]; i++ {
		name = fmt.Sprintf("%s (%d).md", base, i)
	}
	taken[nameKey(name)] = true
	return name
}
