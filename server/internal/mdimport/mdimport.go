// Package mdimport turns a folder or zip of Markdown files, as most note
// apps (and mdexport) write them, into notes, for `notesd import`.
package mdimport

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"path"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"notes/server/internal/images"
	"notes/server/internal/notes"
	"notes/server/internal/search"
)

// Issue is one thing a report lists, by the file it concerns.
type Issue struct {
	Path    string
	Message string
}

// Note is one note ready to store.
type Note struct {
	Path    string // the file it came from
	ID      string
	Title   string
	Content string
	// Unix ms; 0 leaves the time to the server.
	CreatedAt, UpdatedAt int64
}

// Writer stores what an import makes. A dry run passes one that only looks.
type Writer interface {
	PutImage(ctx context.Context, id, contentType string, data []byte) error
	// CreateNote reports false for a note that is already there.
	CreateNote(ctx context.Context, n Note) (created bool, err error)
}

// Options shape an import.
type Options struct {
	SpaceID string
	// LineBreaks turns single newlines into hard line breaks.
	LineBreaks bool
}

// Report is what an import did and left out.
type Report struct {
	Notes    int // created
	Existing int // already there: imported before, or the same text twice
	Images   int // distinct images the created notes show
	// Links to other notes, which became their text.
	NoteLinks int
	// Remote images, left as written and never downloaded.
	RemoteImages int
	// Files that are neither notes nor images a note shows.
	OtherFiles int
	Skipped    []Issue // files not imported
	Changed    []Issue // notes imported with something left as written or out
}

// Run imports every note file in src.
func Run(ctx context.Context, src *Source, opts Options, w Writer) (Report, error) {
	r := &run{ctx: ctx, src: src, opts: opts, w: w,
		images: map[string]imageResult{}, used: map[string]bool{}, seen: map[string]bool{},
		put: map[string]bool{}, stored: map[string]bool{}}
	r.report.Skipped = append(r.report.Skipped, src.Skipped...)
	for i := range src.Files {
		f := &src.Files[i]
		if !isNote(f.Path) {
			continue
		}
		if err := ctx.Err(); err != nil {
			return r.report, err
		}
		if err := r.note(f); err != nil {
			return r.report, fmt.Errorf("%s: %w", f.Path, err)
		}
	}
	for _, f := range src.Files {
		if !isNote(f.Path) && !r.used[f.Path] {
			r.report.OtherFiles++
		}
	}
	return r.report, nil
}

type imageResult struct{ dest, problem string }

type run struct {
	ctx    context.Context
	src    *Source
	opts   Options
	w      Writer
	report Report
	images map[string]imageResult // by file path, or "data:" + SHA-256
	used   map[string]bool        // files notes show as images
	seen   map[string]bool        // note ids this run
	put    map[string]bool        // image ids stored this run
	stored map[string]bool        // of those, counted in the report
	err    error                  // first write error, which stops the run
}

func (r *run) note(f *File) error {
	data, tooLarge, err := f.Read(maxNoteFileBytes)
	switch {
	case err != nil:
		r.skip(f, "can't be read: "+err.Error())
		return nil
	case tooLarge:
		r.skip(f, "longer than a note can be (1 MiB)")
		return nil
	}
	text, problem := decodeText(data)
	if problem != "" {
		r.skip(f, problem)
		return nil
	}
	n, c, issues := r.convert(f, text)
	if r.err != nil {
		return r.err
	}
	if len(n.Content) > notes.MaxContentBytes {
		r.skip(f, "longer than a note can be (1 MiB)")
		return nil
	}
	if r.seen[n.ID] {
		r.report.Existing++
		return nil
	}
	r.seen[n.ID] = true
	created, err := r.w.CreateNote(r.ctx, n)
	if err != nil {
		return err
	}
	if created {
		// Only for notes written: a rerun reports nothing again.
		r.report.Notes++
		r.report.NoteLinks += c.noteLinks
		r.report.RemoteImages += c.remoteImages
		for _, msg := range issues {
			r.report.Changed = append(r.report.Changed, Issue{f.Path, msg})
		}
		for _, id := range images.Refs(n.Content) {
			if r.put[id] && !r.stored[id] {
				r.stored[id] = true
				r.report.Images++
			}
		}
	} else {
		r.report.Existing++
	}
	return nil
}

func (r *run) skip(f *File, msg string) {
	r.report.Skipped = append(r.report.Skipped, Issue{f.Path, msg})
}

// decodeText reads a file as UTF-8 text with LF line endings.
func decodeText(data []byte) (string, string) {
	if bytes.HasPrefix(data, []byte{0xFE, 0xFF}) || bytes.HasPrefix(data, []byte{0xFF, 0xFE}) {
		return "", "UTF-16 text; save it as UTF-8 to import it"
	}
	data = bytes.TrimPrefix(data, []byte{0xEF, 0xBB, 0xBF})
	if !utf8.Valid(data) || bytes.IndexByte(data, 0) >= 0 {
		return "", "not UTF-8 text"
	}
	text := strings.ReplaceAll(string(data), "\r\n", "\n")
	return strings.ReplaceAll(text, "\r", "\n"), ""
}

func (r *run) convert(f *File, text string) (Note, *converter, []string) {
	var issues []string
	fm, body, _, err := splitFrontMatter(text)
	if err != nil {
		issues = append(issues, err.Error())
	}
	issues = append(issues, fm.problems...)

	title := fm.title
	h1, rest := leadingH1(body)
	switch {
	case title != "" && h1 != "" && strings.EqualFold(h1, title):
		body = rest
	case title == "" && h1 != "":
		title, body = h1, rest
	case title == "":
		title = fileTitle(f.Path)
	}
	title, cut := cleanTitle(title)
	if cut {
		issues = append(issues, "title cut to 1 KiB")
	}

	c := &converter{src: r.src, notePath: f.Path, images: r}
	body = c.rewrite(strings.TrimLeft(body, "\n"))
	if r.opts.LineBreaks {
		body = hardBreaks(body)
	}
	issues = append(issues, c.issues...)

	body = strings.TrimRight(body, " \t\n")
	tags, tagIssues := newTags(title, body, append(fm.tags, folderTag(f.Path)))
	issues = append(issues, tagIssues...)
	if tags != "" {
		if body != "" {
			body += "\n\n"
		}
		body += tags
	}

	updated := fm.updated
	if updated.IsZero() {
		updated = f.ModTime
	}
	created := fm.created
	if created.IsZero() {
		created = updated
	}
	if updated.Before(created) {
		updated = created
	}
	sum := sha256.Sum256([]byte(r.opts.SpaceID + "\x00" + title + "\x00" + body))
	return Note{
		Path:      f.Path,
		ID:        "md-" + hex.EncodeToString(sum[:16]),
		Title:     title,
		Content:   body,
		CreatedAt: unixMilli(created),
		UpdatedAt: unixMilli(updated),
	}, c, issues
}

// newTags is the line of tags to add for names the note doesn't already
// show, such as its front matter's and its folder's.
func newTags(title, body string, names []string) (string, []string) {
	have := map[string]bool{}
	for _, t := range search.Tags(title, body) {
		have[t.Key] = true
	}
	var out, issues []string
	for _, name := range names {
		if strings.TrimSpace(name) == "" {
			continue
		}
		tag := formatTag(name)
		if tag == "" {
			issues = append(issues, fmt.Sprintf("tag %q can't be written as a notesd tag; left out", name))
			continue
		}
		if strings.Contains(name, "#") {
			issues = append(issues, fmt.Sprintf("tag %q written as %s; tags can't hold #", name, tag))
		}
		key := search.TagKey(tag)
		if !have[key] {
			have[key] = true
			out = append(out, tag)
		}
	}
	return strings.Join(out, " "), issues
}

func unixMilli(t time.Time) int64 {
	if t.IsZero() || t.UnixMilli() <= 0 {
		return 0
	}
	return t.UnixMilli()
}

var h1Line = regexp.MustCompile(`^ {0,3}#[ \t]+(.*?)(?:[ \t]+#+)?[ \t]*$`)

// leadingH1 is the text of a `# Heading` on the body's first line, which
// notesd's export writes for the title, and the body after it.
func leadingH1(body string) (string, string) {
	body = strings.TrimLeft(body, "\n")
	line, rest, _ := strings.Cut(body, "\n")
	m := h1Line.FindStringSubmatch(line)
	if m == nil || strings.TrimSpace(m[1]) == "" {
		return "", body
	}
	return strings.TrimSpace(m[1]), strings.TrimLeft(rest, "\n")
}

var notionID = regexp.MustCompile(` [0-9a-f]{32}$`)

// fileTitle is a note's title from its file name, without the ID Notion
// adds to every name.
func fileTitle(p string) string {
	base := path.Base(p)
	return notionID.ReplaceAllString(strings.TrimSuffix(base, path.Ext(base)), "")
}

// cleanTitle makes a title one line that fits notes.MaxTitleBytes.
func cleanTitle(t string) (string, bool) {
	t = strings.Join(strings.Fields(t), " ")
	cut := false
	for len(t) > notes.MaxTitleBytes {
		_, size := utf8.DecodeLastRuneInString(t)
		t, cut = t[:len(t)-size], true
	}
	return t, cut
}

func (r *run) fileImage(f *File) (string, string) {
	if res, ok := r.images[f.Path]; ok {
		return res.dest, res.problem
	}
	var res imageResult
	data, tooLarge, err := f.Read(images.MaxBytes)
	switch {
	case err != nil:
		res.problem = "can't be read: " + err.Error()
	case tooLarge:
		res.problem = "larger than 10 MiB"
	default:
		res = r.store(data)
	}
	r.images[f.Path] = res
	if res.dest != "" {
		r.used[f.Path] = true
	}
	return res.dest, res.problem
}

func (r *run) dataImage(data []byte) (string, string) {
	if len(data) > images.MaxBytes {
		return "", "larger than 10 MiB"
	}
	sum := sha256.Sum256(data)
	key := "data:" + hex.EncodeToString(sum[:])
	if res, ok := r.images[key]; ok {
		return res.dest, res.problem
	}
	res := r.store(data)
	r.images[key] = res
	return res.dest, res.problem
}

// store saves an image as it is: JPEG, PNG, WebP or GIF, never re-encoded.
func (r *run) store(data []byte) imageResult {
	contentType := images.Sniff(data)
	if contentType == "" {
		return imageResult{problem: "not JPEG, PNG, WebP or GIF"}
	}
	w, h, err := imageSize(contentType, data)
	if err != nil {
		return imageResult{problem: "can't read its size: " + err.Error()}
	}
	sum := sha256.Sum256(data)
	id := hex.EncodeToString(sum[:])
	if err := r.w.PutImage(r.ctx, id, contentType, data); err != nil {
		if r.err == nil {
			r.err = fmt.Errorf("store image: %w", err)
		}
		return imageResult{problem: err.Error()}
	}
	r.put[id] = true
	return imageResult{dest: fmt.Sprintf("notesd:image/%s?w=%d&h=%d", id, w, h)}
}
