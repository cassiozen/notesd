package mdimport

import (
	"encoding/base64"
	"html"
	"net/url"
	"path"
	"regexp"
	"sort"
	"strings"

	"github.com/yuin/goldmark"
	"github.com/yuin/goldmark/ast"
	"github.com/yuin/goldmark/extension"
	east "github.com/yuin/goldmark/extension/ast"
	"github.com/yuin/goldmark/text"
)

var parser = goldmark.New(goldmark.WithExtensions(extension.GFM)).Parser()

func parse(src []byte) ast.Node { return parser.Parse(text.NewReader(src)) }

// edit replaces src[start:end].
type edit struct {
	start, end int
	text       string
}

func applyEdits(src string, edits []edit) string {
	if len(edits) == 0 {
		return src
	}
	sort.Slice(edits, func(i, j int) bool { return edits[i].start < edits[j].start })
	var b strings.Builder
	at := 0
	for _, e := range edits {
		if e.start < at {
			continue // overlaps the previous edit; keep the source
		}
		b.WriteString(src[at:e.start])
		b.WriteString(e.text)
		at = e.end
	}
	b.WriteString(src[at:])
	return b.String()
}

// imageResolver stores a file or inline data as an app image, returning its
// `notesd:image/…` destination, or "" and why not.
type imageResolver interface {
	fileImage(f *File) (dest, problem string)
	dataImage(data []byte) (dest, problem string)
}

// converter rewrites one note's body.
type converter struct {
	src      *Source
	notePath string
	images   imageResolver
	issues   []string
	// Links to other notes made plain text: counted, not listed, since a
	// vault can hold thousands.
	noteLinks int
	// Remote images left as they are, never fetched.
	remoteImages int
}

// rewrite points images at stored app images and turns links to other
// files, which notesd can't follow, into their text.
func (c *converter) rewrite(body string) string {
	src := []byte(body)
	doc := parse(src)
	open := make([]bool, len(src)) // where inline syntax is read
	imageDests := map[string]int{}
	linkDests := map[string]int{}
	var edits []edit
	_ = ast.Walk(doc, func(n ast.Node, entering bool) (ast.WalkStatus, error) {
		if !entering {
			return ast.WalkContinue, nil
		}
		switch x := n.(type) {
		case *ast.Paragraph, *ast.Heading, *ast.TextBlock:
			markLines(open, n, true)
		case *ast.HTMLBlock:
			if e, ok := c.htmlBlockImage(src, x); ok {
				edits = append(edits, e)
			}
		case *ast.CodeSpan:
			if first, last := x.FirstChild(), x.LastChild(); first != nil {
				if a, ok := first.(*ast.Text); ok {
					if b, ok := last.(*ast.Text); ok {
						mark(open, a.Segment.Start, b.Segment.Stop, false)
					}
				}
			}
			return ast.WalkSkipChildren, nil
		case *ast.RawHTML:
			for i := 0; i < x.Segments.Len(); i++ {
				s := x.Segments.At(i)
				mark(open, s.Start, s.Stop, false)
			}
			if x.Segments.Len() == 1 {
				s := x.Segments.At(0)
				if repl, ok := c.htmlImage(string(s.Value(src))); ok {
					edits = append(edits, edit{s.Start, s.Stop, repl})
				}
			}
		case *ast.Image:
			imageDests[string(x.Destination)]++
		case *ast.Link:
			linkDests[string(x.Destination)]++
		}
		if n.Kind() == east.KindTableCell {
			markLines(open, n, true)
		}
		return ast.WalkContinue, nil
	})
	edits = append(edits, c.scan(src, open, imageDests, linkDests)...)
	// Left: images scan doesn't read, such as `![alt][ref]` references.
	var left []string
	for dest, n := range imageDests {
		if _, p, ok := c.local(dest); ok && n > 0 {
			left = append(left, "image "+p+", written as a reference, left as written")
		}
	}
	sort.Strings(left)
	c.issues = append(c.issues, left...)
	return applyEdits(body, edits)
}

func markLines(open []bool, n ast.Node, v bool) {
	lines := n.Lines()
	for i := 0; i < lines.Len(); i++ {
		s := lines.At(i)
		mark(open, s.Start, s.Stop, v)
	}
}

func mark(open []bool, start, stop int, v bool) {
	for i := max(start, 0); i < stop && i < len(open); i++ {
		open[i] = v
	}
}

// scan finds links, images and wiki-links where open allows. A candidate
// counts only if goldmark parsed its destination too: lookalikes stay.
func (c *converter) scan(src []byte, open []bool, imageDests, linkDests map[string]int) []edit {
	var edits []edit
	for i := 0; i < len(src); i++ {
		if !open[i] {
			continue
		}
		switch {
		case src[i] == '\\':
			i++
		case src[i] == '!' && hasAt(src, i+1, "[["):
			if end, inner, ok := wikiLink(src, open, i+1); ok {
				edits = append(edits, edit{i, end, c.wikiEmbed(inner)})
				i = end - 1
			}
		case hasAt(src, i, "[["):
			if end, inner, ok := wikiLink(src, open, i); ok {
				edits = append(edits, edit{i, end, c.wikiText(inner)})
				c.noteLinks++
				i = end - 1
			}
		case src[i] == '!' && hasAt(src, i+1, "["):
			l, ok := inlineLink(src, open, i+1)
			if !ok || imageDests[l.rawDest] == 0 {
				continue
			}
			imageDests[l.rawDest]--
			if repl, ok := c.image(string(src[l.textStart:l.textEnd]), l.rawDest); ok {
				edits = append(edits, edit{i, l.end, repl})
			}
			i = l.end - 1
		case src[i] == '[':
			l, ok := inlineLink(src, open, i)
			if !ok || linkDests[l.rawDest] == 0 {
				continue
			}
			linkDests[l.rawDest]--
			if c.dropLink(l.rawDest) {
				// Keep the text, which may hold images still to rewrite.
				edits = append(edits, edit{i, i + 1, ""}, edit{l.textEnd, l.end, ""})
			}
		}
	}
	return edits
}

func hasAt(src []byte, i int, s string) bool {
	return i+len(s) <= len(src) && string(src[i:i+len(s)]) == s
}

// wikiLink reads `[[…]]` at i on one line, returning its end and inside.
func wikiLink(src []byte, open []bool, i int) (end int, inner string, ok bool) {
	for j := i + 2; j+1 < len(src); j++ {
		switch {
		case !open[j] || src[j] == '\n' || src[j] == '[':
			return 0, "", false
		case src[j] == ']':
			if src[j+1] != ']' || j == i+2 {
				return 0, "", false
			}
			return j + 2, string(src[i+2 : j]), true
		}
	}
	return 0, "", false
}

type link struct {
	textStart, textEnd int // the text inside the brackets
	rawDest            string
	end                int // after the closing parenthesis
}

// inlineLink reads `[text](dest "title")` at i. The text may span lines and
// hold code; the brackets and destination must be outside code.
func inlineLink(src []byte, open []bool, i int) (link, bool) {
	l := link{textStart: i + 1}
	depth := 0
	j := i + 1
	for ; j < len(src); j++ {
		if !open[j] {
			continue
		}
		if src[j] == '\\' {
			j++
			continue
		}
		if src[j] == '[' {
			depth++
		} else if src[j] == ']' {
			if depth == 0 {
				break
			}
			depth--
		}
	}
	if j+1 >= len(src) || src[j+1] != '(' || !open[j+1] {
		return l, false
	}
	l.textEnd = j
	k := j + 2
	for k < len(src) && (src[k] == ' ' || src[k] == '\t' || src[k] == '\n') {
		k++
	}
	if k < len(src) && src[k] == '<' {
		e := k + 1
		for e < len(src) && src[e] != '>' && src[e] != '\n' {
			if src[e] == '\\' {
				e++
			}
			e++
		}
		if e >= len(src) || src[e] != '>' {
			return l, false
		}
		l.rawDest = string(src[k+1 : e])
		k = e + 1
	} else {
		start, parens := k, 0
		for k < len(src) && src[k] > ' ' {
			if src[k] == '\\' && k+1 < len(src) {
				k += 2
				continue
			}
			if src[k] == '(' {
				parens++
			} else if src[k] == ')' {
				if parens == 0 {
					break
				}
				parens--
			}
			k++
		}
		l.rawDest = string(src[start:k])
	}
	// Optional title, then the closing parenthesis.
	for k < len(src) && (src[k] == ' ' || src[k] == '\t' || src[k] == '\n') {
		k++
	}
	if k < len(src) && (src[k] == '"' || src[k] == '\'' || src[k] == '(') {
		closer := src[k]
		if closer == '(' {
			closer = ')'
		}
		k++
		for k < len(src) && src[k] != closer {
			if src[k] == '\\' {
				k++
			}
			k++
		}
		k++
		for k < len(src) && (src[k] == ' ' || src[k] == '\t' || src[k] == '\n') {
			k++
		}
	}
	if k >= len(src) || src[k] != ')' {
		return l, false
	}
	l.end = k + 1
	return l, true
}

var scheme = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9+.-]*:`)

func isRemote(dest string) bool {
	d := strings.ToLower(dest)
	return strings.HasPrefix(d, "http:") || strings.HasPrefix(d, "https:") || strings.HasPrefix(d, "//")
}

// local resolves a Markdown link destination to a file in the import.
func (c *converter) local(rawDest string) (f *File, p string, ok bool) {
	return c.resolve(html.UnescapeString(unescapeMarkdown(strings.TrimSpace(rawDest))))
}

// resolve finds the file a decoded destination names: ok is false for URLs
// and in-note anchors.
func (c *converter) resolve(dest string) (f *File, p string, ok bool) {
	// `//host/path` is a URL without its scheme, not a path from the root.
	if dest == "" || strings.HasPrefix(dest, "#") || strings.HasPrefix(dest, "//") || scheme.MatchString(dest) {
		return nil, "", false
	}
	if i := strings.IndexAny(dest, "?#"); i >= 0 {
		dest = dest[:i]
	}
	if u, err := url.PathUnescape(dest); err == nil {
		dest = u
	}
	if strings.HasPrefix(dest, "/") {
		p = path.Clean(strings.TrimLeft(dest, "/"))
	} else {
		p = path.Join(path.Dir(c.notePath), dest)
	}
	if p == ".." || strings.HasPrefix(p, "../") {
		return nil, dest, true // above the import: never read
	}
	return c.src.Lookup(p), p, true
}

// dropLink reports a link to another file, which becomes its text.
func (c *converter) dropLink(rawDest string) bool {
	f, p, isLocal := c.local(rawDest)
	switch {
	case !isLocal:
		return false
	case f != nil && !isNote(f.Path):
		c.issues = append(c.issues, "link to "+f.Path+" is now plain text; only notes and images are imported")
	case f == nil && !isNote(p) && path.Ext(p) != "":
		c.issues = append(c.issues, "link to "+p+", not in the import, is now plain text")
	default:
		c.noteLinks++
	}
	return true
}

// image rewrites `![alt](dest)`, or reports false to leave it as written.
func (c *converter) image(rawAlt, rawDest string) (string, bool) {
	alt := unescapeMarkdown(rawAlt)
	dest := strings.TrimSpace(rawDest)
	if d, ok := dataURI(dest); ok {
		ref, problem := c.images.dataImage(d)
		if ref == "" {
			c.issues = append(c.issues, "inline data image left as written: "+problem)
			return "", false
		}
		return imageMarkdown(alt, ref), true
	}
	f, p, isLocal := c.local(rawDest)
	if !isLocal {
		if isRemote(dest) {
			c.remoteImages++
		}
		return "", false
	}
	if f == nil {
		f = c.src.LookupOnlyBase(path.Base(p))
	}
	return c.fileImage(f, p, alt)
}

func (c *converter) fileImage(f *File, p, alt string) (string, bool) {
	if f == nil {
		c.issues = append(c.issues, "image "+p+" isn't in the import; left as written")
		return "", false
	}
	ref, problem := c.images.fileImage(f)
	if ref == "" {
		c.issues = append(c.issues, "image "+f.Path+" left as written: "+problem)
		return "", false
	}
	return imageMarkdown(alt, ref), true
}

// wikiText is what `[[target|alias]]` shows.
func (c *converter) wikiText(inner string) string {
	target, alias, hasAlias := strings.Cut(inner, "|")
	if hasAlias && strings.TrimSpace(alias) != "" {
		return escapeText(strings.TrimSpace(alias))
	}
	target = strings.TrimSpace(target)
	note, heading, _ := strings.Cut(target, "#")
	heading = strings.TrimPrefix(heading, "^")
	switch {
	case note == "":
		return escapeText(heading)
	case heading != "":
		return escapeText(note + " > " + heading)
	}
	return escapeText(note)
}

var obsidianSize = regexp.MustCompile(`^\d+(x\d+)?$`)

// wikiEmbed is `![[file]]`: an image if it is one, else its text.
func (c *converter) wikiEmbed(inner string) string {
	target, opt, _ := strings.Cut(inner, "|")
	target = strings.TrimSpace(target)
	name, _, _ := strings.Cut(target, "#")
	alt := ""
	if o := strings.TrimSpace(opt); o != "" && !obsidianSize.MatchString(o) {
		alt = o
	}
	if isImageName(name) {
		f := c.src.Lookup(path.Join(path.Dir(c.notePath), name))
		if f == nil {
			f = c.src.LookupBase(path.Base(name))
		}
		if repl, ok := c.fileImage(f, name, alt); ok {
			return repl
		}
		return "![[" + inner + "]]"
	}
	if isNote(name) || path.Ext(name) == "" {
		c.noteLinks++
	} else {
		c.issues = append(c.issues, "embedded "+name+" is now plain text; only notes and images are imported")
	}
	return c.wikiText(inner)
}

var (
	imgTag  = regexp.MustCompile(`(?is)^<img\b[^>]*>(\s*</img>)?$`)
	imgAttr = regexp.MustCompile(`(?is)\b(src|alt)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))`)
)

// htmlImage turns a lone `<img>` tag, such as Joplin writes for a resized
// picture, into an app image when its src is in the import.
func (c *converter) htmlImage(tag string) (string, bool) {
	if !imgTag.MatchString(strings.TrimSpace(tag)) {
		return "", false
	}
	var src, alt string
	for _, m := range imgAttr.FindAllStringSubmatch(tag, -1) {
		v := html.UnescapeString(m[2] + m[3] + m[4])
		if strings.EqualFold(m[1], "src") {
			src = v
		} else {
			alt = v
		}
	}
	if src == "" {
		return "", false
	}
	if d, ok := dataURI(src); ok {
		ref, problem := c.images.dataImage(d)
		if ref == "" {
			c.issues = append(c.issues, "inline data image left as written: "+problem)
			return "", false
		}
		return imageMarkdown(alt, ref), true
	}
	f, p, isLocal := c.resolve(strings.TrimSpace(src))
	if !isLocal {
		if isRemote(strings.TrimSpace(src)) {
			c.remoteImages++
		}
		return "", false
	}
	if f == nil {
		f = c.src.LookupOnlyBase(path.Base(p))
	}
	return c.fileImage(f, p, alt)
}

// htmlBlockImage converts an HTML block that is only an `<img>` tag.
func (c *converter) htmlBlockImage(src []byte, b *ast.HTMLBlock) (edit, bool) {
	lines := b.Lines()
	if lines.Len() != 1 {
		return edit{}, false
	}
	s := lines.At(0)
	raw := string(s.Value(src))
	repl, ok := c.htmlImage(raw)
	if !ok {
		return edit{}, false
	}
	return edit{s.Start, s.Stop, repl + strings.Repeat("\n", strings.Count(raw, "\n"))}, true
}

// dataURI decodes a base64 `data:image/…` destination.
func dataURI(dest string) ([]byte, bool) {
	meta, payload, ok := strings.Cut(dest, ",")
	if !ok || !strings.HasPrefix(strings.ToLower(meta), "data:image/") || !strings.HasSuffix(strings.ToLower(meta), ";base64") {
		return nil, false
	}
	data, err := base64.StdEncoding.DecodeString(strings.Join(strings.Fields(payload), ""))
	if err != nil {
		return nil, false
	}
	return data, true
}

var markdownEscape = regexp.MustCompile(`\\([!-/:-@\[-` + "`" + `{-~])`)

func unescapeMarkdown(s string) string { return markdownEscape.ReplaceAllString(s, "$1") }

var altSpecial = regexp.MustCompile("[\\\\\\[\\]*_`~<>!#&]")

// imageMarkdown writes an app image as the apps do (web: imageMarkdown in
// images/ref.ts), so editors show it as one atomic node.
func imageMarkdown(alt, dest string) string {
	alt = strings.Join(strings.Fields(alt), " ")
	return "![" + altSpecial.ReplaceAllString(alt, `\$0`) + "](" + dest + ")"
}

var textSpecial = regexp.MustCompile("[\\\\\\[\\]*_`~<]")

// escapeText keeps a wiki-link's text plain, not Markdown.
func escapeText(s string) string { return textSpecial.ReplaceAllString(s, `\$0`) }

// hardBreaks makes soft line breaks in paragraphs `\` breaks (§7.4): the
// apps notes come from show a lone newline as one. Trailing-space breaks too.
func hardBreaks(body string) string {
	src := []byte(body)
	var edits []edit
	_ = ast.Walk(parse(src), func(n ast.Node, entering bool) (ast.WalkStatus, error) {
		t, ok := n.(*ast.Text)
		if !entering || !ok || !(t.SoftLineBreak() || t.HardLineBreak()) || !inParagraph(n) {
			return ast.WalkContinue, nil
		}
		stop := t.Segment.Stop
		if t.HardLineBreak() && stop < len(src) && src[stop] == '\\' {
			return ast.WalkContinue, nil // already `\`
		}
		nl := stop
		for nl < len(src) && (src[nl] == ' ' || src[nl] == '\t') {
			nl++
		}
		if nl < len(src) && src[nl] == '\n' {
			edits = append(edits, edit{stop, nl, `\`})
		}
		return ast.WalkContinue, nil
	})
	return applyEdits(body, edits)
}

func inParagraph(n ast.Node) bool {
	for p := n.Parent(); p != nil; p = p.Parent() {
		switch p.Kind() {
		case ast.KindParagraph, ast.KindTextBlock:
			return true
		case ast.KindHeading, ast.KindCodeSpan:
			return false
		}
		if p.Type() == ast.TypeBlock {
			return false
		}
	}
	return false
}

var noteExts = map[string]bool{".md": true, ".markdown": true, ".mdown": true, ".mkd": true, ".txt": true}

func isNote(p string) bool { return noteExts[strings.ToLower(path.Ext(p))] }

var imageExts = map[string]bool{
	".png": true, ".jpg": true, ".jpeg": true, ".gif": true, ".webp": true,
	".svg": true, ".heic": true, ".heif": true, ".avif": true, ".bmp": true, ".tif": true, ".tiff": true,
}

func isImageName(p string) bool { return imageExts[strings.ToLower(path.Ext(p))] }
