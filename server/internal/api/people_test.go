package api_test

import (
	"fmt"
	"testing"
	"time"
)

// invite has the owner invite name and returns the new account's id and link token.
func (e *env) invite(admin *client, name string) (int64, string) {
	e.t.Helper()
	r := admin.do("POST", "/api/v1/users", map[string]string{"username": name})
	if r.status != 201 {
		e.t.Fatalf("invite %s: %d %s", name, r.status, r.raw)
	}
	user, _ := r.body["user"].(map[string]any)
	link, _ := r.body["link"].(map[string]any)
	id, _ := user["id"].(float64)
	token, _ := link["token"].(string)
	if id == 0 || token == "" || user["pending"] != true || user["isAdmin"] != false {
		e.t.Fatalf("invite response: %s", r.raw)
	}
	return int64(id), token
}

func usersPath(id int64, rest string) string { return fmt.Sprintf("/api/v1/users/%d%s", id, rest) }

func TestInviteAndJoin(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	admin := e.loginAs(owner, password)
	id, token := e.invite(admin, "Sam")

	if r := admin.do("POST", "/api/v1/users", map[string]string{"username": " sam "}); r.status != 409 || r.body["error"] != "username_taken" {
		t.Fatalf("an invited name is taken: %d %s", r.status, r.raw)
	}
	// Invited: no password, so no way in, and no internal error either.
	if r := e.client().do("POST", "/api/v1/auth/login", map[string]string{"username": "Sam", "password": ""}); r.status != 401 {
		t.Fatalf("invited account login: %d %s", r.status, r.raw)
	}

	anon := e.client()
	r := anon.do("POST", "/api/v1/auth/setup-link", map[string]string{"token": token})
	if r.status != 200 || r.body["username"] != "Sam" || r.body["pending"] != true {
		t.Fatalf("peek: %d %s", r.status, r.raw)
	}
	if r := anon.do("POST", "/api/v1/auth/setup-link/use", map[string]string{"token": token, "password": "short"}); r.status != 400 || r.body["field"] != "password" {
		t.Fatalf("short password: %d %s", r.status, r.raw)
	}
	if r := anon.do("POST", "/api/v1/auth/setup-link/use", map[string]string{"token": token, "password": "sam's own password"}); r.status != 204 {
		t.Fatalf("use: %d %s", r.status, r.raw)
	}
	sam := e.loginAs("sam", "sam's own password")
	if r := sam.do("GET", "/api/v1/notes", nil); r.status != 200 {
		t.Fatalf("sam's notes: %d", r.status)
	}

	// Once only.
	if r := anon.do("POST", "/api/v1/auth/setup-link/use", map[string]string{"token": token, "password": "someone else's"}); r.status != 410 || r.body["error"] != "link_invalid" {
		t.Fatalf("reused link: %d %s", r.status, r.raw)
	}

	r = admin.do("GET", "/api/v1/users", nil)
	list, _ := r.body["users"].([]any)
	if r.status != 200 || len(list) != 2 {
		t.Fatalf("list: %d %s", r.status, r.raw)
	}
	joined, _ := list[1].(map[string]any)
	if joined["id"] != float64(id) || joined["pending"] != false || joined["linkExpiresAt"] != nil {
		t.Fatalf("sam after joining: %v", joined)
	}
}

func TestSetupLinkExpiresAndIsReplaced(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	admin := e.loginAs(owner, password)
	now := time.Now()
	e.auth.SetClock(func() time.Time { return now })
	id, first := e.invite(admin, "sam")

	r := admin.do("POST", usersPath(id, "/link"), nil)
	second, _ := r.body["token"].(string)
	if r.status != 200 || second == "" || second == first {
		t.Fatalf("new link: %d %s", r.status, r.raw)
	}
	if r := e.client().do("POST", "/api/v1/auth/setup-link", map[string]string{"token": first}); r.status != 410 {
		t.Fatalf("replaced link: %d %s", r.status, r.raw)
	}

	now = now.Add(7*24*time.Hour + time.Second)
	if r := e.client().do("POST", "/api/v1/auth/setup-link", map[string]string{"token": second}); r.status != 410 {
		t.Fatalf("expired link: %d %s", r.status, r.raw)
	}
}

func TestResetLinkSignsOutOnlyWhenUsed(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	e.addKid()
	admin := e.loginAs(owner, password)
	kidApp := e.loginAs(kid, kidPassword)
	id := e.userID(admin, kid)

	r := admin.do("POST", usersPath(id, "/link"), nil)
	token, _ := r.body["token"].(string)
	if r.status != 200 {
		t.Fatalf("reset link: %d %s", r.status, r.raw)
	}
	if r := kidApp.do("GET", "/api/v1/auth/session", nil); r.status != 200 {
		t.Fatalf("a link alone must not sign anyone out: %d", r.status)
	}
	e.loginAs(kid, kidPassword) // the old password still works too

	r = e.client().do("POST", "/api/v1/auth/setup-link", map[string]string{"token": token})
	if r.body["pending"] != false {
		t.Fatalf("peek reset link: %s", r.raw)
	}
	if r := e.client().do("POST", "/api/v1/auth/setup-link/use", map[string]string{"token": token, "password": "a brand new one"}); r.status != 204 {
		t.Fatalf("use: %d %s", r.status, r.raw)
	}
	if r := kidApp.do("GET", "/api/v1/auth/session", nil); r.status != 401 {
		t.Fatalf("kid's device after reset: %d", r.status)
	}
	e.loginAs(kid, "a brand new one")
}

func TestPasswordChangeEndsSetupLink(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	e.addKid()
	admin := e.loginAs(owner, password)
	kidWeb := e.client()
	r := kidWeb.do("POST", "/api/v1/auth/login", map[string]string{"username": kid, "password": kidPassword})
	kidWeb.csrf, _ = r.body["csrfToken"].(string)

	r = admin.do("POST", usersPath(e.userID(admin, kid), "/link"), nil)
	token, _ := r.body["token"].(string)
	if r := kidWeb.do("POST", "/api/v1/auth/password", map[string]string{"currentPassword": kidPassword, "newPassword": "remembered it after all"}); r.status != 204 {
		t.Fatalf("change password: %d %s", r.status, r.raw)
	}
	if r := e.client().do("POST", "/api/v1/auth/setup-link", map[string]string{"token": token}); r.status != 410 {
		t.Fatalf("link after the password changed: %d %s", r.status, r.raw)
	}
}

func TestAdminChangesAndRemovals(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	e.addKid()
	admin := e.loginAs(owner, password)
	kidApp := e.loginAs(kid, kidPassword)
	kidID := e.userID(admin, kid)
	ownerID := e.userID(admin, owner)

	for _, c := range []struct{ method, path string }{
		{"PATCH", usersPath(ownerID, "")},
		{"DELETE", usersPath(ownerID, "")},
		{"POST", usersPath(ownerID, "/link")},
	} {
		if r := admin.do(c.method, c.path, map[string]any{"isAdmin": false}); r.status != 409 || r.body["error"] != "cannot_change_self" {
			t.Fatalf("%s %s on self: %d %s", c.method, c.path, r.status, r.raw)
		}
	}

	if r := admin.do("PATCH", usersPath(kidID, ""), map[string]any{}); r.status != 400 {
		t.Fatalf("patch without isAdmin: %d %s", r.status, r.raw)
	}
	if r := admin.do("PATCH", usersPath(kidID, ""), map[string]any{"isAdmin": true}); r.status != 200 || r.body["isAdmin"] != true {
		t.Fatalf("make admin: %d %s", r.status, r.raw)
	}
	if r := kidApp.do("GET", "/api/v1/users", nil); r.status != 200 {
		t.Fatalf("new admin lists users: %d %s", r.status, r.raw)
	}
	// Two admins now, so the kid may demote the owner, but not then be demoted: no admin would be left.
	if r := kidApp.do("PATCH", usersPath(ownerID, ""), map[string]any{"isAdmin": false}); r.status != 200 {
		t.Fatalf("demote owner: %d %s", r.status, r.raw)
	}
	if r := admin.do("GET", "/api/v1/users", nil); r.status != 403 {
		t.Fatalf("demoted owner lists users: %d", r.status)
	}
	if r := kidApp.do("PATCH", usersPath(ownerID, ""), map[string]any{"isAdmin": true}); r.status != 200 {
		t.Fatalf("promote owner back: %d %s", r.status, r.raw)
	}

	// An invited admin can't sign in yet, so doesn't keep the server administered.
	pendingID, _ := e.invite(admin, "sam")
	if r := admin.do("PATCH", usersPath(pendingID, ""), map[string]any{"isAdmin": true}); r.status != 200 {
		t.Fatalf("promote invited: %d %s", r.status, r.raw)
	}
	if r := admin.do("PATCH", usersPath(kidID, ""), map[string]any{"isAdmin": false}); r.status != 200 {
		t.Fatalf("demote kid: %d %s", r.status, r.raw)
	}
	if r := admin.do("DELETE", usersPath(kidID, ""), nil); r.status != 204 {
		t.Fatalf("remove kid: %d %s", r.status, r.raw)
	}
	if r := kidApp.do("GET", "/api/v1/notes", nil); r.status != 401 {
		t.Fatalf("removed kid's device: %d", r.status)
	}
	if r := admin.do("DELETE", usersPath(kidID, ""), nil); r.status != 404 {
		t.Fatalf("remove twice: %d %s", r.status, r.raw)
	}
	if r := admin.do("DELETE", usersPath(pendingID, ""), nil); r.status != 204 {
		t.Fatalf("cancel invite: %d %s", r.status, r.raw)
	}
	if r := admin.do("POST", "/api/v1/users/abc/link", nil); r.status != 404 {
		t.Fatalf("bad id: %d %s", r.status, r.raw)
	}
}

func TestRemovingInvitedEndsTheirLink(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	admin := e.loginAs(owner, password)
	id, token := e.invite(admin, "sam")
	if r := admin.do("DELETE", usersPath(id, ""), nil); r.status != 204 {
		t.Fatalf("remove: %d %s", r.status, r.raw)
	}
	if r := e.client().do("POST", "/api/v1/auth/setup-link/use", map[string]string{"token": token, "password": "too late now"}); r.status != 410 {
		t.Fatalf("link of a removed account: %d %s", r.status, r.raw)
	}
}

func TestOnlyAdminsManagePeople(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	e.addKid()
	admin := e.loginAs(owner, password)
	ownerID := e.userID(admin, owner)
	b := e.loginAs(kid, kidPassword)
	for _, c := range []struct{ method, path string }{
		{"GET", "/api/v1/users"},
		{"POST", "/api/v1/users"},
		{"POST", usersPath(ownerID, "/link")},
		{"PATCH", usersPath(ownerID, "")},
		{"DELETE", usersPath(ownerID, "")},
	} {
		if r := b.do(c.method, c.path, map[string]any{"username": "x", "isAdmin": false}); r.status != 403 || r.body["error"] != "admin_only" {
			t.Fatalf("non-admin %s %s: %d %s", c.method, c.path, r.status, r.raw)
		}
	}
	if r := e.client().do("GET", "/api/v1/users", nil); r.status != 401 {
		t.Fatalf("signed out: %d", r.status)
	}
}

func TestSetupLinkGuessesAreLimited(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c := e.client()
	var r resp
	for range 11 {
		r = c.do("POST", "/api/v1/auth/setup-link", map[string]string{"token": "guess"})
	}
	if r.status != 429 {
		t.Fatalf("after many bad links: %d %s", r.status, r.raw)
	}
}

func (e *env) userID(admin *client, name string) int64 {
	e.t.Helper()
	r := admin.do("GET", "/api/v1/users", nil)
	list, _ := r.body["users"].([]any)
	for _, u := range list {
		m, _ := u.(map[string]any)
		if m["username"] == name {
			id, _ := m["id"].(float64)
			return int64(id)
		}
	}
	e.t.Fatalf("no user %s in %s", name, r.raw)
	return 0
}
