package api

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
)

// DefaultReleasesURL answers with the newest non-prerelease release.
const DefaultReleasesURL = "https://api.github.com/repos/cassiozen/notesd/releases/latest"

// GET /api/v1/update — the running version and, when this server can update
// itself, the newest release. GitHub is only asked when it can, so a server
// without self-update never calls out.
func (s *Server) updateStatus(w http.ResponseWriter, r *http.Request) {
	resp := map[string]any{
		"current":  s.cfg.Version,
		"enabled":  s.cfg.UpdateRequest != "",
		"editable": sessionFrom(r.Context()).IsAdmin,
	}
	if s.cfg.UpdateRequest != "" {
		latest := s.releases.latest(r.Context())
		_, err := os.Stat(s.cfg.UpdateRequest)
		resp["latest"] = latest
		resp["newer"] = newerVersion(latest, s.cfg.Version)
		resp["requested"] = err == nil
	}
	writeJSON(w, http.StatusOK, resp)
}

// POST /api/v1/update — asks notesd-update (docs/operations.md) to install
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

// releases remembers the newest release tag for an hour, which keeps well
// under GitHub's 60 unauthenticated requests an hour.
type releases struct {
	url  string
	http *http.Client

	mu      sync.Mutex
	tag     string
	checked time.Time
}

func newReleases(url string) *releases {
	if url == "" {
		url = DefaultReleasesURL
	}
	return &releases{url: url, http: &http.Client{Timeout: 10 * time.Second}}
}

// latest is the newest release tag, or "" if GitHub hasn't answered yet.
// ponytail: holds the lock across the request; callers are admins opening Settings, not a hot path.
func (rs *releases) latest(ctx context.Context) string {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	if time.Since(rs.checked) < time.Hour {
		return rs.tag
	}
	rs.checked = time.Now() // failures too, so an outage isn't asked every time
	// Not cancelled with the caller's request: a closed tab would otherwise
	// be remembered as a failed check for an hour. The client's timeout bounds it.
	req, err := http.NewRequestWithContext(context.WithoutCancel(ctx), http.MethodGet, rs.url, nil)
	if err != nil {
		return rs.tag
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	res, err := rs.http.Do(req)
	if err != nil {
		return rs.tag
	}
	defer res.Body.Close()
	var body struct {
		TagName string `json:"tag_name"`
	}
	if res.StatusCode == http.StatusOK && json.NewDecoder(io.LimitReader(res.Body, 1<<20)).Decode(&body) == nil {
		rs.tag = body.TagName
	}
	return rs.tag
}

// newerVersion reports whether latest is a later vX.Y.Z than current. Builds
// between releases (v0.10.0-4-g51d93ed), release candidates and dev builds
// aren't releases, so nothing is offered to them.
func newerVersion(latest, current string) bool {
	l, ok := parseVersion(latest)
	c, ok2 := parseVersion(current)
	return ok && ok2 && slices.Compare(l[:], c[:]) > 0
}

func parseVersion(v string) (n [3]int, ok bool) {
	parts := strings.Split(strings.TrimPrefix(v, "v"), ".")
	if !strings.HasPrefix(v, "v") || len(parts) != 3 {
		return n, false
	}
	for i, p := range parts {
		var err error
		if n[i], err = strconv.Atoi(p); err != nil || n[i] < 0 {
			return n, false
		}
	}
	return n, true
}
