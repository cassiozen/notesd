package api_test

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"

	"notes/server/internal/api"
	"notes/server/internal/auth"
	"notes/server/internal/database"
	"notes/server/internal/userdata"
)

const (
	owner    = "owner"
	password = "correct horse battery"
)

type env struct {
	t     *testing.T
	srv   *httptest.Server
	auth  *auth.Service
	users *userdata.Registry
}

func newEnv(t *testing.T) *env {
	t.Helper()
	return newEnvWith(t, api.Config{})
}

func newEnvWith(t *testing.T, cfg api.Config) *env {
	t.Helper()
	ctx := context.Background()
	dir := t.TempDir()
	system, err := database.OpenSystem(ctx, filepath.Join(dir, "system.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { system.Close() })
	users := userdata.NewRegistry(dir)
	t.Cleanup(func() { users.Close() })
	a := auth.NewService(system)
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	srv := httptest.NewServer(api.New(system, users, a, log, cfg).Handler())
	t.Cleanup(srv.Close)
	return &env{t: t, srv: srv, auth: a, users: users}
}

// userDB is a user's notes database; the owner is user 1.
func (e *env) userDB(id int64) *database.DB {
	e.t.Helper()
	d, err := e.users.Open(context.Background(), id)
	if err != nil {
		e.t.Fatal(err)
	}
	return d.DB
}

// client is an HTTP client with an optional cookie jar, bearer token and
// CSRF token.
type client struct {
	e      *env
	hc     *http.Client
	bearer string
	csrf   string
}

func (e *env) client() *client {
	jar, _ := cookiejar.New(nil)
	return &client{e: e, hc: &http.Client{Jar: jar}}
}

type resp struct {
	status int
	header http.Header
	body   map[string]any
	raw    []byte
}

func (c *client) do(method, path string, body any, hdr ...string) resp {
	c.e.t.Helper()
	var r io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		r = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, c.e.srv.URL+path, r)
	req.Header.Set("Content-Type", "application/json")
	if c.bearer != "" {
		req.Header.Set("Authorization", "Bearer "+c.bearer)
	}
	if c.csrf != "" {
		req.Header.Set("X-CSRF-Token", c.csrf)
	}
	for i := 0; i+1 < len(hdr); i += 2 {
		req.Header.Set(hdr[i], hdr[i+1])
	}
	res, err := c.hc.Do(req)
	if err != nil {
		c.e.t.Fatal(err)
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(res.Body)
	out := resp{status: res.StatusCode, header: res.Header, raw: raw}
	_ = json.Unmarshal(raw, &out.body)
	return out
}

func (e *env) setupOwner() {
	e.t.Helper()
	if r := e.client().do("POST", "/api/v1/auth/setup", map[string]string{
		"username": owner, "password": password,
	}); r.status != 201 {
		e.t.Fatalf("setup: %d %s", r.status, r.raw)
	}
}

func (e *env) webClient() *client {
	e.t.Helper()
	c := e.client()
	r := c.do("POST", "/api/v1/auth/login", map[string]string{"username": owner, "password": password})
	if r.status != 200 {
		e.t.Fatalf("web login: %d %s", r.status, r.raw)
	}
	c.csrf, _ = r.body["csrfToken"].(string)
	if c.csrf == "" || r.body["accessToken"] != nil {
		e.t.Fatalf("web login response: %s", r.raw)
	}
	return c
}

func (e *env) deviceClient() (*client, string) {
	e.t.Helper()
	c := e.client()
	c.hc.Jar = nil
	r := c.do("POST", "/api/v1/auth/login", map[string]string{
		"username": owner, "password": password, "client": "device", "deviceName": "Test Phone",
	})
	if r.status != 200 {
		e.t.Fatalf("device login: %d %s", r.status, r.raw)
	}
	c.bearer, _ = r.body["accessToken"].(string)
	refresh, _ := r.body["refreshToken"].(string)
	if c.bearer == "" || refresh == "" || r.header.Get("Set-Cookie") != "" {
		e.t.Fatalf("device login response: %s", r.raw)
	}
	return c, refresh
}

func TestFirstRunSetup(t *testing.T) {
	e := newEnv(t)
	c := e.client()
	if r := c.do("GET", "/api/v1/auth/status", nil); r.body["setupRequired"] != true {
		t.Fatalf("status before setup: %s", r.raw)
	}
	if r := c.do("POST", "/api/v1/auth/setup", map[string]string{
		"username": owner, "password": "short",
	}); r.status != 400 {
		t.Fatalf("setup with short password: %d", r.status)
	}
	e.setupOwner()
	if r := c.do("GET", "/api/v1/auth/status", nil); r.body["setupRequired"] != false {
		t.Fatalf("status after setup: %s", r.raw)
	}
	if r := c.do("POST", "/api/v1/auth/setup", map[string]string{
		"username": "intruder", "password": password,
	}); r.status != 409 {
		t.Fatalf("second setup: %d", r.status)
	}
	// Refused before validation (and the password hash), not after.
	if r := c.do("POST", "/api/v1/auth/setup", map[string]string{
		"username": "intruder", "password": "short",
	}); r.status != 409 {
		t.Fatalf("second setup with short password: %d", r.status)
	}
}

func TestAuthRequired(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c := e.client()
	for _, p := range []string{"/api/v1/notes", "/api/v1/sync", "/api/v1/search?q=x", "/api/v1/auth/session"} {
		if r := c.do("GET", p, nil); r.status != 401 || r.body["error"] != "unauthenticated" {
			t.Errorf("GET %s: %d %s", p, r.status, r.raw)
		}
	}
	c.bearer = "garbage"
	if r := c.do("GET", "/api/v1/notes", nil); r.status != 401 {
		t.Errorf("bad bearer: %d", r.status)
	}
	if r := c.do("POST", "/api/v1/auth/login", map[string]string{"username": owner, "password": "nope nope"}); r.status != 401 {
		t.Errorf("bad password: %d", r.status)
	}
}

func TestWebSessionCSRF(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c := e.webClient()

	note := map[string]any{"operationId": "op1", "id": "n1", "title": "Hello", "content": "World"}

	csrf := c.csrf
	c.csrf = ""
	if r := c.do("POST", "/api/v1/notes", note); r.status != 403 || r.body["error"] != "csrf_failed" {
		t.Fatalf("mutation without CSRF token: %d %s", r.status, r.raw)
	}
	c.csrf = csrf
	if r := c.do("POST", "/api/v1/notes", note, "Origin", "https://evil.example"); r.status != 403 {
		t.Fatalf("cross-origin mutation: %d %s", r.status, r.raw)
	}
	if r := c.do("POST", "/api/v1/notes", note, "Origin", e.srv.URL); r.status != 201 {
		t.Fatalf("same-origin mutation: %d %s", r.status, r.raw)
	}
	// Reads need no CSRF token.
	c.csrf = ""
	if r := c.do("GET", "/api/v1/notes/n1", nil); r.status != 200 || r.body["title"] != "Hello" {
		t.Fatalf("read: %d %s", r.status, r.raw)
	}
	// The session endpoint hands the token back after a page reload.
	r := c.do("GET", "/api/v1/auth/session", nil)
	if r.status != 200 || r.body["csrfToken"] != csrf || r.body["username"] != owner {
		t.Fatalf("session: %s", r.raw)
	}
	c.csrf = csrf
	if r := c.do("POST", "/api/v1/auth/logout", nil); r.status != 204 {
		t.Fatalf("logout: %d", r.status)
	}
	if r := c.do("GET", "/api/v1/notes", nil); r.status != 401 {
		t.Fatalf("after logout: %d", r.status)
	}
}

func TestDeviceTokensAndRefresh(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c, refresh := e.deviceClient()

	// Bearer clients need no CSRF token.
	if r := c.do("POST", "/api/v1/notes", map[string]any{"operationId": "op1", "id": "n1", "title": "T"}); r.status != 201 {
		t.Fatalf("create: %d %s", r.status, r.raw)
	}
	// A device token is not accepted as a web cookie and vice versa.
	web := e.webClient()
	web.csrf = ""
	web.bearer = web.hc.Jar.Cookies(mustURL(e.srv.URL))[0].Value
	web.hc.Jar = nil
	if r := web.do("GET", "/api/v1/notes", nil); r.status != 401 {
		t.Fatalf("web session token used as bearer: %d", r.status)
	}

	anon := e.client()
	r := anon.do("POST", "/api/v1/auth/refresh", map[string]string{"refreshToken": refresh})
	if r.status != 200 || r.body["refreshToken"] != refresh {
		t.Fatalf("refresh: %d %s", r.status, r.raw)
	}
	newAccess := r.body["accessToken"].(string)
	// A lost refresh response is harmless: refreshing again works.
	if r := anon.do("POST", "/api/v1/auth/refresh", map[string]string{"refreshToken": refresh}); r.status != 200 {
		t.Fatalf("second refresh: %d", r.status)
	}
	if r := anon.do("POST", "/api/v1/auth/refresh", map[string]string{"refreshToken": "nope"}); r.status != 401 {
		t.Fatalf("bad refresh: %d", r.status)
	}
	_ = newAccess

	// Revoking the session from the browser signs the device out.
	web = e.webClient()
	r = web.do("GET", "/api/v1/auth/sessions", nil)
	sessions := r.body["sessions"].([]any)
	var deviceID string
	for _, s := range sessions {
		m := s.(map[string]any)
		if m["kind"] == "device" && m["name"] == "Test Phone" {
			deviceID = m["id"].(string)
		}
	}
	if deviceID == "" {
		t.Fatalf("device session not listed: %s", r.raw)
	}
	if r := web.do("DELETE", "/api/v1/auth/sessions/"+deviceID, nil); r.status != 204 {
		t.Fatalf("revoke: %d %s", r.status, r.raw)
	}
	if r := anon.do("POST", "/api/v1/auth/refresh", map[string]string{"refreshToken": refresh}); r.status != 401 {
		t.Fatalf("refresh after revoke: %d", r.status)
	}
}

func TestChangePasswordRevokesOtherSessions(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	dev, _ := e.deviceClient()
	web := e.webClient()
	if r := web.do("POST", "/api/v1/auth/password", map[string]string{
		"currentPassword": "wrong password", "newPassword": "another long password",
	}); r.status != 401 {
		t.Fatalf("wrong current password: %d", r.status)
	}
	if r := web.do("POST", "/api/v1/auth/password", map[string]string{
		"currentPassword": password, "newPassword": "another long password",
	}); r.status != 204 {
		t.Fatalf("change password: %d %s", r.status, r.raw)
	}
	if r := web.do("GET", "/api/v1/notes", nil); r.status != 200 {
		t.Fatalf("current session after change: %d", r.status)
	}
	if r := dev.do("GET", "/api/v1/notes", nil); r.status != 401 {
		t.Fatalf("other session after change: %d", r.status)
	}
}

func TestLoginRateLimit(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c := e.client()
	var last resp
	for i := 0; i < 11; i++ {
		last = c.do("POST", "/api/v1/auth/login", map[string]string{"username": owner, "password": "wrong guess"})
	}
	if last.status != 429 || last.header.Get("Retry-After") == "" {
		t.Fatalf("after 11 failures: %d", last.status)
	}
	// Even the right password is refused while limited.
	if r := c.do("POST", "/api/v1/auth/login", map[string]string{"username": owner, "password": password}); r.status != 429 {
		t.Fatalf("correct password while limited: %d", r.status)
	}
}

func TestNotesOverHTTP(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c, _ := e.deviceClient()

	r := c.do("POST", "/api/v1/notes", map[string]any{"operationId": "c1", "id": "019a1234", "title": "Shopping", "content": "- Milk"})
	if r.status != 201 || r.body["status"] != "created" || r.body["revision"] != 1.0 {
		t.Fatalf("create: %d %s", r.status, r.raw)
	}

	// §5.1 example.
	put := map[string]any{"operationId": "019b5678", "baseRevision": 5, "title": "Shopping List", "content": "- Milk\n- Bread\n- Eggs"}
	r = c.do("PUT", "/api/v1/notes/019a1234", put)
	if r.status != 409 || r.body["error"] != "revision_conflict" {
		t.Fatalf("stale put: %d %s", r.status, r.raw)
	}
	if cur := r.body["current"].(map[string]any); cur["revision"] != 1.0 || cur["content"] != "- Milk" {
		t.Fatalf("conflict current: %s", r.raw)
	}

	put["baseRevision"] = 1
	r = c.do("PUT", "/api/v1/notes/019a1234", put)
	if r.status != 200 || r.body["status"] != "updated" || r.body["revision"] != 2.0 || r.body["changeSeq"] != 2.0 {
		t.Fatalf("put: %d %s", r.status, r.raw)
	}
	// Retry after a lost response returns the stored answer.
	r = c.do("PUT", "/api/v1/notes/019a1234", put)
	if r.status != 200 || r.header.Get("Idempotent-Replayed") != "true" || r.body["revision"] != 2.0 {
		t.Fatalf("replayed put: %d %s", r.status, r.raw)
	}
	// Same operation ID, different payload.
	put["content"] = "something else"
	if r := c.do("PUT", "/api/v1/notes/019a1234", put); r.status != 422 {
		t.Fatalf("reused op id: %d %s", r.status, r.raw)
	}
	// PUT replaces the whole document; partial bodies are rejected.
	if r := c.do("PUT", "/api/v1/notes/019a1234", map[string]any{"operationId": "p", "baseRevision": 2, "title": "x"}); r.status != 400 {
		t.Fatalf("partial put: %d", r.status)
	}
	if r := c.do("PUT", "/api/v1/notes/missing", map[string]any{"operationId": "m", "baseRevision": 1, "title": "", "content": ""}); r.status != 404 {
		t.Fatalf("put missing: %d", r.status)
	}

	// List and search.
	r = c.do("GET", "/api/v1/notes?limit=10", nil)
	if items := r.body["notes"].([]any); len(items) != 1 || items[0].(map[string]any)["preview"] != "Milk\nBread\nEggs" || r.body["total"] != float64(1) {
		t.Fatalf("list: %s", r.raw)
	}
	r = c.do("GET", "/api/v1/search?q=bre", nil)
	if res := r.body["results"].([]any); len(res) != 1 || !strings.Contains(res[0].(map[string]any)["excerpt"].(string), "<mark>Bread</mark>") {
		t.Fatalf("search: %s", r.raw)
	}

	// Delete.
	r = c.do("DELETE", "/api/v1/notes/019a1234", map[string]any{"operationId": "d1", "baseRevision": 2})
	if r.status != 200 || r.body["status"] != "deleted" || r.body["deletedAt"] == nil {
		t.Fatalf("delete: %d %s", r.status, r.raw)
	}
	if r := c.do("GET", "/api/v1/notes/019a1234", nil); r.status != 410 || r.body["error"] != "note_deleted" {
		t.Fatalf("get deleted: %d %s", r.status, r.raw)
	}
	r = c.do("PUT", "/api/v1/notes/019a1234", map[string]any{"operationId": "late", "baseRevision": 2, "title": "x", "content": "y"})
	if r.status != 409 || r.body["error"] != "note_deleted" {
		t.Fatalf("edit after delete: %d %s", r.status, r.raw)
	}

	// Sync sees the tombstone.
	r = c.do("GET", "/api/v1/sync?since=0", nil)
	changes := r.body["changes"].([]any)
	if r.status != 200 || len(changes) != 1 || changes[0].(map[string]any)["deletedAt"] == nil || r.body["nextCursor"] != 3.0 {
		t.Fatalf("sync: %d %s", r.status, r.raw)
	}
	gen := r.body["generation"].(string)
	if r := c.do("GET", "/api/v1/sync?since=3&generation="+gen, nil); r.status != 200 || len(r.body["changes"].([]any)) != 0 {
		t.Fatalf("sync caught up: %d %s", r.status, r.raw)
	}
	if r := c.do("GET", "/api/v1/sync?since=0&generation=other", nil); r.status != 409 || r.body["error"] != "generation_mismatch" || r.body["generation"] != gen {
		t.Fatalf("generation mismatch: %d %s", r.status, r.raw)
	}
	if r := c.do("GET", "/api/v1/sync?since=-1", nil); r.status != 400 {
		t.Fatalf("negative cursor: %d", r.status)
	}

	// Malformed bodies.
	req, _ := http.NewRequest("POST", e.srv.URL+"/api/v1/notes", strings.NewReader("{not json"))
	req.Header.Set("Authorization", "Bearer "+c.bearer)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 400 {
		t.Fatalf("malformed JSON: %d", res.StatusCode)
	}
}

func TestSyncIsGzipped(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c, _ := e.deviceClient()
	c.do("POST", "/api/v1/notes", map[string]any{"operationId": "c1", "id": "n1", "title": "T", "content": strings.Repeat("compressible ", 500)})

	req, _ := http.NewRequest("GET", e.srv.URL+"/api/v1/sync", nil)
	req.Header.Set("Authorization", "Bearer "+c.bearer)
	req.Header.Set("Accept-Encoding", "gzip")
	res, err := (&http.Transport{DisableCompression: true}).RoundTrip(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if res.Header.Get("Content-Encoding") != "gzip" {
		t.Fatalf("Content-Encoding = %q", res.Header.Get("Content-Encoding"))
	}
	zr, err := gzip.NewReader(res.Body)
	if err != nil {
		t.Fatal(err)
	}
	var page map[string]any
	if err := json.NewDecoder(zr).Decode(&page); err != nil {
		t.Fatal(err)
	}
	if len(page["changes"].([]any)) != 1 {
		t.Fatalf("page: %v", page)
	}
}

func TestHealthAndWeb(t *testing.T) {
	e := newEnv(t)
	c := e.client()
	if r := c.do("GET", "/healthz", nil); r.status != 200 {
		t.Errorf("healthz: %d", r.status)
	}
	if r := c.do("GET", "/readyz", nil); r.status != 200 || r.body["status"] != "ready" {
		t.Errorf("readyz: %d %s", r.status, r.raw)
	}
	// 503 is the "web app not built" placeholder, as on a fresh checkout; internal/web tests both cases.
	for _, p := range []string{"/", "/notes/abc", "/settings"} {
		r := c.do("GET", p, nil)
		if (r.status != 200 && r.status != 503) || !strings.Contains(string(r.raw), "<html") || r.header.Get("Content-Security-Policy") == "" {
			t.Errorf("GET %s: %d", p, r.status)
		}
	}
	if r := c.do("GET", "/missing.js", nil); r.status != 404 {
		t.Errorf("missing asset: %d", r.status)
	}
	if r := c.do("GET", "/api/v1/nope", nil); r.status != 404 || r.body["error"] != "not_found" {
		t.Errorf("unknown API path: %d %s", r.status, r.raw)
	}
	if r := c.do("PATCH", "/api/v1/notes/x", nil); r.status != 405 && r.status != 404 {
		t.Errorf("wrong method: %d", r.status)
	}
}

func mustURL(s string) *url.URL {
	u, err := url.Parse(s)
	if err != nil {
		panic(err)
	}
	return u
}

func TestTagsOverHTTP(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c, _ := e.deviceClient()
	for i, content := range []string{"#home/repairs roof", "#Home garden", "nothing"} {
		id := fmt.Sprintf("n%d", i)
		if r := c.do("POST", "/api/v1/notes", map[string]any{"operationId": "c" + id, "id": id, "title": "", "content": content}); r.status != 201 {
			t.Fatalf("create: %d %s", r.status, r.raw)
		}
	}

	r := c.do("GET", "/api/v1/filters", nil)
	tags := r.body["tags"].([]any)
	if r.status != 200 || len(tags) != 2 {
		t.Fatalf("tags: %d %s", r.status, r.raw)
	}
	if first := tags[0].(map[string]any); first["key"] != "home" || first["name"] != "Home" || first["count"] != 2.0 {
		t.Fatalf("parent tag: %s", r.raw)
	}

	r = c.do("GET", "/api/v1/notes?tag="+url.QueryEscape("#Home/Repairs"), nil)
	if items := r.body["notes"].([]any); r.status != 200 || len(items) != 1 || r.body["total"] != 1.0 {
		t.Fatalf("list by tag: %d %s", r.status, r.raw)
	}
	r = c.do("GET", "/api/v1/notes?untagged=true", nil)
	if items := r.body["notes"].([]any); len(items) != 1 || items[0].(map[string]any)["id"] != "n2" {
		t.Fatalf("untagged: %s", r.raw)
	}
	r = c.do("GET", "/api/v1/search?q=garden&tag=home", nil)
	if res := r.body["results"].([]any); len(res) != 1 {
		t.Fatalf("search by tag: %s", r.raw)
	}
	if r := c.do("GET", "/api/v1/notes?tag=%23", nil); r.status != 400 {
		t.Fatalf("bad tag: %d", r.status)
	}
}
