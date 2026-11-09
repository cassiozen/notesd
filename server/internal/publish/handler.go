package publish

import (
	"bytes"
	"context"
	"net"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"notes/server/internal/auth"
	"notes/server/internal/search"
)

const (
	// publicReaders is how many read connections search has to each file: the apps keep theirs.
	publicReaders = 2
	// searchesPerMinute is what one visitor may search.
	searchesPerMinute = 30
	// cachedSearches is how many answers are kept, each for cacheFor.
	cachedSearches = 512
	cacheFor       = time.Minute
	maxQuery       = 200
	searchResults  = 30
	searchBatches  = 4
)

// limits keep the public's work from crowding out the apps (§7.10): a few searches at a time, each
// visitor's searches per minute, and recent answers kept.
type limits struct {
	busy    chan struct{}
	visitor *auth.Limiter

	mu    sync.Mutex
	cache map[string]cached
}

type cached struct {
	page []byte
	at   time.Time
}

func newLimits() *limits {
	return &limits{
		busy:    make(chan struct{}, max(2, runtime.NumCPU())),
		visitor: auth.NewLimiter(searchesPerMinute, time.Minute),
		cache:   map[string]cached{},
	}
}

var publicCSP = "default-src 'none'; script-src 'self'; style-src 'self' https:; font-src 'self' https: data:; " +
	"img-src 'self' https: data:; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"

// IsPublic reports whether a request is for a published space.
func IsPublic(r *http.Request) bool { return strings.HasPrefix(r.URL.Path, "/@") }

// ServeHTTP serves /@<handle>/…: files a site's manifest names, and its search. Nothing here reads a
// cookie or sets one, so any cache between may keep what it answers.
func (s *Service) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	h := w.Header()
	h.Set("Content-Security-Policy", publicCSP)
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Referrer-Policy", "strict-origin-when-cross-origin")

	handle, rest, slash := strings.Cut(strings.TrimPrefix(r.URL.Path, "/@"), "/")
	s.mu.RLock()
	site, ok := s.sites[handle]
	m := s.manifests[handle]
	s.mu.RUnlock()
	if !ok {
		http.NotFound(w, r)
		return
	}
	if !slash {
		to := "/@" + handle + "/"
		if r.URL.RawQuery != "" {
			to += "?" + r.URL.RawQuery
		}
		http.Redirect(w, r, to, http.StatusMovedPermanently)
		return
	}
	if m == nil {
		// Published a moment ago; its first render is under way.
		h.Set("Retry-After", "5")
		http.Error(w, "this site is being published; try again in a moment", http.StatusServiceUnavailable)
		return
	}
	if rest == "search" && strings.TrimSpace(r.URL.Query().Get("q")) != "" {
		s.search(w, r, site, m)
		return
	}
	if name, ok := m.Routes[rest]; ok {
		s.serveFile(w, r, handle, m, name, http.StatusOK)
		return
	}
	if to, ok := renamed(m, rest); ok {
		http.Redirect(w, r, "/@"+handle+"/"+to, http.StatusMovedPermanently)
		return
	}
	s.serveFile(w, r, handle, m, "404.html", http.StatusNotFound)
}

var postPath = regexp.MustCompile(`^[a-z0-9-]*-([0-9a-f]{8,64})$`)

// renamed is where a post's address went when its title (and slug) changed since the link was made.
func renamed(m *manifest, rest string) (string, bool) {
	sm := postPath.FindStringSubmatch(rest)
	if sm == nil {
		return "", false
	}
	to, ok := m.Posts[sm[1]]
	return to, ok
}

// serveFile answers with one of a site's files, compressed when the browser takes it.
func (s *Service) serveFile(w http.ResponseWriter, r *http.Request, handle string, m *manifest, name string, status int) {
	h := w.Header()
	switch {
	case strings.HasPrefix(name, "img/") || name == m.Style || name == m.Script:
		// Named by their content: a change is a new name.
		h.Set("Cache-Control", "public, max-age=31536000, immutable")
	case status == http.StatusNotFound:
		h.Set("Cache-Control", "public, max-age=60")
	default:
		h.Set("Cache-Control", "public, max-age=60, stale-while-revalidate=86400")
	}
	h.Set("Content-Type", contentType(name))
	etag := m.ETags[name]
	file := name
	if m.Gzipped[name] {
		h.Add("Vary", "Accept-Encoding")
		if acceptsGzip(r) {
			file += ".gz"
			etag = strings.TrimSuffix(etag, `"`) + `-gz"`
			h.Set("Content-Encoding", "gzip")
		}
	}
	f, err := os.Open(filepath.Join(s.dir, handle, filepath.FromSlash(file)))
	if err != nil {
		h.Del("Content-Encoding")
		h.Set("Cache-Control", "no-store")
		h.Set("Retry-After", "5")
		http.Error(w, "this site is being published; try again in a moment", http.StatusServiceUnavailable)
		return
	}
	defer f.Close()
	if status != http.StatusOK {
		w.WriteHeader(status)
		if r.Method != http.MethodHead {
			_, _ = f.WriteTo(w)
		}
		return
	}
	h.Set("ETag", etag)
	info, err := f.Stat()
	if err != nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	http.ServeContent(w, r, "", info.ModTime(), f)
}

func contentType(name string) string {
	switch path.Ext(name) {
	case ".html":
		return "text/html; charset=utf-8"
	case ".css":
		return "text/css; charset=utf-8"
	case ".js":
		return "text/javascript; charset=utf-8"
	case ".xml":
		if name == "feed.xml" {
			return "application/atom+xml; charset=utf-8"
		}
		return "application/xml; charset=utf-8"
	case ".jpg":
		return "image/jpeg"
	case ".png":
		return "image/png"
	case ".webp":
		return "image/webp"
	case ".gif":
		return "image/gif"
	}
	return "application/octet-stream"
}

func acceptsGzip(r *http.Request) bool {
	for _, part := range strings.Split(r.Header.Get("Accept-Encoding"), ",") {
		enc, params, _ := strings.Cut(strings.TrimSpace(part), ";")
		if !strings.EqualFold(strings.TrimSpace(enc), "gzip") {
			continue
		}
		q, ok := strings.CutPrefix(strings.TrimSpace(params), "q=")
		if !ok {
			return true
		}
		weight, err := strconv.ParseFloat(strings.TrimSpace(q), 64)
		return err == nil && weight > 0
	}
	return false
}

// search answers a query from the space's notes, within the limits.
func (s *Service) search(w http.ResponseWriter, r *http.Request, site Site, m *manifest) {
	h := w.Header()
	h.Set("X-Robots-Tag", "noindex")
	q := strings.Join(strings.Fields(r.URL.Query().Get("q")), " ")
	if len(q) > maxQuery {
		q = strings.ToValidUTF8(q[:maxQuery], "")
	}
	if ok, wait := s.limits.visitor.Take(visitorKey(r)); !ok {
		h.Set("Retry-After", strconv.Itoa(int(wait.Seconds())+1))
		http.Error(w, "too many searches; try again in a minute", http.StatusTooManyRequests)
		return
	}

	// As typed: the page shows the query.
	key := site.Handle + "\x00" + q
	if page, ok := s.limits.get(key, s.now()); ok {
		writeSearch(w, page)
		return
	}
	select {
	case s.limits.busy <- struct{}{}:
		defer func() { <-s.limits.busy }()
	default:
		h.Set("Retry-After", "5")
		http.Error(w, "busy; try again in a moment", http.StatusServiceUnavailable)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	db, err := s.reader(ctx, site.File)
	if err != nil {
		s.log.Error("public search", "handle", site.Handle, "err", err)
		http.Error(w, "search is unavailable", http.StatusServiceUnavailable)
		return
	}
	root := rootFor("search")
	p := page{Site: siteInfo{Name: m.Name, Accent: m.Accent, Style: m.Style, Script: m.Script},
		Kind: "search", Root: root, Title: q + " · Search", NoIndex: true, Query: q}
	// The index has the space's notes the site doesn't show (untitled, empty), so matches come in batches until
	// enough are posts. Bounded: one search must stay cheap.
	for batch := 0; batch < searchBatches && len(p.Posts) < searchResults; batch++ {
		results, err := search.Search(ctx, db, q, search.Filter{Space: m.SpaceID}, search.MaxLimit, batch*search.MaxLimit)
		if err != nil {
			s.log.Error("public search", "handle", site.Handle, "err", err)
			http.Error(w, "search is unavailable", http.StatusServiceUnavailable)
			return
		}
		for _, res := range results {
			to, ok := m.Notes[res.ID]
			if !ok {
				continue
			}
			p.Posts = append(p.Posts, listItem{Href: root + to, Title: res.Title, Excerpt: excerptHTML(res.Excerpt)})
			if len(p.Posts) == searchResults {
				break
			}
		}
		if len(results) < search.MaxLimit {
			break
		}
	}
	p.Count = plural(len(p.Posts), "post", "posts")
	var b bytes.Buffer
	if err := pages.ExecuteTemplate(&b, "page", p); err != nil {
		s.log.Error("public search", "handle", site.Handle, "err", err)
		http.Error(w, "search is unavailable", http.StatusServiceUnavailable)
		return
	}
	s.limits.put(key, b.Bytes(), s.now())
	writeSearch(w, b.Bytes())
}

func writeSearch(w http.ResponseWriter, page []byte) {
	h := w.Header()
	h.Set("Content-Type", "text/html; charset=utf-8")
	h.Set("Cache-Control", "public, max-age=60")
	_, _ = w.Write(page)
}

func (l *limits) get(key string, now time.Time) ([]byte, bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	c, ok := l.cache[key]
	if !ok || now.Sub(c.at) > cacheFor {
		return nil, false
	}
	return c.page, true
}

func (l *limits) put(key string, page []byte, now time.Time) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.cache) >= cachedSearches {
		for k, c := range l.cache {
			if now.Sub(c.at) > cacheFor {
				delete(l.cache, k)
			}
		}
		if len(l.cache) >= cachedSearches {
			l.cache = map[string]cached{}
		}
	}
	l.cache[key] = cached{page: page, at: now}
}

// visitorKey is who's searching: the connection's address, or the address a proxy on this machine or
// network forwarded for (Caddy, a tunnel), which is every visitor's connection otherwise.
func visitorKey(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	ip := net.ParseIP(host)
	if ip == nil || !(ip.IsLoopback() || ip.IsPrivate()) {
		return host
	}
	fwd := r.Header.Get("X-Forwarded-For")
	if i := strings.LastIndexByte(fwd, ','); i >= 0 {
		fwd = fwd[i+1:]
	}
	if fwd = strings.TrimSpace(fwd); fwd != "" {
		return fwd
	}
	return host
}
