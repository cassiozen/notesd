package api

import (
	"net/http"
	"slices"
	"strconv"
	"strings"
)

// APIVersion is the /api/v1 contract's revision, for clients to check
// what this server speaks before they sync.
const APIVersion = 1

// Client platforms that send X-Notesd-Client.
var clientPlatforms = map[string]bool{"android": true, "ios": true, "web": true}

func (s *Server) version() string {
	if s.cfg.Version == "" {
		return "dev"
	}
	return s.cfg.Version
}

// requestClient is the app that sent r as platform and version, from
// "X-Notesd-Client: android/1.0.0" or else "User-Agent: notesd-android/1.0.0",
// or ok false for anything else (a browser loading an image, an older app,
// a script).
func requestClient(r *http.Request) (platform, version string, ok bool) {
	if h := strings.TrimSpace(r.Header.Get("X-Notesd-Client")); h != "" {
		platform, version, ok = strings.Cut(h, "/")
	} else {
		ua, _, _ := strings.Cut(strings.TrimSpace(r.UserAgent()), " ")
		var rest string
		if rest, ok = strings.CutPrefix(ua, "notesd-"); ok {
			platform, version, ok = strings.Cut(rest, "/")
		}
	}
	if !ok || !clientPlatforms[platform] || version == "" {
		return "", "", false
	}
	return platform, strings.TrimPrefix(version, "v"), true
}

// checkClient answers 426 to an app older than this server's minimum for
// its platform (Config.MinClient). An app or minimum whose version can't be
// parsed passes: refusing would strand a build nobody can tell is too old.
func (s *Server) checkClient(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Status stays open: an app reads it to tell what this server is.
		if !strings.HasPrefix(r.URL.Path, "/api/v1/") || r.URL.Path == "/api/v1/auth/status" || len(s.cfg.MinClient) == 0 {
			next.ServeHTTP(w, r)
			return
		}
		platform, version, ok := requestClient(r)
		if !ok {
			next.ServeHTTP(w, r)
			return
		}
		minVersion := s.cfg.MinClient[platform]
		have, ok := appVersion(version)
		want, ok2 := appVersion(minVersion)
		if !ok || !ok2 || slices.Compare(have[:], want[:]) >= 0 {
			next.ServeHTTP(w, r)
			return
		}
		writeJSON(w, http.StatusUpgradeRequired, errorBody{
			Error:      "upgrade_required",
			Message:    "this app is too old for this server; update notesd",
			MinVersion: strings.TrimPrefix(minVersion, "v"),
		})
	})
}

// appVersion parses an app's X.Y.Z, with or without a leading v. Anything
// after the numbers ("-rc1", "-4-g51d93ed" between releases) is ignored:
// such a build has at least that release's code.
func appVersion(v string) (n [3]int, ok bool) {
	v = strings.TrimPrefix(v, "v")
	if i := strings.IndexAny(v, "-+"); i >= 0 {
		v = v[:i]
	}
	parts := strings.Split(v, ".")
	if len(parts) != 3 {
		return n, false
	}
	for i, p := range parts {
		var err error
		if n[i], err = strconv.Atoi(p); err != nil {
			return n, false
		}
	}
	return n, true
}
