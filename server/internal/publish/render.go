package publish

import (
	"bytes"
	"html/template"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/yuin/goldmark"
	"github.com/yuin/goldmark/ast"
	"github.com/yuin/goldmark/extension"
	"github.com/yuin/goldmark/text"

	"notes/server/internal/search"
)

// Raw HTML in a note is escaped: goldmark drops it unless WithUnsafe is set.
var markdown = goldmark.New(goldmark.WithExtensions(extension.GFM))

// appImage is an image the apps added (§7.6): its id, then its size.
var appImage = regexp.MustCompile(`^notesd:image/([0-9a-f]{64})\?w=([1-9]\d{0,5})&h=([1-9]\d{0,5})$`)

// imageFile is an image copied into a site.
type imageFile struct {
	Name string // relative to the site's root: img/<id>.<ext>
}

// body renders a note's Markdown for a page whose links to the site's root
// start with root ("../", or the site's URL in the feed); anchors gives its
// headings links to themselves.
func body(content, root string, images map[string]imageFile, anchors bool) (template.HTML, error) {
	src := []byte(content)
	doc := markdown.Parser().Parse(text.NewReader(src))
	// Changed after the walk: replacing a node during it would cut its siblings off.
	var tags []*ast.Text
	var imgs []*ast.Image
	var heads []*ast.Heading
	err := ast.Walk(doc, func(n ast.Node, entering bool) (ast.WalkStatus, error) {
		if !entering {
			return ast.WalkContinue, nil
		}
		switch n := n.(type) {
		case *ast.Heading:
			// The note's title is the page's only <h1>.
			n.Level = min(n.Level+1, 6)
			heads = append(heads, n)
		case *ast.Image:
			imgs = append(imgs, n)
			return ast.WalkSkipChildren, nil
		case *ast.Link, *ast.AutoLink, *ast.CodeSpan:
			return ast.WalkSkipChildren, nil
		case *ast.Text:
			tags = append(tags, n)
		}
		return ast.WalkContinue, nil
	})
	if err != nil {
		return "", err
	}
	for _, n := range imgs {
		pointImage(n, src, root, images)
	}
	for _, t := range tags {
		linkTags(t, src, root)
	}
	// A page's only: a feed reader has no stylesheet to hide the anchor's words, and wants links absolute.
	if anchors {
		anchorHeadings(heads, src)
	}
	var out bytes.Buffer
	if err := markdown.Renderer().Render(&out, src, doc); err != nil {
		return "", err
	}
	return template.HTML(out.String()), nil // #nosec G203 -- goldmark output, raw HTML escaped
}

// pointImage points an app image at its copy in the site, sized so the page doesn't move as it loads.
// One the site doesn't have (missing, or not an app image) becomes its alt text.
func pointImage(n *ast.Image, src []byte, root string, images map[string]imageFile) {
	m := appImage.FindSubmatch(n.Destination)
	if m != nil {
		if f, ok := images[string(m[1])]; ok {
			n.Destination = []byte(root + f.Name)
			n.SetAttributeString("width", m[2])
			n.SetAttributeString("height", m[3])
			n.SetAttributeString("loading", []byte("lazy"))
			n.SetAttributeString("decoding", []byte("async"))
			return
		}
	}
	n.Parent().ReplaceChild(n.Parent(), n, ast.NewString(altText(n, src)))
}

// linkTags turns the #tags in a run of text into links to their pages, by the apps' rules (§7.5).
func linkTags(t *ast.Text, src []byte, root string) {
	value := string(t.Segment.Value(src))
	spans := search.TagSpans(value, opensTag(t, src))
	if len(spans) == 0 {
		return
	}
	parent := t.Parent()
	start := t.Segment.Start
	at := 0
	var last ast.Node = t
	insert := func(n ast.Node) {
		parent.InsertAfter(parent, last, n)
		last = n
	}
	for _, sp := range spans {
		href, ok := tagHref(root, search.TagKey(sp.Name))
		if !ok {
			continue
		}
		if sp.Start > at {
			insert(ast.NewTextSegment(text.NewSegment(start+at, start+sp.Start)))
		}
		link := ast.NewLink()
		link.Destination = []byte(href)
		link.SetAttributeString("class", []byte("tag"))
		link.AppendChild(link, ast.NewTextSegment(text.NewSegment(start+sp.Start, start+sp.End)))
		insert(link)
		at = sp.End
	}
	if at == 0 {
		return
	}
	// The line's break belongs after its last piece, a text: an empty one when a tag ends the line.
	if at < len(value) || t.SoftLineBreak() || t.HardLineBreak() {
		rest := ast.NewTextSegment(text.NewSegment(start+at, t.Segment.Stop))
		rest.SetSoftLineBreak(t.SoftLineBreak())
		rest.SetHardLineBreak(t.HardLineBreak())
		insert(rest)
	}
	parent.RemoveChild(parent, t)
}

// opensTag reports whether a tag may start at the beginning of t: at its line's start, or after a space or
// an opening bracket (§7.5). Inside emphasis, the run's start counts as the line's.
func opensTag(t *ast.Text, src []byte) bool {
	switch prev := t.PreviousSibling().(type) {
	case nil:
		return true
	case *ast.Text:
		if prev.SoftLineBreak() || prev.HardLineBreak() {
			return true
		}
		v := prev.Segment.Value(src)
		c, _ := utf8.DecodeLastRune(v)
		return len(v) > 0 && (unicode.IsSpace(c) || c == '(' || c == '[' || c == '{')
	}
	return false
}

// altText is an image's alt text as written.
func altText(n ast.Node, src []byte) []byte {
	var b []byte
	_ = ast.Walk(n, func(c ast.Node, entering bool) (ast.WalkStatus, error) {
		if entering {
			switch c := c.(type) {
			case *ast.Text:
				b = append(b, c.Segment.Value(src)...)
			case *ast.String:
				b = append(b, c.Value...)
			}
		}
		return ast.WalkContinue, nil
	})
	return b
}

// tagHref is a tag's page below root: tags/home/garden/. A tag with a level a URL can't hold as a
// segment (. or ..) has no page.
func tagHref(root, key string) (string, bool) {
	path, ok := tagPath(key)
	if !ok {
		return "", false
	}
	return root + path, true
}

// tagPath is a tag's page below the site's root, its levels escaped.
func tagPath(key string) (string, bool) {
	levels := strings.Split(key, "/")
	for i, l := range levels {
		if l == "" || l == "." || l == ".." {
			return "", false
		}
		levels[i] = url.PathEscape(l)
	}
	return "tags/" + strings.Join(levels, "/") + "/", true
}

// anchorHeadings gives each heading an id from its words, and a link to itself to share that section by.
func anchorHeadings(heads []*ast.Heading, src []byte) {
	// The page's own ids.
	taken := map[string]bool{"main": true}
	for _, h := range heads {
		base := slug(string(altText(h, src)))
		if base == "post" {
			base = "section"
		}
		id := base
		for n := 2; taken[id]; n++ {
			id = base + "-" + strconv.Itoa(n)
		}
		taken[id] = true
		h.SetAttributeString("id", []byte(id))
		link := ast.NewLink()
		link.Destination = []byte("#" + id)
		link.SetAttributeString("class", []byte("heading-anchor"))
		// Raw: goldmark renders no aria-label, so the words a screen reader says are hidden text.
		mark := ast.NewString([]byte(`<span aria-hidden="true">#</span><span class="visually-hidden">Link to this section</span>`))
		mark.SetCode(true)
		link.AppendChild(link, mark)
		h.AppendChild(h, link)
	}
}

// withoutTags is a line of prose without its #tags: an excerpt's are noise, the post's page lists them.
func withoutTags(line string) string {
	var b strings.Builder
	at := 0
	for _, sp := range search.TagSpans(line, true) {
		b.WriteString(line[at:sp.Start])
		at = sp.End
	}
	return b.String() + line[at:]
}

// readingTime is how long a post takes to read, at 230 words a minute.
func readingTime(content string) string {
	words := len(strings.Fields(search.PlainText(content)))
	return strconv.Itoa(max(1, (words+115)/230)) + " min read"
}

// excerptLen is how much of a post's text the lists show.
const excerptLen = 200

// excerpt is the start of a note's text, as plain text, cut at a word.
func excerpt(content string) string {
	// Prose only: #include in a code block isn't a tag.
	s := strings.Join(strings.Fields(search.PlainTextWith(content, withoutTags)), " ")
	if utf8.RuneCountInString(s) <= excerptLen {
		return s
	}
	r := []rune(s)[:excerptLen]
	cut := len(r)
	for i := len(r) - 1; i > excerptLen/2; i-- {
		if unicode.IsSpace(r[i]) {
			cut = i
			break
		}
	}
	return strings.TrimRightFunc(string(r[:cut]), func(c rune) bool { return unicode.IsSpace(c) || unicode.IsPunct(c) }) + "…"
}
