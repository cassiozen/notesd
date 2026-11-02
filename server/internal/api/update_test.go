package api_test

import (
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"notes/server/internal/api"
)

// releasesJSON is a GitHub /releases list, newest first, pretty-printed as
// GitHub serves it.
const releasesJSON = `[
  {
    "url": "https://api.github.com/repos/cassiozen/notesd/releases/4",
    "author": {"login": "cassiozen", "type": "User"},
    "tag_name": "v2.0.0",
    "name": "v2.0.0",
    "draft": false,
    "prerelease": false,
    "assets": [{"name": "SHA256SUMS", "uploader": {"login": "cassiozen"}}],
    "body": "Read the notes. \"draft\": true, \"tag_name\": \"v1.99.0\""
  },
  {"tag_name": "v1.4.0", "name": "", "draft": true, "prerelease": false, "assets": []},
  {"tag_name": "v1.4.0-rc1", "name": "", "draft": false, "prerelease": true, "assets": []},
  {"tag_name": "v1.3.0", "name": "", "draft": false, "prerelease": false, "assets": []},
  {"tag_name": "v1.10.0-beta", "name": "", "draft": false, "prerelease": false, "assets": []},
  {"tag_name": "v1.2.10", "name": "", "draft": false, "prerelease": false, "assets": []},
  {"tag_name": "v0.9.0", "name": "", "draft": false, "prerelease": false, "assets": []}
]`

func TestUpdate(t *testing.T) {
	calls := 0
	gh := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		_, _ = w.Write([]byte(releasesJSON))
	}))
	t.Cleanup(gh.Close)
	request := filepath.Join(t.TempDir(), "update-request")
	e := newEnvWith(t, api.Config{Version: "v1.2.3", UpdateRequest: request, ReleasesURL: gh.URL})
	e.setupOwner()
	e.addKid()
	a := e.loginAs(owner, password)
	b := e.loginAs(kid, kidPassword)

	r := a.do("GET", "/api/v1/update", nil)
	if r.status != 200 || r.body["latest"] != "v1.3.0" || r.body["majorAvailable"] != "v2.0.0" || r.body["newer"] != true ||
		r.body["editable"] != true || r.body["requested"] != false {
		t.Fatalf("status: %d %s", r.status, r.raw)
	}
	if r := b.do("POST", "/api/v1/update", nil); r.status != 403 || r.body["error"] != "admin_only" {
		t.Fatalf("non-admin: %d %s", r.status, r.raw)
	}
	if _, err := os.Stat(request); err == nil {
		t.Fatal("non-admin left a request")
	}
	if r := a.do("POST", "/api/v1/update", nil); r.status != 204 {
		t.Fatalf("request: %d %s", r.status, r.raw)
	}
	if r := b.do("GET", "/api/v1/update", nil); r.body["requested"] != true || r.body["editable"] != false {
		t.Fatalf("after request: %s", r.raw)
	}
	if calls != 1 {
		t.Fatalf("GitHub asked %d times, want 1 (cached)", calls)
	}
}

func TestUpdateDisabled(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	a := e.loginAs(owner, password)
	if r := a.do("GET", "/api/v1/update", nil); r.body["enabled"] != false || r.body["latest"] != nil {
		t.Fatalf("status: %s", r.raw)
	}
	if r := a.do("POST", "/api/v1/update", nil); r.status != 409 || r.body["error"] != "updates_disabled" {
		t.Fatalf("request: %d %s", r.status, r.raw)
	}
}

// notesd-update picks the release the same way the server reports it.
func TestUpdateScriptSelection(t *testing.T) {
	bash, err := exec.LookPath("bash")
	if err != nil {
		t.Skip("no bash")
	}
	list := filepath.Join(t.TempDir(), "releases.json")
	if err := os.WriteFile(list, []byte(releasesJSON), 0o600); err != nil {
		t.Fatal(err)
	}
	for current, want := range map[string]string{
		"v1.2.3":            "v1.3.0",
		"v1.2.0-4-g51d93ed": "v1.3.0",
		"v0.1.0":            "v0.9.0",
		"v2.0.0":            "v2.0.0",
		"v3.0.0":            "",
		"dev":               "",
	} {
		out, err := exec.Command(bash, filepath.Join("..", "..", "..", "scripts", "notesd-update"),
			"--select-from", list, current).Output()
		if err != nil {
			t.Fatalf("%s: %v", current, err)
		}
		if got := strings.TrimSpace(string(out)); got != want {
			t.Errorf("%s: selected %q, want %q", current, got, want)
		}
	}
}

func TestUpdateSameMajorOnly(t *testing.T) {
	gh := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(releasesJSON))
	}))
	t.Cleanup(gh.Close)
	e := newEnvWith(t, api.Config{Version: "v2.0.0", UpdateRequest: filepath.Join(t.TempDir(), "update-request"), ReleasesURL: gh.URL})
	e.setupOwner()
	r := e.loginAs(owner, password).do("GET", "/api/v1/update", nil)
	if r.body["latest"] != "v2.0.0" || r.body["newer"] != false || r.body["majorAvailable"] != nil {
		t.Fatalf("status: %s", r.raw)
	}
}

// A server a major behind finds its releases past the first page.
func TestUpdateFollowsPages(t *testing.T) {
	calls := 0
	var gh *httptest.Server
	gh = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		switch r.URL.Query().Get("page") {
		case "":
			w.Header().Set("Link", `<`+gh.URL+`/?page=2>; rel="next", <`+gh.URL+`/?page=3>; rel="last"`)
			_, _ = w.Write([]byte(`[{"tag_name": "v2.1.0", "draft": false, "prerelease": false}]`))
		case "2":
			w.Header().Set("Link", `<`+gh.URL+`/?page=3>; rel="next"`)
			_, _ = w.Write([]byte(`[{"tag_name": "v2.0.0", "draft": false, "prerelease": false}]`))
		default:
			_, _ = w.Write([]byte(`[{"tag_name": "v1.4.0", "draft": false, "prerelease": false}]`))
		}
	}))
	t.Cleanup(gh.Close)
	e := newEnvWith(t, api.Config{Version: "v1.2.3", UpdateRequest: filepath.Join(t.TempDir(), "update-request"), ReleasesURL: gh.URL})
	e.setupOwner()
	c := e.loginAs(owner, password)
	r := c.do("GET", "/api/v1/update", nil)
	if r.body["latest"] != "v1.4.0" || r.body["newer"] != true || r.body["majorAvailable"] != "v2.1.0" {
		t.Fatalf("status: %s", r.raw)
	}
	c.do("GET", "/api/v1/update", nil)
	if calls != 3 {
		t.Fatalf("GitHub asked %d times, want 3 (one per page, cached)", calls)
	}
}
