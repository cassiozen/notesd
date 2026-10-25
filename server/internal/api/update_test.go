package api_test

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"notes/server/internal/api"
)

func TestUpdate(t *testing.T) {
	calls := 0
	gh := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		_, _ = w.Write([]byte(`{"tag_name": "v1.3.0"}`))
	}))
	t.Cleanup(gh.Close)
	request := filepath.Join(t.TempDir(), "update-request")
	e := newEnvWith(t, api.Config{Version: "v1.2.3", UpdateRequest: request, ReleasesURL: gh.URL})
	e.setupOwner()
	e.addKid()
	a := e.loginAs(owner, password)
	b := e.loginAs(kid, kidPassword)

	r := a.do("GET", "/api/v1/update", nil)
	if r.status != 200 || r.body["latest"] != "v1.3.0" || r.body["newer"] != true || r.body["editable"] != true || r.body["requested"] != false {
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
