package publish

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"encoding/xml"
	"fmt"
	"html/template"
	"net/url"
	"path"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode"

	"golang.org/x/text/unicode/norm"

	"notes/server/internal/images"
	"notes/server/internal/locks"
	"notes/server/internal/search"
	"notes/server/internal/userdata"
)

//go:embed assets
var assets embed.FS

var pages = template.Must(template.ParseFS(assets, "assets/site.html"))

// pageSize is how many posts a page of the list holds.
const pageSize = 30

// feedSize is how many posts the feed holds.
const feedSize = 50

// site is everything a published space's address serves, built in memory.
type site struct {
	files    map[string][]byte // by name, relative to the site's folder
	manifest *manifest
}

// manifest says which file answers each address below /@<handle>/, so the
// handler never builds a file name from a URL.
type manifest struct {
	Name    string `json:"name"`
	Accent  string `json:"accent"`
	Style   string `json:"style"`
	Script  string `json:"script"`
	SpaceID string `json:"spaceId"`
	// Routes maps a path below the site's root ("", "tags/home/", "a-post-1a2b3c4d") to its file.
	Routes map[string]string `json:"routes"`
	// Posts maps each post's short id to its path, for an address whose slug is out of date.
	Posts map[string]string `json:"posts"`
	// Notes maps each published note's id to its post's path, for search results.
	Notes map[string]string `json:"notes"`
	// ETags by file name; a file in Gzipped also has its compressed copy, <name>.gz.
	ETags   map[string]string `json:"etags"`
	Gzipped map[string]bool   `json:"gzipped"`
}

// post is a published note.
type post struct {
	ID, Title, Content string
	Created, Updated   time.Time
	Path               string // below the site's root: <slug>-<short>
	Short              string
	Tags               []search.Tag
}

// source is what a site is built from.
type source struct {
	Name, Icon, CSS string
	Base            string // the site's absolute URL, for the feed and sitemap
	BasePath        string // its path, /@<handle>/: the 404 page's root, served at any depth
	Posts           []post // newest first
	Images          map[string]imageFile
	ImageData       map[string][]byte
}

// load reads a space's published notes and the images they show. A note is published when it has a title
// and a body; locked notes can't be in a published space (§7.8).
func load(ctx context.Context, d *userdata.Data, spaceID, base string) (*source, error) {
	sp, err := d.Spaces.Get(ctx, spaceID)
	if err != nil {
		return nil, err
	}
	src := &source{Name: sp.Name, Icon: sp.Icon, Base: base, Images: map[string]imageFile{}, ImageData: map[string][]byte{}}
	if err := d.DB.R.QueryRowContext(ctx, `SELECT coalesce((SELECT css FROM space_publish WHERE space_id = ?), '')`,
		spaceID).Scan(&src.CSS); err != nil {
		return nil, err
	}
	rows, err := d.DB.R.QueryContext(ctx, `SELECT id, title, content, created_at, updated_at FROM notes
		WHERE space_id = ? AND deleted_at IS NULL ORDER BY created_at DESC, id DESC`, spaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	shorts := map[string]bool{}
	for rows.Next() {
		var p post
		var created, updated int64
		if err := rows.Scan(&p.ID, &p.Title, &p.Content, &created, &updated); err != nil {
			return nil, err
		}
		p.Title = strings.TrimSpace(p.Title)
		if p.Title == "" || strings.TrimSpace(p.Content) == "" || locks.IsLocked(p.Content) {
			continue
		}
		p.Created, p.Updated = time.UnixMilli(created).UTC(), time.UnixMilli(updated).UTC()
		p.Short = shortID(p.ID, shorts)
		p.Path = slug(p.Title) + "-" + p.Short
		p.Tags = search.Tags(p.Title, p.Content)
		src.Posts = append(src.Posts, p)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	rows.Close()
	for _, p := range src.Posts {
		for _, id := range images.Refs(p.Content) {
			if _, ok := src.Images[id]; ok {
				continue
			}
			img, data, err := d.Images.Get(ctx, id)
			if err != nil {
				continue // shown as its alt text
			}
			ext := images.Ext(img.ContentType)
			if ext == "" {
				continue // encrypted: never in a published note, whose body is plain
			}
			name := "img/" + id + "." + ext
			src.Images[id] = imageFile{Name: name}
			src.ImageData[name] = data
		}
	}
	return src, nil
}

// shortID is a note's address suffix: 8 hex digits of its id's hash, longer if another post here has them.
// Note ids are the apps' choice, so they're hashed rather than cut.
func shortID(id string, taken map[string]bool) string {
	sum := sha256.Sum256([]byte(id))
	h := hex.EncodeToString(sum[:])
	for n := 8; n < len(h); n += 4 {
		if !taken[h[:n]] {
			taken[h[:n]] = true
			return h[:n]
		}
	}
	return h
}

var notSlug = regexp.MustCompile(`[^a-z0-9]+`)

// slug is a title as an address: lowercase ASCII letters and digits, accents dropped, words joined by -.
func slug(title string) string {
	var b strings.Builder
	for _, r := range norm.NFKD.String(strings.ToLower(title)) {
		if !unicode.Is(unicode.Mn, r) {
			b.WriteRune(r)
		}
	}
	s := strings.Trim(notSlug.ReplaceAllString(b.String(), "-"), "-")
	if len(s) > 60 {
		s = strings.TrimRight(s[:60], "-")
	}
	if s == "" {
		return "post"
	}
	return s
}

// siteInfo is what every page shows of the site.
type siteInfo struct {
	Name, Accent, Style, Script string
}

// listItem is a post in a list.
type listItem struct {
	Href, Title, Date, Datetime string
	Excerpt                     template.HTML
}

// tagLink is a tag linked to its page.
type tagLink struct {
	Href, Name string
}

// postView is a post's page.
type postView struct {
	Title, Date, Datetime, Updated, UpdatedDatetime string
	Body                                            template.HTML
	Tags                                            []tagLink
}

// tagNode is a tag in the tags page's tree.
type tagNode struct {
	Href, Name string
	Count      int
	Children   []*tagNode
}

// page is what the page template is given.
type page struct {
	Site        siteInfo
	Kind        string // home, post, tags, tag, search, missing
	Root        string // the site's root relative to this page: "./" or "../.."
	Title       string
	Description string
	Canonical   string
	NoIndex     bool

	Posts        []listItem
	Newer, Older string // a list's neighbouring pages
	Post         *postView
	PostNewer    *listItem
	PostOlder    *listItem
	Tags         []*tagNode
	Tag          string
	Count        string
	Query        string
}

// rootFor is the site's root as seen from a path below it.
func rootFor(route string) string {
	if n := strings.Count(route, "/"); n > 0 {
		return strings.Repeat("../", n)
	}
	return "./"
}

func date(t time.Time) string { return t.Format("Jan 2, 2006") }

func datetime(t time.Time) string { return t.Format(time.RFC3339) }

func (p post) item(root string) listItem {
	return listItem{
		Href: root + p.Path, Title: p.Title, Date: date(p.Created), Datetime: datetime(p.Created),
		Excerpt: template.HTML(template.HTMLEscapeString(excerpt(p.Content))), // #nosec G203 -- escaped
	}
}

func plural(n int, one, many string) string {
	if n == 1 {
		return "1 " + one
	}
	return strconv.Itoa(n) + " " + many
}

// build renders every file of a site.
func build(src *source, accent string) (*site, error) {
	s := &site{files: map[string][]byte{}, manifest: &manifest{
		Name: src.Name, Accent: accent, Routes: map[string]string{}, Posts: map[string]string{},
		Notes: map[string]string{}, ETags: map[string]string{}, Gzipped: map[string]bool{},
	}}
	m := s.manifest
	style, err := stylesheet(src.CSS)
	if err != nil {
		return nil, err
	}
	m.Style = "style." + hash(style)[:10] + ".css"
	script, err := assets.ReadFile("assets/theme.js")
	if err != nil {
		return nil, err
	}
	m.Script = "theme." + hash(script)[:10] + ".js"
	s.add(m.Style, m.Style, style)
	s.add(m.Script, m.Script, script)
	info := siteInfo{Name: src.Name, Accent: accent, Style: m.Style, Script: m.Script}
	for name, data := range src.ImageData {
		s.add(name, name, data)
	}

	// route is as it appears in a link (a tag's levels escaped); the manifest keeps it as a request's
	// path reads, unescaped.
	render := func(route, file string, p page) error {
		p.Site, p.Root = info, rootFor(route)
		if p.Kind == "missing" {
			p.Root = src.BasePath
		} else if !p.NoIndex {
			p.Canonical = src.Base + route
		}
		var b bytes.Buffer
		if err := pages.ExecuteTemplate(&b, "page", p); err != nil {
			return fmt.Errorf("%s: %w", file, err)
		}
		key, err := url.PathUnescape(route)
		if err != nil {
			return err
		}
		s.add(key, file, b.Bytes())
		return nil
	}

	// The list, 30 to a page: /, /page/2/, …
	pagesN := max(1, (len(src.Posts)+pageSize-1)/pageSize)
	listRoute := func(n int) string {
		if n == 1 {
			return ""
		}
		return "page/" + strconv.Itoa(n) + "/"
	}
	for n := 1; n <= pagesN; n++ {
		route := listRoute(n)
		root := rootFor(route)
		p := page{Kind: "home", Description: src.Name}
		if n > 1 {
			p.Title = "Page " + strconv.Itoa(n)
			p.Newer = root + listRoute(n-1)
		}
		if n < pagesN {
			p.Older = root + listRoute(n+1)
		}
		for _, post := range src.Posts[(n-1)*pageSize : min(n*pageSize, len(src.Posts))] {
			p.Posts = append(p.Posts, post.item(root))
		}
		file := "index.html"
		if n > 1 {
			file = "page-" + strconv.Itoa(n) + ".html"
		}
		if err := render(route, file, p); err != nil {
			return nil, err
		}
	}

	// A page per post.
	for i, post := range src.Posts {
		root := rootFor(post.Path)
		html, err := body(post.Content, root, src.Images)
		if err != nil {
			return nil, err
		}
		v := &postView{Title: post.Title, Date: date(post.Created), Datetime: datetime(post.Created), Body: html}
		if date(post.Updated) != v.Date && post.Updated.After(post.Created) {
			v.Updated, v.UpdatedDatetime = date(post.Updated), datetime(post.Updated)
		}
		for _, t := range post.Tags {
			if href, ok := tagHref(root, t.Key); ok {
				v.Tags = append(v.Tags, tagLink{Href: href, Name: t.Name})
			}
		}
		p := page{Kind: "post", Title: post.Title, Description: excerpt(post.Content), Post: v}
		if i > 0 {
			it := src.Posts[i-1].item(root)
			p.PostNewer = &it
		}
		if i+1 < len(src.Posts) {
			it := src.Posts[i+1].item(root)
			p.PostOlder = &it
		}
		if err := render(post.Path, "p/"+post.Short+".html", p); err != nil {
			return nil, err
		}
		m.Posts[post.Short] = post.Path
		m.Notes[post.ID] = post.Path
	}

	// Tags: the tree, and a page per tag with its nested tags' posts, all on one: a tag's page/2/ would be the
	// address of a tag nested in it.
	tree, byKey := tagTree(src.Posts)
	if err := render("tags/", "tags.html", page{Kind: "tags", Title: "Tags", Tags: hrefs(tree, "../")}); err != nil {
		return nil, err
	}
	for key, t := range byKey {
		route, _ := tagPath(key)
		root := rootFor(route)
		p := page{Kind: "tag", Title: "#" + t.name, Tag: t.name, Count: plural(len(t.posts), "post", "posts")}
		for _, post := range t.posts {
			p.Posts = append(p.Posts, post.item(root))
		}
		if err := render(route, "t/"+hash([]byte(key))[:16]+".html", p); err != nil {
			return nil, err
		}
	}

	if err := render("search", "search.html", page{Kind: "search", Title: "Search", NoIndex: true}); err != nil {
		return nil, err
	}
	if err := render("404", "404.html", page{Kind: "missing", Title: "Not found", NoIndex: true}); err != nil {
		return nil, err
	}
	delete(m.Routes, "404") // served for any address the site doesn't have, never at its own

	feed, err := atom(src)
	if err != nil {
		return nil, err
	}
	s.add("feed.xml", "feed.xml", feed)
	sitemap, err := sitemap(src, byKey)
	if err != nil {
		return nil, err
	}
	s.add("sitemap.xml", "sitemap.xml", sitemap)
	return s, nil
}

// add stores a file and the address it answers, compressing what compresses.
func (s *site) add(route, name string, data []byte) {
	s.files[name] = data
	s.manifest.Routes[route] = name
	s.manifest.ETags[name] = `"` + hash(data)[:20] + `"`
	switch path.Ext(name) {
	case ".html", ".css", ".js", ".xml":
		var b bytes.Buffer
		w, _ := gzip.NewWriterLevel(&b, gzip.BestCompression)
		_, _ = w.Write(data)
		_ = w.Close()
		s.files[name+".gz"] = b.Bytes()
		s.manifest.Gzipped[name] = true
	}
}

func hash(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

// splitImports takes the @import rules out of custom CSS: they only count at the top of a stylesheet. A rule
// ends at the first ; outside strings, comments and brackets, since a font's address can hold one
// (family=X:ital@0;1); one inside a block or a comment is left where it is.
func splitImports(css string) (rules []string, rest string) {
	var b strings.Builder
	depth, parens := 0, 0
	for i := 0; i < len(css); {
		if depth == 0 && parens == 0 && len(css)-i >= 7 && strings.EqualFold(css[i:i+7], "@import") {
			end := statementEnd(css, i)
			rules = append(rules, strings.TrimSpace(css[i:end]))
			i = end
			continue
		}
		switch css[i] {
		case '{':
			depth++
		case '}':
			depth = max(0, depth-1)
		case '(':
			parens++
		case ')':
			parens = max(0, parens-1)
		}
		next := tokenEnd(css, i)
		b.WriteString(css[i:next])
		i = next
	}
	return rules, b.String()
}

// tokenEnd is where the token at i ends: past a string or a comment, else the next byte.
func tokenEnd(css string, i int) int {
	switch {
	case css[i] == '"' || css[i] == '\'':
		for j := i + 1; j < len(css); j++ {
			switch css[j] {
			case '\\':
				j++
			case css[i], '\n':
				return j + 1
			}
		}
		return len(css)
	case strings.HasPrefix(css[i:], "/*"):
		if j := strings.Index(css[i+2:], "*/"); j >= 0 {
			return i + 4 + j
		}
		return len(css)
	}
	return i + 1
}

// statementEnd is past the ; ending the rule at i, outside strings, comments and brackets; the end without one.
func statementEnd(css string, i int) int {
	parens := 0
	for i < len(css) {
		switch css[i] {
		case '(':
			parens++
		case ')':
			parens--
		case ';':
			if parens <= 0 {
				return i + 1
			}
		}
		i = tokenEnd(css, i)
	}
	return len(css)
}

// stylesheet is the site's styles in the notesd layer, then the space's own custom CSS unlayered, which
// wins over them without !important. Its @imports (web fonts) go first, where CSS reads them.
func stylesheet(custom string) ([]byte, error) {
	theme, err := assets.ReadFile("assets/theme.css")
	if err != nil {
		return nil, err
	}
	base, err := assets.ReadFile("assets/site.css")
	if err != nil {
		return nil, err
	}
	var b bytes.Buffer
	imports, rest := splitImports(custom)
	for _, i := range imports {
		b.WriteString(i + "\n")
	}
	b.WriteString("@layer notesd {\n")
	b.Write(theme)
	b.WriteString("\n")
	b.Write(base)
	b.WriteString("}\n")
	if rest := strings.TrimSpace(rest); rest != "" {
		b.WriteString("\n/* This space's own */\n")
		b.WriteString(rest + "\n")
	}
	return b.Bytes(), nil
}

// tagEntry is a tag with the posts carrying it or a nested tag.
type tagEntry struct {
	name  string // its last level, as most recently written
	posts []post
}

// tagTree groups posts by tag, every level of a nested tag counting too (#home/garden is in #home).
func tagTree(posts []post) ([]*tagNode, map[string]*tagEntry) {
	byKey := map[string]*tagEntry{}
	// Names as the most recently updated post writes them (§7.5).
	byUpdate := append([]post(nil), posts...)
	sort.SliceStable(byUpdate, func(i, j int) bool { return byUpdate[i].Updated.After(byUpdate[j].Updated) })
	for _, p := range byUpdate {
		for _, t := range p.Tags {
			levels, names := strings.Split(t.Key, "/"), strings.Split(t.Name, "/")
			for i := range levels {
				key := strings.Join(levels[:i+1], "/")
				if _, ok := tagPath(key); !ok {
					break
				}
				if byKey[key] == nil {
					byKey[key] = &tagEntry{name: names[min(i, len(names)-1)]}
				}
			}
		}
	}
	for _, p := range posts {
		seen := map[string]bool{}
		for _, t := range p.Tags {
			levels := strings.Split(t.Key, "/")
			for i := range levels {
				key := strings.Join(levels[:i+1], "/")
				if e := byKey[key]; e != nil && !seen[key] {
					seen[key] = true
					e.posts = append(e.posts, p)
				}
			}
		}
	}
	nodes := map[string]*tagNode{}
	var roots []*tagNode
	keys := make([]string, 0, len(byKey))
	for k := range byKey {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		e := byKey[k]
		n := &tagNode{Name: e.name, Count: len(e.posts)}
		n.Href, _ = tagPath(k)
		nodes[k] = n
		if i := strings.LastIndex(k, "/"); i >= 0 && nodes[k[:i]] != nil {
			nodes[k[:i]].Children = append(nodes[k[:i]].Children, n)
		} else {
			roots = append(roots, n)
		}
	}
	return roots, byKey
}

// hrefs prefixes a tree's links with root, for the page showing it.
func hrefs(nodes []*tagNode, root string) []*tagNode {
	for _, n := range nodes {
		n.Href = root + n.Href
		hrefs(n.Children, root)
	}
	return nodes
}

type atomLink struct {
	Rel  string `xml:"rel,attr,omitempty"`
	Href string `xml:"href,attr"`
}

type atomContent struct {
	Type string `xml:"type,attr"`
	Body string `xml:",chardata"`
}

type atomEntry struct {
	Title     string      `xml:"title"`
	ID        string      `xml:"id"`
	Link      atomLink    `xml:"link"`
	Published string      `xml:"published"`
	Updated   string      `xml:"updated"`
	Content   atomContent `xml:"content"`
}

type atomFeed struct {
	XMLName xml.Name    `xml:"http://www.w3.org/2005/Atom feed"`
	Title   string      `xml:"title"`
	ID      string      `xml:"id"`
	Links   []atomLink  `xml:"link"`
	Updated string      `xml:"updated"`
	Entries []atomEntry `xml:"entry"`
}

// atom is the site's feed: its newest posts in full, links absolute, as feed readers need.
func atom(src *source) ([]byte, error) {
	f := atomFeed{Title: src.Name, ID: src.Base, Links: []atomLink{{Href: src.Base}, {Rel: "self", Href: src.Base + "feed.xml"}}}
	var newest time.Time
	for _, p := range src.Posts[:min(feedSize, len(src.Posts))] {
		html, err := body(p.Content, src.Base, src.Images)
		if err != nil {
			return nil, err
		}
		newest = maxTime(newest, p.Updated)
		f.Entries = append(f.Entries, atomEntry{
			Title: p.Title, ID: src.Base + p.Path, Link: atomLink{Href: src.Base + p.Path},
			Published: datetime(p.Created), Updated: datetime(p.Updated), Content: atomContent{Type: "html", Body: string(html)},
		})
	}
	if newest.IsZero() {
		newest = time.Unix(0, 0).UTC()
	}
	f.Updated = datetime(newest)
	b, err := xml.MarshalIndent(f, "", "  ")
	if err != nil {
		return nil, err
	}
	return append([]byte(xml.Header), b...), nil
}

func maxTime(a, b time.Time) time.Time {
	if b.After(a) {
		return b
	}
	return a
}

type sitemapURL struct {
	Loc     string `xml:"loc"`
	LastMod string `xml:"lastmod,omitempty"`
}

type sitemapSet struct {
	XMLName xml.Name     `xml:"http://www.sitemaps.org/schemas/sitemap/0.9 urlset"`
	URLs    []sitemapURL `xml:"url"`
}

// sitemap lists the site's pages for search engines.
func sitemap(src *source, tags map[string]*tagEntry) ([]byte, error) {
	set := sitemapSet{URLs: []sitemapURL{{Loc: src.Base}, {Loc: src.Base + "tags/"}}}
	for _, p := range src.Posts {
		set.URLs = append(set.URLs, sitemapURL{Loc: src.Base + p.Path, LastMod: p.Updated.Format(time.DateOnly)})
	}
	keys := make([]string, 0, len(tags))
	for k := range tags {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		route, _ := tagPath(k)
		set.URLs = append(set.URLs, sitemapURL{Loc: src.Base + route})
	}
	b, err := xml.MarshalIndent(set, "", "  ")
	if err != nil {
		return nil, err
	}
	return append([]byte(xml.Header), b...), nil
}

// excerptHTML is a search result's excerpt: HTML-safe already, its matches in <mark>.
func excerptHTML(s string) template.HTML {
	return template.HTML(s) // #nosec G203 -- search escapes the note's text and adds only <mark>
}
