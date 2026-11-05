package mdimport

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"image"
	"image/jpeg"
	"image/png"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"
)

var ctx = context.Background()

type memWriter struct {
	images map[string][]byte
	notes  map[string]Note
}

func newMem() *memWriter { return &memWriter{images: map[string][]byte{}, notes: map[string]Note{}} }

func (m *memWriter) PutImage(_ context.Context, id, _ string, data []byte) error {
	m.images[id] = data
	return nil
}

func (m *memWriter) CreateNote(_ context.Context, n Note) (bool, error) {
	if _, ok := m.notes[n.ID]; ok {
		return false, nil
	}
	m.notes[n.ID] = n
	return true, nil
}

func (m *memWriter) byPath(t *testing.T, p string) Note {
	t.Helper()
	for _, n := range m.notes {
		if n.Path == p {
			return n
		}
	}
	t.Fatalf("no note from %s; have %v", p, m.paths())
	return Note{}
}

func (m *memWriter) paths() []string {
	var ps []string
	for _, n := range m.notes {
		ps = append(ps, n.Path)
	}
	slices.Sort(ps)
	return ps
}

// writeTree writes files (path → content) under a new folder.
func writeTree(t *testing.T, files map[string]string) string {
	t.Helper()
	root := t.TempDir()
	for p, content := range files {
		full := filepath.Join(root, filepath.FromSlash(p))
		if err := os.MkdirAll(filepath.Dir(full), 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(full, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	return root
}

func importTree(t *testing.T, files map[string]string, opts Options) (*memWriter, Report) {
	t.Helper()
	src, err := Open(writeTree(t, files))
	if err != nil {
		t.Fatal(err)
	}
	defer src.Close()
	w := newMem()
	rep, err := Run(ctx, src, opts, w)
	if err != nil {
		t.Fatal(err)
	}
	return w, rep
}

func pngBytes(t *testing.T, w, h int) []byte {
	t.Helper()
	var b bytes.Buffer
	if err := png.Encode(&b, image.NewRGBA(image.Rect(0, 0, w, h))); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

func ref(data []byte, w, h int) string {
	sum := sha256.Sum256(data)
	return fmt.Sprintf("notesd:image/%s?w=%d&h=%d", hex.EncodeToString(sum[:]), w, h)
}

func TestTitleAndFrontMatter(t *testing.T) {
	w, rep := importTree(t, map[string]string{
		"fm.md":    "---\ntitle: From Front Matter\ncreated: 2024-01-02T03:04:05Z\nupdated: 2024-02-03 10:00\ntags: [work, Project X, \"#a/b\"]\naliases: [x]\n---\n# From Front Matter\n\nBody\n",
		"h1.md":    "\n# Heading Title #\n\nBody",
		"plain.md": "Just text",
		"Page abcdef0123456789abcdef0123456789.md": "Notion page",
		"rule.md":  "---\nNot yaml, a rule and a heading\n---\n",
		"bad.md":   "---\ntitle: [unclosed\n---\nText",
		"comma.md": "---\ntags: one, two words\ndate: 1700000000\n---\nx",
	}, Options{})
	if rep.Notes != 7 {
		t.Fatalf("report = %+v", rep)
	}
	fm := w.byPath(t, "fm.md")
	if fm.Title != "From Front Matter" || fm.Content != "Body\n\n#work #Project X# #a/b" {
		t.Errorf("fm = %q %q", fm.Title, fm.Content)
	}
	if want := time.Date(2024, 1, 2, 3, 4, 5, 0, time.UTC).UnixMilli(); fm.CreatedAt != want {
		t.Errorf("created = %d, want %d", fm.CreatedAt, want)
	}
	if want := time.Date(2024, 2, 3, 10, 0, 0, 0, time.Local).UnixMilli(); fm.UpdatedAt != want {
		t.Errorf("updated = %d, want %d", fm.UpdatedAt, want)
	}
	if h1 := w.byPath(t, "h1.md"); h1.Title != "Heading Title" || h1.Content != "Body" {
		t.Errorf("h1 = %q %q", h1.Title, h1.Content)
	}
	if p := w.byPath(t, "plain.md"); p.Title != "plain" || p.Content != "Just text" {
		t.Errorf("plain = %q %q", p.Title, p.Content)
	}
	if n := w.byPath(t, "Page abcdef0123456789abcdef0123456789.md"); n.Title != "Page" {
		t.Errorf("notion title = %q", n.Title)
	}
	if r := w.byPath(t, "rule.md"); r.Content != "---\nNot yaml, a rule and a heading\n---" {
		t.Errorf("rule = %q", r.Content)
	}
	if c := w.byPath(t, "comma.md"); c.Content != "x\n\n#one #two words#" || c.CreatedAt != 1_700_000_000_000 {
		t.Errorf("comma = %q %d", c.Content, c.CreatedAt)
	}
	if len(rep.Changed) != 1 || rep.Changed[0].Path != "bad.md" || !strings.Contains(rep.Changed[0].Message, "front matter not read") {
		t.Errorf("changed = %+v", rep.Changed)
	}
}

func TestFolderTagsAndExistingTags(t *testing.T) {
	w, _ := importTree(t, map[string]string{
		"Work/Projects/a.md": "Has #work/projects already",
		"2024/b.md":          "---\ntags: [Home]\n---\nAlso #home",
		"top.md":             "No folder",
	}, Options{})
	if c := w.byPath(t, "Work/Projects/a.md").Content; c != "Has #work/projects already" {
		t.Errorf("a = %q", c)
	}
	if c := w.byPath(t, "2024/b.md").Content; c != "Also #home\n\n#2024#" {
		t.Errorf("b = %q", c)
	}
	if c := w.byPath(t, "top.md").Content; c != "No folder" {
		t.Errorf("top = %q", c)
	}
}

func TestHardBreaks(t *testing.T) {
	for _, tc := range []struct{ in, want string }{
		{"one\ntwo", "one\\\ntwo"},
		{"one  \ntwo", "one\\\ntwo"},
		{"one \ntwo", "one\\\ntwo"},
		{"one\\\ntwo", "one\\\ntwo"},
		{"a\n\nb", "a\n\nb"},
		{"- a\n  b\n- c", "- a\\\n  b\n- c"},
		{"> q\n> r", "> q\\\n> r"},
		{"```\nx\ny\n```", "```\nx\ny\n```"},
		{"a `code\nspan` b", "a `code\nspan` b"},
		{"Heading\n===", "Heading\n==="},
		{"**bold\nstill**", "**bold\\\nstill**"},
	} {
		if got := hardBreaks(tc.in); got != tc.want {
			t.Errorf("hardBreaks(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestLineBreaksOption(t *testing.T) {
	files := map[string]string{"n.md": "one\ntwo"}
	w, _ := importTree(t, files, Options{LineBreaks: true})
	if c := w.byPath(t, "n.md").Content; c != "one\\\ntwo" {
		t.Errorf("with = %q", c)
	}
	w, _ = importTree(t, files, Options{})
	if c := w.byPath(t, "n.md").Content; c != "one\ntwo" {
		t.Errorf("without = %q", c)
	}
}

func TestImages(t *testing.T) {
	img := pngBytes(t, 4, 3)
	other := pngBytes(t, 2, 5)
	r, ro := ref(img, 4, 3), ref(other, 2, 5)
	data := base64.StdEncoding.EncodeToString(other)
	w, rep := importTree(t, map[string]string{
		"notes/n.md": strings.Join([]string{
			"![rel](../_resources/pic.png)",
			"![enc](../_resources/my%20pic.png \"title\")",
			"![angle](<../_resources/my pic.png>)",
			"![[pic.png|300]] ![[pic.png|Alt *x*]]",
			`Inline <img src="../_resources/pic.png" width="300"> tag`,
			"![data](data:image/png;base64," + data + ")",
			"![web](https://example.com/a.png)",
			"![svg](../_resources/logo.svg)",
			"![gone](missing.png)",
			"`![code](../_resources/pic.png)`",
			`\[escaped](../_resources/pic.png)`,
			"[![linked](../_resources/pic.png)](https://example.com)",
		}, "\n\n") + "\n\n<img src=\"../_resources/pic.png\">\n\n```\n![fence](../_resources/pic.png)\n```\n",
		"_resources/pic.png":    string(img),
		"_resources/my pic.png": string(img),
		"_resources/logo.svg":   "<svg/>",
		"_resources/unused.pdf": "%PDF",
	}, Options{})
	got := w.byPath(t, "notes/n.md").Content
	want := strings.Join([]string{
		"![rel](" + r + ")",
		"![enc](" + r + ")",
		"![angle](" + r + ")",
		"![](" + r + ") ![Alt \\*x\\*](" + r + ")",
		"Inline ![](" + r + ") tag",
		"![data](" + ro + ")",
		"![web](https://example.com/a.png)",
		"![svg](../_resources/logo.svg)",
		"![gone](missing.png)",
		"`![code](../_resources/pic.png)`",
		`\[escaped](../_resources/pic.png)`,
		"[![linked](" + r + ")](https://example.com)",
	}, "\n\n") + "\n\n![](" + r + ")\n\n```\n![fence](../_resources/pic.png)\n```\n\n#notes"
	if got != want {
		t.Errorf("content =\n%s\nwant\n%s", got, want)
	}
	if len(w.images) != 2 || rep.Images != 2 || rep.RemoteImages != 1 {
		t.Errorf("images = %d, report = %+v", len(w.images), rep)
	}
	if rep.OtherFiles != 2 { // logo.svg, unused.pdf
		t.Errorf("other files = %d", rep.OtherFiles)
	}
	var msgs []string
	for _, is := range rep.Changed {
		msgs = append(msgs, is.Message)
	}
	if len(msgs) != 2 || !strings.Contains(msgs[0], "not JPEG, PNG, WebP or GIF") || !strings.Contains(msgs[1], "isn't in the import") {
		t.Errorf("changed = %q", msgs)
	}
}

func TestLinks(t *testing.T) {
	w, rep := importTree(t, map[string]string{
		"a.md": strings.Join([]string{
			"[Other](b.md) and [web](https://example.com) and [anchor](#h)",
			"[[b]] [[b|the alias]] [[b#Part]] [[#Local]]",
			"[doc](files/doc.pdf) ![[files/doc.pdf]]",
			"[gone](nowhere/c.md)",
			"`[[code]]` and `[c](b.md)`",
			"[a `code` text](b.md)",
		}, "\n\n"),
		"b.md":          "B",
		"files/doc.pdf": "%PDF",
	}, Options{})
	got := w.byPath(t, "a.md").Content
	want := strings.Join([]string{
		"Other and [web](https://example.com) and [anchor](#h)",
		"b the alias b > Part Local",
		"doc files/doc.pdf",
		"gone",
		"`[[code]]` and `[c](b.md)`",
		"a `code` text",
	}, "\n\n")
	if got != want {
		t.Errorf("content =\n%s\nwant\n%s", got, want)
	}
	if rep.NoteLinks != 7 {
		t.Errorf("note links = %d", rep.NoteLinks)
	}
	if len(rep.Changed) != 2 {
		t.Errorf("changed = %+v", rep.Changed)
	}
}

func TestSkipsAndRepeats(t *testing.T) {
	root := writeTree(t, map[string]string{
		"a.md":                "Same",
		"copy/a.md":           "Same",
		"latin1.md":           "caf\xe9",
		"utf16.md":            "\xff\xfeh\x00i\x00",
		".obsidian/x.md":      "hidden",
		"big.md":              strings.Repeat("x", maxNoteFileBytes+1),
		"long.md":             "[[b]] ![](https://example.com/a.png) " + strings.Repeat("x", 1<<20+1),
		"crlf.txt":            "\xef\xbb\xbfline one\r\nline two\r\n",
		"notes.markdown":      "md",
		"picture.png":         "not really",
		".notesd-mirror.json": "{}",
	})
	outside := filepath.Join(t.TempDir(), "outside.md")
	if err := os.WriteFile(outside, []byte("secret"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, filepath.Join(root, "link.md")); err != nil {
		t.Fatal(err)
	}
	src, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	w := newMem()
	rep, err := Run(ctx, src, Options{}, w)
	if err != nil {
		t.Fatal(err)
	}
	// a.md and copy/a.md differ by folder tag only in copy/.
	if want := []string{"a.md", "copy/a.md", "crlf.txt", "notes.markdown"}; !slices.Equal(w.paths(), want) {
		t.Errorf("imported %v, want %v", w.paths(), want)
	}
	if c := w.byPath(t, "crlf.txt").Content; c != "line one\nline two" {
		t.Errorf("crlf = %q", c)
	}
	skipped := map[string]string{}
	for _, is := range rep.Skipped {
		skipped[is.Path] = is.Message
	}
	for p, want := range map[string]string{
		"latin1.md": "not UTF-8", "utf16.md": "UTF-16", "big.md": "1 MiB", "long.md": "1 MiB", "link.md": "symbolic link",
	} {
		if !strings.Contains(skipped[p], want) {
			t.Errorf("skipped[%s] = %q, want %q", p, skipped[p], want)
		}
	}
	if len(skipped) != 5 {
		t.Errorf("skipped = %v", skipped)
	}
	if rep.NoteLinks != 0 || rep.RemoteImages != 0 {
		t.Errorf("counted a skipped note: %+v", rep)
	}

	// Again: everything is already there.
	rep, err = Run(ctx, src, Options{}, w)
	if err != nil {
		t.Fatal(err)
	}
	if rep.Notes != 0 || rep.Existing != 4 || rep.NoteLinks != 0 || len(rep.Changed) != 0 {
		t.Errorf("second run = %+v", rep)
	}
	// Another space makes other notes.
	rep, _ = Run(ctx, src, Options{SpaceID: "work"}, w)
	if rep.Notes != 4 {
		t.Errorf("other space = %+v", rep)
	}
}

func TestDuplicateTextOnce(t *testing.T) {
	_, rep := importTree(t, map[string]string{"x.md": "# T\n\nsame", "y.md": "# T\n\nsame"}, Options{})
	if rep.Notes != 1 || rep.Existing != 1 {
		t.Errorf("report = %+v", rep)
	}
}

func writeZip(t *testing.T, files map[string]string) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "notes.zip")
	f, err := os.Create(p)
	if err != nil {
		t.Fatal(err)
	}
	zw := zip.NewWriter(f)
	names := make([]string, 0, len(files))
	for name := range files {
		names = append(names, name)
	}
	slices.Sort(names)
	for _, name := range names {
		h := &zip.FileHeader{Name: name, Method: zip.Deflate, Modified: time.Date(2023, 5, 6, 7, 8, 9, 0, time.UTC)}
		fw, err := zw.CreateHeader(h)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := fw.Write([]byte(files[name])); err != nil {
			t.Fatal(err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestZip(t *testing.T) {
	img := pngBytes(t, 1, 1)
	p := writeZip(t, map[string]string{
		"vault/Daily/today.md":      "![](../img/p.png)",
		"vault/img/p.png":           string(img),
		"vault/__MACOSX/._today.md": "junk",
		"vault/.trash/old.md":       "old",
		"vault/../escape.md":        "nope",
		"vault/sub/../ok.md":        "ok",
	})
	src, err := Open(p)
	if err != nil {
		t.Fatal(err)
	}
	defer src.Close()
	w := newMem()
	rep, err := Run(ctx, src, Options{}, w)
	if err != nil {
		t.Fatal(err)
	}
	// vault/../escape.md cleans to escape.md: inside the archive, so the
	// common folder isn't stripped.
	if want := []string{"escape.md", "vault/Daily/today.md", "vault/ok.md"}; !slices.Equal(w.paths(), want) {
		t.Fatalf("imported %v, want %v (report %+v)", w.paths(), want, rep)
	}
	n := w.byPath(t, "vault/Daily/today.md")
	if n.Content != "![]("+ref(img, 1, 1)+")\n\n#vault/Daily" {
		t.Errorf("content = %q", n.Content)
	}
	if want := time.Date(2023, 5, 6, 7, 8, 9, 0, time.UTC).UnixMilli(); n.UpdatedAt != want || n.CreatedAt != want {
		t.Errorf("times = %d %d, want %d", n.CreatedAt, n.UpdatedAt, want)
	}
}

func TestZipCommonFolderAndTraversal(t *testing.T) {
	p := writeZip(t, map[string]string{
		"vault/a.md":    "a",
		"vault/b/c.md":  "c",
		"../../evil.md": "evil",
		"/abs.md":       "abs",
	})
	src, err := Open(p)
	if err != nil {
		t.Fatal(err)
	}
	defer src.Close()
	var paths []string
	for _, f := range src.Files {
		paths = append(paths, f.Path)
	}
	if want := []string{"a.md", "b/c.md"}; !slices.Equal(paths, want) {
		t.Errorf("files = %v, want %v", paths, want)
	}
	if len(src.Skipped) != 2 {
		t.Errorf("skipped = %+v", src.Skipped)
	}
}

func TestImageSize(t *testing.T) {
	if w, h, err := imageSize("image/png", pngBytes(t, 7, 3)); err != nil || w != 7 || h != 3 {
		t.Errorf("png = %d %d %v", w, h, err)
	}

	var jb bytes.Buffer
	if err := jpeg.Encode(&jb, image.NewRGBA(image.Rect(0, 0, 8, 2)), nil); err != nil {
		t.Fatal(err)
	}
	plain := jb.Bytes()
	if w, h, err := imageSize("image/jpeg", plain); err != nil || w != 8 || h != 2 {
		t.Errorf("jpeg = %d %d %v", w, h, err)
	}
	// The same JPEG with EXIF orientation 6 (rotated 90°) shows 2×8.
	tiff := []byte{'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0, 0, 0}
	seg := append([]byte("Exif\x00\x00"), tiff...)
	app1 := append([]byte{0xFF, 0xE1, 0, 0}, seg...)
	binary.BigEndian.PutUint16(app1[2:], uint16(len(seg)+2))
	rotated := append(append(append([]byte{}, plain[:2]...), app1...), plain[2:]...)
	if w, h, err := imageSize("image/jpeg", rotated); err != nil || w != 2 || h != 8 {
		t.Errorf("rotated jpeg = %d %d %v", w, h, err)
	}

	riff := func(chunk string, payload []byte) []byte {
		b := append([]byte("RIFF\x00\x00\x00\x00WEBP"+chunk+"\x00\x00\x00\x00"), payload...)
		return append(b, make([]byte, 16)...)
	}
	bits := make([]byte, 4)
	binary.LittleEndian.PutUint32(bits, 99|49<<14)
	lossless := riff("VP8L", append([]byte{0x2f}, bits...))
	if w, h, err := imageSize("image/webp", lossless); err != nil || w != 100 || h != 50 {
		t.Errorf("webp lossless = %d %d %v", w, h, err)
	}
	lossy := riff("VP8 ", []byte{0, 0, 0, 0x9d, 0x01, 0x2a, 30, 0, 20, 0})
	if w, h, err := imageSize("image/webp", lossy); err != nil || w != 30 || h != 20 {
		t.Errorf("webp lossy = %d %d %v", w, h, err)
	}
	extended := riff("VP8X", []byte{0, 0, 0, 0, 199, 0, 0, 99, 0, 0})
	if w, h, err := imageSize("image/webp", extended); err != nil || w != 200 || h != 100 {
		t.Errorf("webp extended = %d %d %v", w, h, err)
	}
	if _, _, err := imageSize("image/webp", []byte("RIFF")); err == nil {
		t.Error("short webp read")
	}
}

func TestFormatTag(t *testing.T) {
	for name, want := range map[string]string{
		"work":         "#work",
		"a/b":          "#a/b",
		"two words":    "#two words#",
		"2024":         "#2024#",
		"Work (old)/x": "#Work (old)/x#",
		"c#":           "#c",
		"":             "",
		"   ":          "",
	} {
		if got := formatTag(name); got != want {
			t.Errorf("formatTag(%q) = %q, want %q", name, got, want)
		}
	}
}

func TestImageLookups(t *testing.T) {
	img := pngBytes(t, 4, 3)
	r := ref(img, 4, 3)
	w, rep := importTree(t, map[string]string{
		"n.md": strings.Join([]string{
			"![proto](//pic.png)",
			`<img src="https://example.com/a.png">`,
			"![dup](gone/dup.png)",
			"![one](gone/one.png)",
			"![r][pic]\n\n[pic]: pic.png",
			"![w][web]\n\n[web]: https://example.com/b.png",
		}, "\n\n"),
		"pic.png":   string(img),
		"x/dup.png": string(img),
		"y/dup.png": string(img),
		"z/one.png": string(img),
	}, Options{})
	want := strings.Join([]string{
		"![proto](//pic.png)",
		`<img src="https://example.com/a.png">`,
		"![dup](gone/dup.png)",
		"![one](" + r + ")",
		"![r][pic]\n\n[pic]: pic.png",
		"![w][web]\n\n[web]: https://example.com/b.png",
	}, "\n\n")
	if got := w.byPath(t, "n.md").Content; got != want {
		t.Errorf("content =\n%s\nwant\n%s", got, want)
	}
	if rep.RemoteImages != 3 {
		t.Errorf("remote images = %d", rep.RemoteImages)
	}
	var msgs []string
	for _, is := range rep.Changed {
		msgs = append(msgs, is.Message)
	}
	if len(msgs) != 2 || !strings.Contains(msgs[0], "gone/dup.png isn't in the import") || !strings.Contains(msgs[1], "pic.png left as written") {
		t.Errorf("changed = %q", msgs)
	}
}

func TestFrontMatterOddities(t *testing.T) {
	long := strings.Repeat("é", 600)
	w, rep := importTree(t, map[string]string{
		"num.md":  "---\ndate: 20240115\ntags: [C#, \"#multi word#\"]\n---\nx",
		"long.md": "# " + long + "\n\nbody",
	}, Options{})
	n := w.byPath(t, "num.md")
	if n.Content != "x\n\n#C #multi word#" {
		t.Errorf("content = %q", n.Content)
	}
	if time.UnixMilli(n.CreatedAt).Year() < 2001 {
		t.Errorf("created = %v", time.UnixMilli(n.CreatedAt))
	}
	if l := w.byPath(t, "long.md"); len(l.Title) != 1024 {
		t.Errorf("title is %d bytes", len(l.Title))
	}
	got := map[string][]string{}
	for _, is := range rep.Changed {
		got[is.Path] = append(got[is.Path], is.Message)
	}
	if m := got["num.md"]; len(m) != 2 || !strings.Contains(m[0], "isn't a date") || !strings.Contains(m[1], `"C#" written as #C`) {
		t.Errorf("num.md issues = %q", m)
	}
	if m := got["long.md"]; len(m) != 1 || m[0] != "title cut to 1 KiB" {
		t.Errorf("long.md issues = %q", m)
	}
}

func TestFolderLinks(t *testing.T) {
	outside := writeTree(t, map[string]string{"sub/a.md": "secret"})
	root := writeTree(t, map[string]string{"sub/a.md": "mine", "b.md": "b"})
	link := filepath.Join(t.TempDir(), "vault")
	if err := os.Symlink(root, link); err != nil {
		t.Fatal(err)
	}
	src, err := Open(link)
	if err != nil {
		t.Fatal(err)
	}
	defer src.Close()
	if len(src.Files) != 2 {
		t.Fatalf("files through a linked root = %d", len(src.Files))
	}
	// A folder swapped for a link after the walk isn't followed.
	if err := os.RemoveAll(filepath.Join(root, "sub")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(outside, "sub"), filepath.Join(root, "sub")); err != nil {
		t.Fatal(err)
	}
	w := newMem()
	rep, err := Run(ctx, src, Options{}, w)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(w.paths(), []string{"b.md"}) || len(rep.Skipped) != 1 || rep.Skipped[0].Path != "sub/a.md" {
		t.Errorf("imported %v, skipped %+v", w.paths(), rep.Skipped)
	}
}

func TestRerunReportsNothingAgain(t *testing.T) {
	src, err := Open(writeTree(t, map[string]string{
		"a.md": "[[b]] ![](https://example.com/a.png) [doc](doc.pdf)",
		"b.md": "b",
	}))
	if err != nil {
		t.Fatal(err)
	}
	defer src.Close()
	w := newMem()
	if rep, err := Run(ctx, src, Options{}, w); err != nil || rep.NoteLinks != 1 || rep.RemoteImages != 1 || len(rep.Changed) != 1 {
		t.Fatalf("first run = %+v, %v", rep, err)
	}
	rep, err := Run(ctx, src, Options{}, w)
	if err != nil || rep.Existing != 2 || rep.NoteLinks != 0 || rep.RemoteImages != 0 || len(rep.Changed) != 0 {
		t.Errorf("second run = %+v, %v", rep, err)
	}
}
