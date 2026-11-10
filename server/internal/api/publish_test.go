package api_test

import (
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/http/httputil"
	"net/url"
	"regexp"
	"strings"
	"testing"
	"time"
)

// page is a public response.
type page struct {
	status int
	header http.Header
	body   string
}

// visit fetches a public address without following redirects.
func (e *env) visit(path string, hdr ...string) page {
	e.t.Helper()
	req, _ := http.NewRequest("GET", e.srv.URL+path, nil)
	for i := 0; i+1 < len(hdr); i += 2 {
		req.Header.Set(hdr[i], hdr[i+1])
	}
	hc := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	res, err := hc.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return page{status: res.StatusCode, header: res.Header, body: string(b)}
}

// eventually waits for a public page to say want: the publisher renders a moment after a change.
func (e *env) eventually(path, want string) page {
	e.t.Helper()
	var p page
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(20 * time.Millisecond) {
		if p = e.visit(path); strings.Contains(p.body, want) {
			return p
		}
	}
	e.t.Fatalf("%s never said %q: %d %s", path, want, p.status, p.body)
	return p
}

const origin = "https://notes.example"

// css is the stylesheet a site's page links to.
func css(s *sharedEnv, page string) string {
	s.t.Helper()
	m := regexp.MustCompile(`<link rel="stylesheet" href="\./(style\.[0-9a-f]+\.css)">`).FindStringSubmatch(page)
	if m == nil {
		s.t.Fatalf("no stylesheet in %s", page)
	}
	return s.visit("/@family/" + m[1]).body
}

func (s *sharedEnv) publish(c *client, space string, body map[string]any) resp {
	s.t.Helper()
	if body == nil {
		body = map[string]any{}
	}
	if _, ok := body["origin"]; !ok {
		body["origin"] = origin
	}
	return c.do("PUT", "/api/v1/spaces/"+space+"/publish", body)
}

func TestPublishingASpace(t *testing.T) {
	s := newSharedEnv(t)
	if r := s.owner.do("PUT", "/api/v1/spaces/fam/publish", map[string]any{}); r.status != 400 || r.body["field"] != "origin" {
		t.Fatalf("the first time needs the app's address: %d %s", r.status, r.raw)
	}
	if r := s.publish(s.owner, "default", nil); r.status != 409 || r.body["error"] != "space_default" {
		t.Fatalf("the default space stays private: %d %s", r.status, r.raw)
	}
	r := s.publish(s.owner, "fam", map[string]any{"origin": "https://Notes.example/"})
	if r.status != 200 || r.body["handle"] != "family" || r.body["url"] != "https://notes.example/@family/" || r.body["published"] != true {
		t.Fatalf("publish: %d %s", r.status, r.raw)
	}
	trait := spaceByID(t, s.owner, "fam")["traits"].([]any)[0].(map[string]any)
	if trait["key"] != "published" || trait["exposure"] != "Public at notes.example/@family" {
		t.Fatalf("its trait: %v", trait)
	}

	home := s.visit("/@family/")
	if home.status != 200 || !strings.Contains(home.body, "Groceries") || !strings.Contains(home.body, "<title>Family</title>") {
		t.Fatalf("home: %d %s", home.status, home.body)
	}
	if home.header.Get("Set-Cookie") != "" || !strings.Contains(home.header.Get("Cache-Control"), "stale-while-revalidate") ||
		!strings.Contains(home.header.Get("Content-Security-Policy"), "script-src 'self'") {
		t.Fatalf("cacheable by anyone, scripts only its own: %v", home.header)
	}
	if r := s.visit("/@family"); r.status != 301 || r.header.Get("Location") != "/@family/" {
		t.Fatalf("the root has its slash: %d %v", r.status, r.header)
	}
	if r := s.visit("/@nobody/"); r.status != 404 {
		t.Fatalf("no such site: %d", r.status)
	}

	// Its picture comes along, named by its content.
	pic := s.visit("/@family/img/" + s.image + ".png")
	if pic.status != 200 || pic.header.Get("Content-Type") != "image/png" || !strings.Contains(pic.header.Get("Cache-Control"), "immutable") {
		t.Fatalf("picture: %d %v", pic.status, pic.header)
	}
	// So do its fonts and favicon, named by their content too.
	links := regexp.MustCompile(`(?:href="\./|url\()(fonts/[^)]+\.woff2|icon\.[0-9a-f]+\.svg)`).FindAllStringSubmatch(home.body+css(s, home.body), -1)
	if len(links) != 5 {
		t.Fatalf("4 fonts and the favicon: %v", links)
	}
	for _, link := range links {
		r := s.visit("/@family/" + link[1])
		want := map[bool]string{true: "font/woff2", false: "image/svg+xml"}[strings.HasSuffix(link[1], ".woff2")]
		if r.status != 200 || r.header.Get("Content-Type") != want || !strings.Contains(r.header.Get("Cache-Control"), "immutable") {
			t.Fatalf("%s: %d %v", link[1], r.status, r.header)
		}
		// The favicon's colours are its own inline style; nothing else is let in.
		if want == "image/svg+xml" && r.header.Get("Content-Security-Policy") != "default-src 'none'; style-src 'unsafe-inline'" {
			t.Fatalf("favicon CSP: %v", r.header)
		}
	}
	// Only what the manifest names is served: never a file name built from the address.
	other := strings.Repeat("c", 64)
	if r := s.visit("/@family/img/" + other + ".png"); r.status != 404 {
		t.Fatalf("an unpublished picture: %d", r.status)
	}

	// Conditional and compressed.
	etag := home.header.Get("ETag")
	if r := s.visit("/@family/", "If-None-Match", etag); r.status != 304 {
		t.Fatalf("unchanged: %d", r.status)
	}
	plain := s.visit("/@family/", "Accept-Encoding", "identity")
	gz := s.visit("/@family/", "Accept-Encoding", "gzip")
	if plain.header.Get("Content-Encoding") != "" || gz.header.Get("Content-Encoding") != "gzip" ||
		gz.header.Get("ETag") == plain.header.Get("ETag") || !strings.Contains(plain.body, "Groceries") {
		t.Fatalf("gzip, told apart from the plain copy: %v %v", plain.header, gz.header)
	}

	// The settings, as the owner sees them; a custom stylesheet after the site's.
	if r := s.publish(s.owner, "fam", map[string]any{"css": "body { color: rebeccapurple }"}); r.status != 200 || r.body["css"] != "body { color: rebeccapurple }" {
		t.Fatalf("css: %d %s", r.status, r.raw)
	}
	home = s.visit("/@family/")
	style := home.body[strings.Index(home.body, `href="./style.`)+8:]
	style = style[:strings.Index(style, `"`)]
	if css := s.visit("/@family/" + style); !strings.Contains(css.body, "rebeccapurple") {
		t.Fatalf("stylesheet %s: %s", style, css.body[:100])
	}
	if r := s.owner.do("GET", "/api/v1/spaces/fam/publish", nil); r.status != 200 || r.body["handle"] != "family" {
		t.Fatalf("get: %d %s", r.status, r.raw)
	}
}

func TestPublishedSitesFollowTheirNotes(t *testing.T) {
	s := newSharedEnv(t)
	s.publish(s.owner, "fam", nil)
	// A new post, and one with no body: only the first is published.
	s.owner.do("POST", "/api/v1/notes", map[string]any{"operationId": "op-2", "id": "trip", "title": "Trip", "spaceId": "fam", "content": "To the #coast"})
	s.owner.do("POST", "/api/v1/notes", map[string]any{"operationId": "op-3", "id": "empty", "title": "Draft", "spaceId": "fam", "content": "  "})
	home := s.eventually("/@family/", "Trip")
	if strings.Contains(home.body, "Draft") {
		t.Fatal("a note without a body isn't a post")
	}
	tag := s.eventually("/@family/tags/coast/", "Trip")
	if tag.status != 200 {
		t.Fatalf("its tag: %d", tag.status)
	}
	// Search results are rendered for each query, beside the same tags as every page.
	if r := s.visit("/@family/search?q=coast"); !strings.Contains(r.body, `<a href="./tags/coast/" aria-label="#coast, 1 post">`) {
		t.Fatalf("search's rail: %s", r.body)
	}

	// The address keeps working after the title changes.
	post := home.body[strings.Index(home.body, `href="./trip-`)+8:]
	post = post[:strings.Index(post, `"`)]
	s.owner.do("PUT", "/api/v1/notes/trip", map[string]any{"operationId": "op-4", "baseRevision": 1, "title": "Road trip", "content": "To the #coast"})
	// The page shows the new title a moment before the manifest that redirects is in place.
	var r page
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(20 * time.Millisecond) {
		if r = s.visit("/@family/" + post); r.status == 301 {
			break
		}
	}
	if r.status != 301 || !strings.HasPrefix(r.header.Get("Location"), "./road-trip-") {
		t.Fatalf("old slug: %d %v", r.status, r.header)
	}
	if r := s.visit("/@family/no-such-post"); r.status != 404 || !strings.Contains(r.body, "Not found") {
		t.Fatalf("missing: %d %s", r.status, r.body)
	}
}

func TestPublishedSpacesRefuseLockedNotes(t *testing.T) {
	s := newSharedEnv(t)
	locked := "notesd:locked:v1:" + strings.Repeat("A", 40)
	s.owner.do("POST", "/api/v1/notes", map[string]any{"operationId": "op-l", "id": "secret", "title": "S", "content": locked, "spaceId": "fam"})
	r := s.publish(s.owner, "fam", nil)
	if r.status != 409 || r.body["error"] != "space_has_locked" || r.body["count"] != 1.0 {
		t.Fatalf("publish with a locked note: %d %s", r.status, r.raw)
	}
	s.owner.do("DELETE", "/api/v1/notes/secret", map[string]any{"operationId": "op-d", "baseRevision": 1})
	if r := s.publish(s.owner, "fam", nil); r.status != 200 {
		t.Fatalf("publish: %d %s", r.status, r.raw)
	}
	r = s.owner.do("POST", "/api/v1/notes", map[string]any{"operationId": "op-l2", "id": "s2", "title": "S", "content": locked, "spaceId": "fam"})
	if r.status != 422 || r.body["error"] != "locked_in_exposed_space" {
		t.Fatalf("lock in a published space: %d %s", r.status, r.raw)
	}
	// Taken down, it takes them again; its address stays its own.
	if r := s.owner.do("DELETE", "/api/v1/spaces/fam/publish", nil); r.status != 204 {
		t.Fatalf("unpublish: %d %s", r.status, r.raw)
	}
	if r := s.visit("/@family/"); r.status != 404 {
		t.Fatalf("taken down: %d", r.status)
	}
	if r := s.owner.do("POST", "/api/v1/notes", map[string]any{"operationId": "op-l3", "id": "s3", "title": "S", "content": locked, "spaceId": "fam"}); r.status != 201 {
		t.Fatalf("lock once unpublished: %d %s", r.status, r.raw)
	}
	if r := s.owner.do("GET", "/api/v1/spaces/fam/publish", nil); r.body["handle"] != "family" || r.body["published"] != false {
		t.Fatalf("the handle stays: %s", r.raw)
	}
}

func TestHandles(t *testing.T) {
	s := newSharedEnv(t)
	s.publish(s.owner, "fam", nil)
	s.kid.do("PUT", "/api/v1/spaces/fam2", map[string]any{"name": "Family"})
	s.kid.do("POST", "/api/v1/notes", map[string]any{"operationId": "k-1", "id": "k1", "title": "Hi", "spaceId": "fam2", "content": "Hello"})
	if r := s.publish(s.kid, "fam2", nil); r.status != 200 || r.body["handle"] != "family-2" {
		t.Fatalf("a name another site has gets a number: %d %s", r.status, r.raw)
	}
	if r := s.publish(s.kid, "fam2", map[string]any{"handle": "family"}); r.status != 409 || r.body["error"] != "handle_taken" {
		t.Fatalf("taken: %d %s", r.status, r.raw)
	}
	if r := s.publish(s.kid, "fam2", map[string]any{"handle": "-Bad"}); r.status != 400 || r.body["field"] != "handle" {
		t.Fatalf("bad: %d %s", r.status, r.raw)
	}
	if r := s.publish(s.kid, "fam2", map[string]any{"handle": "kids"}); r.status != 200 || r.body["url"] != origin+"/@kids/" {
		t.Fatalf("rename: %d %s", r.status, r.raw)
	}
	if r := s.visit("/@kids/"); r.status != 200 || s.visit("/@family-2/").status != 404 {
		t.Fatalf("the old address goes: %d", r.status)
	}
	// Deleting the space frees its address.
	s.kid.do("DELETE", "/api/v1/notes/k1", map[string]any{"operationId": "k-d", "baseRevision": 1})
	if r := s.kid.do("DELETE", "/api/v1/spaces/fam2", nil); r.status != 204 {
		t.Fatalf("delete: %d %s", r.status, r.raw)
	}
	if r := s.visit("/@kids/"); r.status != 404 {
		t.Fatalf("deleted: %d", r.status)
	}
}

func TestPublishingASharedSpace(t *testing.T) {
	s := newSharedEnv(t)
	s.publish(s.owner, "fam", map[string]any{"css": "h1 { color: teal }"})
	// Shared after it was published: its site and stylesheet move with its notes.
	s.share()
	s.kid.do("POST", "/api/v1/notes", map[string]any{"operationId": "k-1", "id": "kn", "title": "From Kid", "spaceId": "fam", "content": "Hi"})
	s.eventually("/@family/", "From Kid")
	if r := s.owner.do("GET", "/api/v1/spaces/fam/publish", nil); r.status != 200 || r.body["css"] != "h1 { color: teal }" {
		t.Fatalf("settings moved with it: %d %s", r.status, r.raw)
	}
	// Its owner manages it, like its name; a member sees the trait.
	if r := s.kid.do("PUT", "/api/v1/spaces/fam/publish", map[string]any{"origin": origin}); r.status != 403 {
		t.Fatalf("a member publishes: %d %s", r.status, r.raw)
	}
	traits := spaceByID(t, s.kid, "fam")["traits"].([]any)
	if len(traits) != 2 || traits[1].(map[string]any)["key"] != "published" {
		t.Fatalf("member's traits: %v", traits)
	}
	if r := s.publish(s.owner, "fam", map[string]any{"handle": "fam"}); r.status != 200 {
		t.Fatalf("owner changes it: %d %s", r.status, r.raw)
	}
}

func TestRemovingAnAccountTakesItsSitesDown(t *testing.T) {
	s := newSharedEnv(t)
	s.kid.do("PUT", "/api/v1/spaces/k", map[string]any{"name": "Kid"})
	s.kid.do("POST", "/api/v1/notes", map[string]any{"operationId": "k-1", "id": "k1", "title": "Hi", "spaceId": "k", "content": "Hello"})
	if r := s.publish(s.kid, "k", nil); r.status != 200 {
		t.Fatalf("publish: %d %s", r.status, r.raw)
	}
	if r := s.visit("/@kid/"); r.status != 200 {
		t.Fatalf("published: %d", r.status)
	}
	if r := s.owner.do("DELETE", fmt.Sprintf("/api/v1/users/%d", s.kidID), nil); r.status != 204 {
		t.Fatalf("remove: %d %s", r.status, r.raw)
	}
	if r := s.visit("/@kid/"); r.status != 404 {
		t.Fatalf("their site comes down: %d", r.status)
	}
}

func TestPublicSearch(t *testing.T) {
	s := newSharedEnv(t)
	s.publish(s.owner, "fam", nil)
	s.owner.do("POST", "/api/v1/notes", map[string]any{"operationId": "op-2", "id": "milk2", "title": "", "spaceId": "fam", "content": "Milk without a title"})
	s.owner.do("POST", "/api/v1/notes", map[string]any{"operationId": "op-3", "id": "other", "title": "Milk elsewhere", "content": "Milk"})
	r := s.visit("/@family/search?q=milk")
	if r.status != 200 || !strings.Contains(r.body, "Groceries") || !strings.Contains(r.body, "<mark>Milk</mark>") {
		t.Fatalf("search: %d %s", r.status, r.body)
	}
	if strings.Contains(r.body, "without a title") || strings.Contains(r.body, "elsewhere") {
		t.Fatal("only what the site shows")
	}
	if r.header.Get("X-Robots-Tag") != "noindex" || !strings.Contains(r.body, `name="robots" content="noindex"`) {
		t.Fatalf("not indexed: %v", r.header)
	}
	if r := s.visit("/@family/search"); r.status != 200 || !strings.Contains(r.body, `class="search-form"`) {
		t.Fatalf("the form: %d", r.status)
	}
	// A visitor searching too much is told to wait; others aren't.
	for i := 0; i < 40; i++ {
		r = s.visit(fmt.Sprintf("/@family/search?q=milk%d", i), "X-Forwarded-For", "203.0.113.9")
	}
	if r.status != 429 || r.header.Get("Retry-After") == "" {
		t.Fatalf("too many: %d", r.status)
	}
	if r := s.visit("/@family/search?q=milk", "X-Forwarded-For", "203.0.113.10"); r.status != 200 {
		t.Fatalf("another visitor: %d", r.status)
	}
}

func TestPublicSearchLooksPastNotesTheSiteDoesntShow(t *testing.T) {
	s := newSharedEnv(t)
	s.publish(s.owner, "fam", nil)
	s.owner.do("POST", "/api/v1/notes", map[string]any{"operationId": "p", "id": "tart", "title": "Tart", "spaceId": "fam",
		"content": "A long story about baking, butter, flour, sugar, ovens and patience, with one pear somewhere in it."})
	// More untitled matches than one batch, each a better match than the post.
	for i := range 60 {
		s.owner.do("POST", "/api/v1/notes", map[string]any{"operationId": fmt.Sprint("u", i), "id": fmt.Sprint("u", i),
			"title": "", "spaceId": "fam", "content": "pear pear pear"})
	}
	s.eventually("/@family/", ">Tart<")
	if r := s.visit("/@family/search?q=Pear"); r.status != 200 || !strings.Contains(r.body, ">Tart<") ||
		!strings.Contains(r.body, `value="Pear"`) {
		t.Fatalf("search: %d %s", r.status, r.body)
	}
	// Another spelling is its own answer: the page shows the query as typed.
	if r := s.visit("/@family/search?q=pear"); !strings.Contains(r.body, `value="pear"`) {
		t.Fatalf("cached across spellings: %s", r.body)
	}
}

func TestASiteOnItsOwnDomain(t *testing.T) {
	s := newSharedEnv(t)
	s.publish(s.owner, "fam", nil)
	for _, bad := range []string{"not a domain", "."} {
		if r := s.publish(s.owner, "fam", map[string]any{"domain": bad}); r.status != 400 || r.body["field"] != "domain" {
			t.Fatalf("an invalid domain %q: %d %s", bad, r.status, r.raw)
		}
	}
	r := s.publish(s.owner, "fam", map[string]any{"domain": " Blog.Example.com. "})
	if r.status != 200 || r.body["domain"] != "blog.example.com" || r.body["url"] != "https://blog.example.com/" {
		t.Fatalf("domain: %d %s", r.status, r.raw)
	}
	trait := spaceByID(t, s.owner, "fam")["traits"].([]any)[0].(map[string]any)
	if trait["summary"] != "At blog.example.com" {
		t.Fatalf("its trait: %v", trait)
	}
	// Its absolute links are the domain's; served at /@family/ too, it names the domain as the real address.
	home := s.eventually("/@family/", `<link rel="canonical" href="https://blog.example.com/">`)
	if !strings.Contains(s.visit("/@family/feed.xml").body, "<id>https://blog.example.com/groceries-") {
		t.Fatalf("feed links: %s", home.body)
	}

	// The owner's proxy maps the domain onto /@family/: everything in the site works from there.
	proxy := httptest.NewServer(&httputil.ReverseProxy{Rewrite: func(pr *httputil.ProxyRequest) {
		pr.SetURL(mustParse(t, s.srv.URL))
		pr.Out.URL.Path = "/@family" + pr.In.URL.Path
		pr.Out.URL.RawPath = ""
	}})
	t.Cleanup(proxy.Close)
	get := func(path string) page {
		t.Helper()
		hc := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
		res, err := hc.Get(proxy.URL + path)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		return page{status: res.StatusCode, header: res.Header, body: string(b)}
	}
	root := get("/")
	post := root.body[strings.Index(root.body, `href="./groceries-`)+8:]
	post = post[:strings.Index(post, `"`)]
	if root.status != 200 || get("/"+post).status != 200 {
		t.Fatalf("home and a post through the proxy: %d", root.status)
	}
	// A post's old address redirects within the domain, not to the app's /@family/.
	short := post[strings.LastIndex(post, "-"):]
	if r := get("/old-title" + short); r.status != 301 || r.header.Get("Location") != "./"+post {
		t.Fatalf("old slug: %d %v", r.status, r.header)
	}
	// Missing, at any depth: its links lead back to the site's root from where it was asked for.
	if r := get("/no/such/page"); r.status != 404 || !strings.Contains(r.body, `<a href="../../">See every post</a>`) {
		t.Fatalf("missing: %d %s", r.status, r.body)
	}
	// An escaped / is part of one segment, as the browser resolves links from it.
	if r := s.visit("/@family/no%2Fsuch"); r.status != 404 || !strings.Contains(r.body, `<a href="./">See every post</a>`) {
		t.Fatalf("missing, escaped: %d %s", r.status, r.body)
	}

	if r := s.publish(s.owner, "fam", map[string]any{"domain": ""}); r.status != 200 || r.body["url"] != origin+"/@family/" {
		t.Fatalf("domain dropped: %d %s", r.status, r.raw)
	}
}

func mustParse(t *testing.T, raw string) *url.URL {
	t.Helper()
	u, err := url.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	return u
}
