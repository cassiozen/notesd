package publish

import (
	"fmt"
	"strings"
	"testing"
	"time"

	"notes/server/internal/search"
)

func mustBody(t *testing.T, md string, images map[string]imageFile) string {
	t.Helper()
	html, err := body(md, "../", images, true)
	if err != nil {
		t.Fatal(err)
	}
	return string(html)
}

func TestBodyHeadingsStepDownUnderTheTitle(t *testing.T) {
	got := mustBody(t, "# One\n\n## Two\n\n###### Six", nil)
	for _, want := range []string{`<h2 id="one">One`, `<h3 id="two">Two`, `<h6 id="six">Six`} {
		if !strings.Contains(got, want) {
			t.Errorf("%s not in %s", want, got)
		}
	}
}

func TestHeadingsLinkToThemselves(t *testing.T) {
	got := mustBody(t, "# Main\n\n# Plan\n\n# Plan\n\n# 日本語", nil)
	for _, want := range []string{
		// The page's own id stays its own.
		`<h2 id="main-2">Main<a href="#main-2" class="heading-anchor"><span aria-hidden="true">#</span><span class="visually-hidden">Link to this section</span></a></h2>`,
		`<h2 id="plan">`, `<h2 id="plan-2">`, `<h2 id="section">`,
	} {
		if !strings.Contains(got, want) {
			t.Errorf("%s not in %s", want, got)
		}
	}
}

func TestExcerptsLeaveLabellingTagsOut(t *testing.T) {
	for content, want := range map[string]string{
		// Labels: a line of only tags, or a run after a finished sentence.
		"#summer, #refreshing, #drinks\nGet chill with these drinks": "Get chill with these drinks",
		"#a · #b | #c; #d / #e\nText":                                "Text",
		"Tomatoes at last. #veg #summer\n\nMore #home/garden soon.":  "Tomatoes at last. More #home/garden soon.",
		"Tomatoes at last! #veg, #summer":                            "Tomatoes at last!",
		"Recipes: #drinks":                                           "Recipes:",
		"A tag #with spaces# closes. #veg":                           "A tag #with spaces# closes.",
		// Words: a tag in a sentence stays, as written.
		"I went to the #beach":                     "I went to the #beach",
		"I like #tea, #coffee":                     "I like #tea, #coffee",
		"#idea what if notes could talk":           "#idea what if notes could talk",
		"Back at last. #veg.":                      "Back at last. #veg.",
		"Planted #home/garden tomatoes. #veg #fun": "Planted #home/garden tomatoes.",
	} {
		if got := excerpt(content); got != want {
			t.Errorf("excerpt(%q) = %q, want %q", content, got, want)
		}
	}
	// Code isn't prose: its # stays.
	if got := excerpt("C at last. #c\n\n```\n#include <stdio.h>\n```"); got != "C at last. #include <stdio.h>" {
		t.Fatalf("%q", got)
	}
}

func TestReadingTime(t *testing.T) {
	for words, want := range map[int]string{1: "1 min read", 300: "1 min read", 460: "2 min read", 1000: "4 min read"} {
		if got := readingTime(strings.Repeat("word ", words)); got != want {
			t.Errorf("%d words: %q", words, got)
		}
	}
}

func TestFaviconIsTheNamesLetterOnTheAccent(t *testing.T) {
	got := string(favicon("  garden notes", "open-book"))
	marks := accents()["open-book"]
	for _, want := range []string{">G</text>", "rect{fill:" + marks["light"].Accent + "}", "rect{fill:" + marks["dark"].Accent + "}"} {
		if !strings.Contains(got, want) {
			t.Errorf("%s not in %s", want, got)
		}
	}
	if got := string(favicon("<&>", "no-such")); strings.Contains(got, "<&>") || !strings.Contains(got, accents()[""]["light"].Accent) {
		t.Errorf("no letter, and the default accent: %s", got)
	}
}

func TestBodyEscapesRawHTML(t *testing.T) {
	got := mustBody(t, "Hi <script>alert(1)</script>\n\n<div onclick=x>block</div>\n\n[x](javascript:alert(1))", nil)
	if strings.Contains(got, "<script") || strings.Contains(got, "<div") || strings.Contains(got, "javascript:") {
		t.Fatalf("raw HTML or a script URL got through: %s", got)
	}
}

func TestBodyImages(t *testing.T) {
	id := strings.Repeat("a", 64)
	other := strings.Repeat("b", 64)
	md := fmt.Sprintf("![A cat](notesd:image/%s?w=640&h=480)\n\n![Gone](notesd:image/%s?w=1&h=1) and ![web](https://x/y.png)", id, other)
	got := mustBody(t, md, map[string]imageFile{id: {Name: "img/" + id + ".jpg"}})
	if !strings.Contains(got, `<img src="../img/`+id+`.jpg" alt="A cat" width="640" height="480" loading="lazy" decoding="async">`) {
		t.Errorf("the site's copy, sized: %s", got)
	}
	if strings.Contains(got, other) || !strings.Contains(got, "Gone and") {
		t.Errorf("a missing image is its alt text: %s", got)
	}
	if strings.Contains(got, "x/y.png") || !strings.Contains(got, "web") {
		t.Errorf("an image from elsewhere isn't loaded, its alt text shown: %s", got)
	}
}

func TestBodyLinksTagsByTheAppsRules(t *testing.T) {
	got := mustBody(t, "Plants #home/garden and #multi word tag# here.\n\n"+
		"Not C#, not `#code`, not [#link](https://x), not a#b.\n\n*#emph* and (#paren)", nil)
	for _, want := range []string{
		`<a href="../tags/home/garden/" class="tag">#home/garden</a> and`,
		`<a href="../tags/multi%20word%20tag/" class="tag">#multi word tag#</a> here.`,
		`<em><a href="../tags/emph/" class="tag">#emph</a></em>`,
		`(<a href="../tags/paren/" class="tag">#paren</a>)`,
	} {
		if !strings.Contains(got, want) {
			t.Errorf("%s not in %s", want, got)
		}
	}
	if strings.Count(got, `class="tag"`) != 4 {
		t.Errorf("only those are tags: %s", got)
	}
}

func TestBodyKeepsTheBreakAfterALineEndingInATag(t *testing.T) {
	got := mustBody(t, "Plants #garden\nmore here", nil)
	if !strings.Contains(got, "#garden</a>\nmore here") {
		t.Fatalf("the words run together: %s", got)
	}
}

func TestTagPathsRefuseDotLevels(t *testing.T) {
	if p, ok := tagPath("a/b c"); !ok || p != "tags/a/b%20c/" {
		t.Fatalf("%q %v", p, ok)
	}
	for _, k := range []string{"..", "a/../b", "."} {
		if _, ok := tagPath(k); ok {
			t.Errorf("%q has a page", k)
		}
	}
}

func TestSlugsAndShortIDs(t *testing.T) {
	for title, want := range map[string]string{
		"What I run on my $10 OpenStick": "what-i-run-on-my-10-openstick",
		"Família & Café":                 "familia-cafe",
		"日本語":                            "post",
	} {
		if got := slug(title); got != want {
			t.Errorf("slug(%q) = %q, want %q", title, got, want)
		}
	}
	taken := map[string]bool{}
	a := shortID("note-1", taken)
	if len(a) != 8 || shortID("note-1", taken) == a {
		t.Fatalf("8 hex digits, longer when taken: %s", a)
	}
}

func TestExcerptIsPlainAndCutAtAWord(t *testing.T) {
	if got := excerpt("# Head\n\nSome **bold** [link](https://x) text."); got != "Head Some bold link text." {
		t.Fatalf("%q", got)
	}
	long := strings.Repeat("word ", 100)
	got := excerpt(long)
	if !strings.HasSuffix(got, "word…") || len([]rune(got)) > excerptLen+1 {
		t.Fatalf("%q", got)
	}
}

func TestStylesheetLetsTheSpacesCSSWin(t *testing.T) {
	css, err := stylesheet("@import url(https://fonts.example/a.css);\nbody { color: red }\n")
	if err != nil {
		t.Fatal(err)
	}
	s := string(css)
	imp, layer, own := strings.Index(s, "@import"), strings.Index(s, "@layer notesd {"), strings.Index(s, "body { color: red }")
	if imp != 0 || layer < imp || own < layer || strings.Count(s, "@import") != 1 {
		t.Fatalf("imports first, then the site in its layer, then the space's own unlayered:\n%s", s[:200])
	}
}

func posts(n int) []post {
	var out []post
	base := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	for i := n; i >= 1; i-- {
		id := fmt.Sprintf("n%d", i)
		content := fmt.Sprintf("Body %d #all", i)
		if i == n {
			content += "\n\n# Part"
		}
		if i%2 == 0 {
			content += " #home/garden"
		}
		p := post{ID: id, Title: fmt.Sprintf("Post %d", i), Content: content,
			Created: base.Add(time.Duration(i) * time.Hour), Updated: base.Add(time.Duration(i) * time.Hour)}
		p.Short = shortID(id, map[string]bool{})
		p.Path = slug(p.Title) + "-" + p.Short
		p.Tags = search.Tags(p.Title, p.Content)
		out = append(out, p)
	}
	return out
}

func TestBuildLaysOutTheSite(t *testing.T) {
	src := &source{Name: "Garden", Base: "https://notes.example/@garden/",
		Posts: posts(31), Images: map[string]imageFile{}, ImageData: map[string][]byte{}}
	s, err := build(src, "duck")
	if err != nil {
		t.Fatal(err)
	}
	m := s.manifest
	page := func(route string) string {
		t.Helper()
		name, ok := m.Routes[route]
		if !ok {
			t.Fatalf("no %q in %v", route, m.Routes)
		}
		return string(s.files[name])
	}

	home := page("")
	if strings.Count(home, `class="post-item"`) != pageSize || !strings.Contains(home, "Post 31") || strings.Contains(home, ">Post 1<") {
		t.Errorf("the first page holds the newest 30")
	}
	if !strings.Contains(home, `<a rel="next" href="./page/2/">Older posts</a>`) || !strings.Contains(home, `data-accent="duck"`) {
		t.Errorf("home: %s", home)
	}
	if p2 := page("page/2/"); !strings.Contains(p2, ">Post 1<") || !strings.Contains(p2, `<a rel="prev" href="../../">Newer posts</a>`) {
		t.Errorf("page 2: %s", p2)
	}

	first := src.Posts[0]
	post := page(first.Path)
	for _, want := range []string{
		`<h1 class="post-title">Post 31</h1>`, `<a rel="next" href="./` + src.Posts[1].Path + `">`,
		`<link rel="canonical" href="https://notes.example/@garden/` + first.Path + `">`,
		`<link rel="stylesheet" href="./` + m.Style + `">`, `href="./tags/all/"`,
	} {
		if !strings.Contains(post, want) {
			t.Errorf("%s not in the post's page", want)
		}
	}
	if m.Posts[first.Short] != first.Path || m.Notes[first.ID] != first.Path {
		t.Errorf("addresses: %v %v", m.Posts, m.Notes)
	}

	tags := page("tags/")
	if !strings.Contains(tags, `href="../tags/home/"`) || !strings.Contains(tags, `href="../tags/home/garden/"`) {
		t.Errorf("tags: %s", tags)
	}
	if home := page("tags/home/"); strings.Count(home, `class="post-item"`) != 15 || !strings.Contains(home, "15 posts") {
		t.Errorf("a parent tag holds its nested tags' posts: %s", home)
	}
	if nf := string(s.files["404.html"]); !strings.Contains(nf, `href="`+missingRoot+`"`) || strings.Contains(nf, "@garden") {
		t.Errorf("the 404 page's root is filled in for each request, the site's own path nowhere: %s", nf)
	}
	if _, ok := m.Routes["404"]; ok {
		t.Error("404 isn't an address")
	}
	feed := page("feed.xml")
	if !strings.Contains(post, `class="heading-anchor"`) || !strings.Contains(feed, "Part") || strings.Contains(feed, "heading-anchor") {
		t.Error("a heading's anchor on its page, not in the feed: a reader has no stylesheet to hide it")
	}
	if strings.Count(feed, "<entry>") != 31 || !strings.Contains(feed, "<id>https://notes.example/@garden/"+first.Path+"</id>") {
		t.Errorf("feed: %s", feed[:300])
	}
	if !m.Gzipped[m.Routes[""]] || s.files[m.Routes[""]+".gz"] == nil {
		t.Error("pages are stored compressed too")
	}
	// The look: the serif served with the site and named by its content, a favicon, and link previews.
	css := string(s.files[m.Style])
	files, _ := fonts()
	for _, f := range files {
		if m.Routes[f.name] != f.name || !strings.Contains(css, "url("+f.name+")") || m.Gzipped[f.name] {
			t.Errorf("%s: served, named in the stylesheet, not compressed again", f.name)
		}
	}
	for _, want := range []string{
		`<link rel="icon" href="./` + m.Icon + `" type="image/svg+xml">`,
		`<meta property="og:title" content="Post 31">`, `<meta property="og:type" content="article">`,
		`<span class="reading-time">1 min read</span>`,
	} {
		if !strings.Contains(post, want) {
			t.Errorf("%s not in the post's page", want)
		}
	}
	if m.Routes[m.Icon] != m.Icon || !strings.Contains(home, `<h1 class="page-title visually-hidden">Posts</h1>`) {
		t.Errorf("icon %q; home: %s", m.Icon, home)
	}
}

func TestTheRailListsTagsWithOnlyTheCurrentBranchOpen(t *testing.T) {
	src := &source{Name: "Garden", Base: "https://notes.example/@garden/",
		Posts: posts(31), Images: map[string]imageFile{}, ImageData: map[string][]byte{}}
	s, err := build(src, "duck")
	if err != nil {
		t.Fatal(err)
	}
	page := func(route string) string {
		t.Helper()
		name, ok := s.manifest.Routes[route]
		if !ok {
			t.Fatalf("no %q", route)
		}
		return string(s.files[name])
	}
	// What's in the rail, apart from the page around it.
	railOf := func(html string) string {
		t.Helper()
		start := strings.Index(html, `<nav class="tag-rail"`)
		if start < 0 {
			return ""
		}
		return html[start : start+strings.Index(html[start:], "</nav>")]
	}

	home := railOf(page(""))
	for _, want := range []string{
		`<a href="./" aria-label="All posts, 31" aria-current="page"><span class="tag-rail-mark"`,
		`<a href="./tags/all/" aria-label="#all, 31 posts">`,
		`<a href="./tags/home/" aria-label="#home, 15 posts">`,
		`<svg class="tag-rail-more" `,
	} {
		if !strings.Contains(home, want) {
			t.Errorf("%s not in the home page's rail: %s", want, home)
		}
	}
	if strings.Contains(home, "garden") {
		t.Errorf("a branch is closed away from its tags: %s", home)
	}

	garden := railOf(page("tags/home/garden/"))
	for _, want := range []string{
		`<a href="../../../" aria-label="All posts, 31">`,
		`<svg class="tag-rail-more open" `,
		`<a href="../../../tags/home/garden/" aria-label="#garden, 15 posts" aria-current="page"><span class="tag-rail-mark"`,
	} {
		if !strings.Contains(garden, want) {
			t.Errorf("%s not in a nested tag's rail: %s", want, garden)
		}
	}
	if strings.Count(garden, `aria-current`) != 1 {
		t.Errorf("one tag is current: %s", garden)
	}
	if parent := railOf(page("tags/home/")); !strings.Contains(parent, `href="../../tags/home/garden/"`) {
		t.Errorf("a tag's own page opens its branch: %s", parent)
	}
	if other := railOf(page("tags/all/")); strings.Contains(other, "garden") {
		t.Errorf("another tag's page leaves the branch closed: %s", other)
	}

	if post := railOf(page(src.Posts[0].Path)); post == "" || strings.Contains(post, "aria-current") {
		t.Errorf("a post's page has the rail, nothing current: %s", post)
	}
	if railOf(page("tags/")) != "" {
		t.Error("the tags page has no rail: it would repeat the page")
	}
	if nf := railOf(string(s.files["404.html"])); !strings.Contains(nf, `href="`+missingRoot+`tags/all/"`) {
		t.Errorf("the 404 page's rail starts its links where its root is filled in: %s", nf)
	}

	bare := posts(2)
	for i := range bare {
		bare[i].Tags = nil
	}
	s, err = build(&source{Name: "Bare", Base: "https://notes.example/@bare/", Posts: bare,
		Images: map[string]imageFile{}, ImageData: map[string][]byte{}}, "duck")
	if err != nil {
		t.Fatal(err)
	}
	if railOf(page("")) != "" {
		t.Error("no tags, no rail")
	}
}

func TestImportsEndOutsideStringsAndBrackets(t *testing.T) {
	css := "@import url(https://fonts.googleapis.com/css2?family=Instrument+Serif:ital@0;1&display=swap);\n" +
		"@IMPORT \"a;b.css\" screen;\n" +
		"/* @import url(c.css); */\n" +
		"@media print { @import url(d.css); }\n" +
		"h1 { font-family: 'Instrument Serif' }\n"
	rules, rest := splitImports(css)
	want := []string{
		"@import url(https://fonts.googleapis.com/css2?family=Instrument+Serif:ital@0;1&display=swap);",
		"@IMPORT \"a;b.css\" screen;",
	}
	if strings.Join(rules, "\n") != strings.Join(want, "\n") {
		t.Fatalf("rules: %q", rules)
	}
	for _, kept := range []string{"/* @import url(c.css); */", "@media print { @import url(d.css); }", "h1 { font-family: 'Instrument Serif' }"} {
		if !strings.Contains(rest, kept) {
			t.Errorf("%q not left in place: %q", kept, rest)
		}
	}
	if strings.Contains(rest, "display=swap") || strings.Contains(rest, "a;b.css") {
		t.Errorf("an import left behind: %q", rest)
	}
}

func TestTheMissingPagesRootIsFilledInOnlyInItsLinks(t *testing.T) {
	page := []byte(`<title>` + missingRoot + `x</title><a href="` + missingRoot + `tags/">` +
		`<link href="` + missingRoot + `style.css"><script src="` + missingRoot + `t.js"></script>`)
	got := string(fillRoot(page, "../../"))
	want := `<title>` + missingRoot + `x</title><a href="../../tags/"><link href="../../style.css"><script src="../../t.js"></script>`
	if got != want {
		t.Fatalf("a space named like the placeholder keeps its name:\n%s", got)
	}
}
