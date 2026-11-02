package api

import (
	"net/http"
	"regexp"
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

// describedVersion is an app's version as git describe gives it: a release,
// or a build that many commits past one, maybe with uncommitted changes.
var describedVersion = regexp.MustCompile(`^([0-9]+)\.([0-9]+)\.([0-9]+)(?:-[0-9]+-g[0-9a-f]+)?(?:-dirty)?$`)

// appVersion parses an app's X.Y.Z, with or without a leading v. A build
// past a release has at least that release's code, so it counts as it.
// Anything else is unparseable: a pre-release ("-rc1"), a build outside a
// tagged checkout ("0.0.0-dev"), or 0.0.0 itself, which no release is.
func appVersion(v string) (n [3]int, ok bool) {
	m := describedVersion.FindStringSubmatch(strings.TrimPrefix(v, "v"))
	if m == nil {
		return n, false
	}
	for i := range n {
		var err error
		if n[i], err = strconv.Atoi(m[i+1]); err != nil {
			return n, false
		}
	}
	return n, n != [3]int{}
}
