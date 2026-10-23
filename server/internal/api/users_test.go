package api_test

import (
	"context"
	"testing"

	"notes/server/internal/api"
)

const (
	kid         = "Kid"
	kidPassword = "another horse battery"
)

// addKid adds a second, non-admin user, as `notesd add-user` does.
func (e *env) addKid() {
	e.t.Helper()
	if _, err := e.auth.AddUser(context.Background(), kid, kidPassword, false); err != nil {
		e.t.Fatal(err)
	}
}

func (e *env) loginAs(username, pw string) *client {
	e.t.Helper()
	c := e.client()
	c.hc.Jar = nil
	r := c.do("POST", "/api/v1/auth/login", map[string]string{
		"username": username, "password": pw, "client": "device", "deviceName": "Phone of " + username,
	})
	if r.status != 200 {
		e.t.Fatalf("login as %s: %d %s", username, r.status, r.raw)
	}
	c.bearer, _ = r.body["accessToken"].(string)
	return c
}

func TestUsersAreIsolated(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	e.addKid()
	a := e.loginAs(owner, password)
	b := e.loginAs("kid", kidPassword) // usernames ignore case

	if r := a.do("POST", "/api/v1/notes", map[string]any{"operationId": "o1", "id": "n1", "title": "Secret", "content": "#private plans"}); r.status != 201 {
		t.Fatalf("create: %d %s", r.status, r.raw)
	}
	if r := a.do("PUT", "/api/v1/spaces/work", map[string]any{"name": "Work"}); r.status != 200 && r.status != 201 {
		t.Fatalf("create space: %d %s", r.status, r.raw)
	}
	lockPassword := map[string]any{
		"kind": "password", "salt": b64(16, 's'), "iterations": 600000, "wrapped": b64(60, 'w'), "verifier": b64(32, 'v'),
	}
	if r := a.do("PUT", "/api/v1/locks/keys/password", lockPassword); r.status != 200 {
		t.Fatalf("create lock password: %d %s", r.status, r.raw)
	}
	img := sha(pngBytes)
	if r := a.raw("PUT", "/api/v1/images/"+img, "image/png", pngBytes); r.status != 201 {
		t.Fatalf("upload image: %d %s", r.status, r.raw)
	}

	if r := b.do("GET", "/api/v1/notes", nil); r.status != 200 || len(r.body["notes"].([]any)) != 0 {
		t.Fatalf("list: %d %s", r.status, r.raw)
	}
	if r := b.do("GET", "/api/v1/notes/n1", nil); r.status != 404 {
		t.Fatalf("get another user's note: %d %s", r.status, r.raw)
	}
	if r := b.do("PUT", "/api/v1/notes/n1", map[string]any{"operationId": "x", "baseRevision": 1, "title": "x", "content": "y"}); r.status != 404 {
		t.Fatalf("edit another user's note: %d %s", r.status, r.raw)
	}
	if r := b.do("GET", "/api/v1/sync?since=0", nil); r.status != 200 || len(r.body["changes"].([]any)) != 0 {
		t.Fatalf("sync: %d %s", r.status, r.raw)
	}
	if r := b.do("GET", "/api/v1/search?q=secret", nil); r.status != 200 || len(r.body["results"].([]any)) != 0 {
		t.Fatalf("search: %d %s", r.status, r.raw)
	}
	if r := b.do("GET", "/api/v1/filters", nil); r.status != 200 || len(r.body["tags"].([]any)) != 0 {
		t.Fatalf("filters: %d %s", r.status, r.raw)
	}
	if got := spaceNames(t, b); len(got) != 1 || got[0] != "Notes" {
		t.Fatalf("spaces: %q", got)
	}
	if r := b.do("GET", "/api/v1/locks", nil); r.status != 200 || len(r.body["keys"].([]any)) != 0 {
		t.Fatalf("locks: %d %s", r.status, r.raw)
	}
	if r := b.do("GET", "/api/v1/images/"+img, nil); r.status != 404 {
		t.Fatalf("another user's image: %d", r.status)
	}

	// The same note id is free in the other user's database.
	if r := b.do("POST", "/api/v1/notes", map[string]any{"operationId": "o1", "id": "n1", "title": "Mine", "content": ""}); r.status != 201 {
		t.Fatalf("same id for another user: %d %s", r.status, r.raw)
	}
	if r := a.do("GET", "/api/v1/notes/n1", nil); r.status != 200 || r.body["title"] != "Secret" {
		t.Fatalf("owner's note after: %d %s", r.status, r.raw)
	}
}

func TestSessionsAreScopedToTheirUser(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	e.addKid()
	a := e.loginAs(owner, password)
	a2 := e.loginAs(owner, password)
	b := e.loginAs(kid, kidPassword)

	if r := b.do("POST", "/api/v1/auth/login", map[string]string{"username": kid, "password": password, "client": "device"}); r.status != 401 {
		t.Fatalf("kid with the owner's password: %d", r.status)
	}

	r := a.do("GET", "/api/v1/auth/sessions", nil)
	if r.status != 200 || len(r.body["sessions"].([]any)) != 2 {
		t.Fatalf("owner's sessions: %s", r.raw)
	}
	r = b.do("GET", "/api/v1/auth/sessions", nil)
	list := r.body["sessions"].([]any)
	if len(list) != 1 {
		t.Fatalf("kid's sessions: %s", r.raw)
	}
	kidSession := list[0].(map[string]any)["id"].(string)
	if r := a.do("DELETE", "/api/v1/auth/sessions/"+kidSession, nil); r.status != 404 {
		t.Fatalf("revoke another user's session: %d", r.status)
	}

	// Changing a password signs out that user's other sessions only.
	if r := a.do("POST", "/api/v1/auth/password", map[string]string{"currentPassword": password, "newPassword": "a whole new password"}); r.status != 204 {
		t.Fatalf("change password: %d %s", r.status, r.raw)
	}
	if r := a2.do("GET", "/api/v1/auth/session", nil); r.status != 401 {
		t.Fatalf("owner's other session after password change: %d", r.status)
	}
	if r := b.do("GET", "/api/v1/auth/session", nil); r.status != 200 || r.body["username"] != kid || r.body["isAdmin"] != false {
		t.Fatalf("kid's session after owner's password change: %d %s", r.status, r.raw)
	}
	if r := a.do("GET", "/api/v1/auth/session", nil); r.status != 200 || r.body["username"] != owner || r.body["isAdmin"] != true {
		t.Fatalf("owner's session: %d %s", r.status, r.raw)
	}

	// A reset from the command line signs out only that user.
	if err := e.auth.ResetPassword(context.Background(), "KID", "reset by the host"); err != nil {
		t.Fatal(err)
	}
	if r := b.do("GET", "/api/v1/auth/session", nil); r.status != 401 {
		t.Fatalf("kid after reset: %d", r.status)
	}
	if r := a.do("GET", "/api/v1/auth/session", nil); r.status != 200 {
		t.Fatalf("owner after kid's reset: %d", r.status)
	}
	e.loginAs(kid, "reset by the host")
}

func TestSetupStaysClosedWithMoreUsers(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	e.addKid()
	if r := e.client().do("POST", "/api/v1/auth/setup", map[string]string{"username": "intruder", "password": password}); r.status != 409 {
		t.Fatalf("setup after users exist: %d", r.status)
	}
	if _, err := e.auth.AddUser(context.Background(), " kid ", kidPassword, false); err == nil {
		t.Fatal("a username taken ignoring case and spaces must be refused")
	}
}

func TestOnlyAdminsChangeServerSettings(t *testing.T) {
	f := newFakeResend(t)
	e := newEnvWith(t, api.Config{ResendURL: f.srv.URL})
	e.setupOwner()
	e.addKid()
	a := e.loginAs(owner, password)
	b := e.loginAs(kid, kidPassword)

	settings := map[string]any{"apiKey": "re_123456789", "from": "Notes <notes@example.com>"}
	if r := b.do("PUT", "/api/v1/settings/email", settings); r.status != 403 || r.body["error"] != "admin_only" {
		t.Fatalf("non-admin save: %d %s", r.status, r.raw)
	}
	if r := a.do("PUT", "/api/v1/settings/email", settings); r.status != 200 || r.body["editable"] != true {
		t.Fatalf("admin save: %d %s", r.status, r.raw)
	}
	r := b.do("GET", "/api/v1/settings/email", nil)
	if r.status != 200 || r.body["configured"] != true || r.body["editable"] != false || r.body["keyHint"] != "6789" {
		t.Fatalf("non-admin sees the shared settings: %d %s", r.status, r.raw)
	}
	if r := b.do("DELETE", "/api/v1/settings/email", nil); r.status != 403 {
		t.Fatalf("non-admin delete: %d", r.status)
	}

	// Everyone sends with them.
	if r := b.do("POST", "/api/v1/notes", map[string]any{"operationId": "o", "id": "k1", "title": "Hi", "content": "From kid"}); r.status != 201 {
		t.Fatalf("create: %d %s", r.status, r.raw)
	}
	if r := b.do("POST", "/api/v1/notes/k1/email", map[string]any{"to": []string{"grandma@example.com"}, "revision": 1}); r.status != 200 || len(f.sent) != 1 {
		t.Fatalf("non-admin send: %d %s", r.status, r.raw)
	}
}
