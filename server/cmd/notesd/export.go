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
	"strings"
	"time"

	"golang.org/x/term"

	"notes/server/internal/database"
	"notes/server/internal/images"
	"notes/server/internal/locks"
	"notes/server/internal/mdexport"
	"notes/server/internal/notes"
	"notes/server/internal/spaces"
)

// exported counts what an export wrote and what it left out.
type exported struct {
	Written       int
	Locked        int // skipped: no key given
	Unreadable    int // locked, but the key didn't open them
	Images        int // image files written to images/
	MissingImages int // left as notesd:image/ references: not on the server, or locked and not opened
}

type getImage = mdexport.GetImage

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
	taken := map[string]bool{mdexport.NameKey(mdexport.ImagesDir): true}
	folders := map[string]string{}
	for _, sp := range list {
		name := mdexport.FileName(sp.Name)
		for i := 2; taken[mdexport.NameKey(name)]; i++ {
			name = mdexport.Numbered(mdexport.FileName(sp.Name), i)
		}
		taken[mdexport.NameKey(name)] = true
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
		path := filepath.Join(dir, folder, mdexport.UniqueName(mdexport.FileName(n.Title), taken[folder]))
		if err := os.WriteFile(path, []byte(mdexport.Markdown(n.Title, content)), 0o600); err != nil {
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

// exportImages writes the app images content shows to dir/images (once
// each, tracked in written) and points their references at the files.
func exportImages(dir, up, content string, key []byte, get getImage, written map[string]string, res *exported) (string, error) {
	shown := map[string]string{}
	for _, id := range images.Refs(content) {
		if _, done := written[id]; !done {
			path, err := mdexport.WriteImage(dir, id, key, get)
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
		shown[id] = written[id]
	}
	return mdexport.PointImages(content, up, shown), nil
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
