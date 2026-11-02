package api

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
)

// DefaultReleasesURL lists the newest releases, newest first.
const DefaultReleasesURL = "https://api.github.com/repos/cassiozen/notesd/releases?per_page=100"

// GET /api/v1/update — the running version and, when this server can update
// itself, the newest release of the same major version (latest), which
// notesd-update installs, and of a later one (majorAvailable), which an
// admin installs by hand after reading its upgrade notes. GitHub is only
// asked when it can, so a server without self-update never calls out.
func (s *Server) updateStatus(w http.ResponseWriter, r *http.Request) {
	resp := map[string]any{
		"current":  s.cfg.Version,
		"enabled":  s.cfg.UpdateRequest != "",
		"editable": sessionFrom(r.Context()).IsAdmin,
	}
	if s.cfg.UpdateRequest != "" {
		latest, major := pickReleases(s.releases.tags(r.Context()), s.cfg.Version)
		_, err := os.Stat(s.cfg.UpdateRequest)
		resp["latest"] = latest
		resp["newer"] = newerVersion(latest, s.cfg.Version)
		resp["requested"] = err == nil
		if major != "" {
			resp["majorAvailable"] = major
		}
	}
	writeJSON(w, http.StatusOK, resp)
}

// pickReleases returns the newest of tags with current's major version, and
// the newest of a later major, or "" for either if there's none. A current
// version that isn't a release (dev) has no major: latest is then the newest
// of all, never offered as newer.
func pickReleases(tags []string, current string) (latest, major string) {
	cur, curOK := releaseMajor(current)
	var best, bestMajor [3]int
	for _, tag := range tags {
		v, ok := parseVersion(tag)
		if !ok {
			continue
		}
		switch {
		case !curOK || v[0] == cur:
			if latest == "" || slices.Compare(v[:], best[:]) > 0 {
				latest, best = tag, v
			}
		case v[0] > cur:
			if major == "" || slices.Compare(v[:], bestMajor[:]) > 0 {
				major, bestMajor = tag, v
			}
		}
	}
	return latest, major
}

// releaseMajor is the major version of a release or of a build between
// releases (v1.2.0-4-g51d93ed).
func releaseMajor(v string) (int, bool) {
	rest, ok := strings.CutPrefix(v, "v")
	if !ok {
		return 0, false
	}
	m, _, ok := strings.Cut(rest, ".")
	n, err := strconv.Atoi(m)
	return n, ok && err == nil && n >= 0
}

// POST /api/v1/update — asks notesd-update (docs/guides/self-hosting/upgrading.md) to install
// the newest release. notesd only leaves a file in its data directory; the
// root service decides what to install, so this can't run anything else.
func (s *Server) requestUpdate(w http.ResponseWriter, r *http.Request) {
	if s.cfg.UpdateRequest == "" {
		writeError(w, http.StatusConflict, "updates_disabled", "this server isn't set up to update itself")
		return
	}
	if err := os.WriteFile(s.cfg.UpdateRequest, nil, 0o600); err != nil {
		s.writeErr(w, r, err)
		return
	}
	s.log.Info("update requested", "username", sessionFrom(r.Context()).Username, "version", s.cfg.Version)
	w.WriteHeader(http.StatusNoContent)
}

// releases remembers the published release tags for an hour, which keeps
// well under GitHub's 60 unauthenticated requests an hour.
type releases struct {
	url  string
	http *http.Client

	mu      sync.Mutex
	list    []string
	checked time.Time
}

func newReleases(url string) *releases {
	if url == "" {
		url = DefaultReleasesURL
	}
	return &releases{url: url, http: &http.Client{Timeout: 10 * time.Second}}
}

// maxReleasePages bounds how many pages of releases are read: 1,000
// releases reach back far enough for any server still running, and ten
// requests an hour keep well under GitHub's limit.
const maxReleasePages = 10

// tags are the published releases' tags (no drafts or prereleases), or nil
// if GitHub hasn't answered yet. A server a major version behind needs
// releases past the first page, so the list is followed page by page.
// ponytail: holds the lock across the requests; callers are admins opening Settings, not a hot path.
func (rs *releases) tags(ctx context.Context) []string {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	if time.Since(rs.checked) < time.Hour {
		return rs.list
	}
	rs.checked = time.Now() // failures too, so an outage isn't asked every time
	// Not cancelled with the caller's request: a closed tab would otherwise
	// be remembered as a failed check for an hour. The client's timeout bounds it.
	ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
	defer cancel()
	list := []string{}
	url := rs.url
	for page := 0; page < maxReleasePages && url != ""; page++ {
		tags, next, ok := rs.page(ctx, url)
		if !ok {
			// A partial list could offer an older release as the newest.
			return rs.list
		}
		list = append(list, tags...)
		url = next
	}
	rs.list = list
	return rs.list
}

// page reads one page of releases and the next page's URL, if any.
func (rs *releases) page(ctx context.Context, url string) (tags []string, next string, ok bool) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, "", false
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	res, err := rs.http.Do(req)
	if err != nil {
		return nil, "", false
	}
	defer res.Body.Close()
	var body []struct {
		TagName    string `json:"tag_name"`
		Draft      bool   `json:"draft"`
		Prerelease bool   `json:"prerelease"`
	}
	if res.StatusCode != http.StatusOK || json.NewDecoder(io.LimitReader(res.Body, 8<<20)).Decode(&body) != nil {
		return nil, "", false
	}
	for _, rel := range body {
		if !rel.Draft && !rel.Prerelease {
			tags = append(tags, rel.TagName)
		}
	}
	return tags, nextLink(res.Header.Get("Link")), true
}

// nextLink is the rel="next" URL of a Link header, as GitHub paginates.
func nextLink(h string) string {
	for _, part := range strings.Split(h, ",") {
		target, params, ok := strings.Cut(strings.TrimSpace(part), ";")
		if !ok || !strings.HasPrefix(target, "<") || !strings.HasSuffix(target, ">") {
			continue
		}
		for _, p := range strings.Split(params, ";") {
			if strings.ReplaceAll(strings.TrimSpace(p), " ", "") == `rel="next"` {
				return target[1 : len(target)-1]
			}
		}
	}
	return ""
}

// newerVersion reports whether latest is a later vX.Y.Z than current. Builds
// between releases (v0.10.0-4-g51d93ed), release candidates and dev builds
// aren't releases, so nothing is offered to them.
func newerVersion(latest, current string) bool {
	l, ok := parseVersion(latest)
	c, ok2 := parseVersion(current)
	return ok && ok2 && slices.Compare(l[:], c[:]) > 0
}

var releaseTag = regexp.MustCompile(`^v\d+\.\d+\.\d+$`)

func parseVersion(v string) (n [3]int, ok bool) {
	if !releaseTag.MatchString(v) {
		return n, false
	}
	for i, p := range strings.Split(v[1:], ".") {
		var err error
		if n[i], err = strconv.Atoi(p); err != nil {
			return n, false
		}
	}
	return n, true
}
