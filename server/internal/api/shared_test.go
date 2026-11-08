package api_test

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"net/http"
	"strings"
	"testing"
)

// sharedEnv has the owner and Kid signed in, and the owner's "Family" space
// holding a note that shows an image.
type sharedEnv struct {
	*env
	owner, kid     *client
	ownerID, kidID int64
	image          string
}

func newSharedEnv(t *testing.T) *sharedEnv {
	t.Helper()
	e := newEnv(t)
	e.setupOwner()
	e.addKid()
	s := &sharedEnv{env: e, owner: e.loginAs(owner, password), kid: e.loginAs(kid, kidPassword)}
	s.ownerID = e.userID(s.owner, owner)
	s.kidID = e.userID(s.owner, kid)
	if r := s.owner.do("PUT", "/api/v1/spaces/fam", map[string]any{"name": "Family", "icon": "dragon"}); r.status != 200 {
		t.Fatalf("create space: %d %s", r.status, r.raw)
	}
	s.image = s.putImage(s.owner, "/api/v1/images/")
	if r := s.owner.do("POST", "/api/v1/notes", map[string]any{
		"operationId": "op-1", "id": "groceries", "title": "Groceries", "spaceId": "fam",
		"content": "- Milk\n\n![](notesd:image/" + s.image + "?w=1&h=1)",
	}); r.status != 201 {
		t.Fatalf("create note: %d %s", r.status, r.raw)
	}
	return s
}

// putImage uploads a one-pixel PNG under prefix, returning its id.
func (s *sharedEnv) putImage(c *client, prefix string) string {
	s.t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, 1, 1))
	img.Set(0, 0, color.RGBA{R: 200, A: 255})
	var buf bytes.Buffer
	_ = png.Encode(&buf, img)
	sum := sha256.Sum256(buf.Bytes())
	id := hex.EncodeToString(sum[:])
	req, _ := http.NewRequest("PUT", s.srv.URL+prefix+id, bytes.NewReader(buf.Bytes()))
	req.Header.Set("Content-Type", "image/png")
	req.Header.Set("Authorization", "Bearer "+c.bearer)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		s.t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode >= 300 {
		s.t.Fatalf("upload image: %d", res.StatusCode)
	}
	return id
}

// share invites Kid into Family and has them join.
func (s *sharedEnv) share() {
	s.t.Helper()
	r := s.owner.do("POST", "/api/v1/spaces/fam/invites", map[string]any{"userId": s.kidID})
	if r.status != 201 {
		s.t.Fatalf("invite: %d %s", r.status, r.raw)
	}
	inv := s.kid.do("GET", "/api/v1/invites", nil)
	list, _ := inv.body["invites"].([]any)
	if len(list) != 1 {
		s.t.Fatalf("invites: %s", inv.raw)
	}
	id := list[0].(map[string]any)["id"].(string)
	if r := s.kid.do("POST", "/api/v1/invites/"+id+"/accept", nil); r.status != 200 {
		s.t.Fatalf("accept: %d %s", r.status, r.raw)
	}
}

func spaceByID(t *testing.T, c *client, id string) map[string]any {
	t.Helper()
	r := c.do("GET", "/api/v1/spaces", nil)
	for _, sp := range r.body["spaces"].([]any) {
		if m := sp.(map[string]any); m["id"] == id {
			return m
		}
	}
	return nil
}

func changes(t *testing.T, c *client, path string) []map[string]any {
	t.Helper()
	r := c.do("GET", path, nil)
	if r.status != 200 {
		t.Fatalf("sync %s: %d %s", path, r.status, r.raw)
	}
	var out []map[string]any
	for _, ch := range r.body["changes"].([]any) {
		out = append(out, ch.(map[string]any))
	}
	return out
}

func TestSharingASpace(t *testing.T) {
	s := newSharedEnv(t)

	// An invitation shares the space: its notes move to its own file.
	s.share()
	own := changes(t, s.owner, "/api/v1/sync")
	last := own[len(own)-1]
	if last["id"] != "groceries" || last["movedTo"] != "fam" || last["deletedAt"] == nil {
		t.Fatalf("the owner's feed says the note moved: %v", last)
	}
	for _, c := range []*client{s.owner, s.kid} {
		got := changes(t, c, "/api/v1/spaces/fam/sync")
		if len(got) != 1 || got[0]["title"] != "Groceries" || got[0]["spaceId"] != "fam" || got[0]["revision"] != 1.0 {
			t.Fatalf("the space's feed has the note, as it was: %v", got)
		}
		// Its image came along, and loads at the account's address too.
		for _, path := range []string{"/api/v1/spaces/fam/images/", "/api/v1/images/"} {
			if r := c.do("GET", path+s.image, nil); r.status != 200 {
				t.Fatalf("image at %s: %d", path, r.status)
			}
		}
	}

	// Each sees the space, its members and the trait from their side.
	sp := spaceByID(t, s.kid, "fam")
	shared, _ := sp["shared"].(map[string]any)
	if sp["name"] != "Family" || sp["icon"] != "dragon" || sp["noteCount"] != 1.0 || shared["role"] != "member" {
		t.Fatalf("Kid's view: %v", sp)
	}
	traits := sp["traits"].([]any)
	if len(traits) != 1 || traits[0].(map[string]any)["summary"] != "With owner" ||
		traits[0].(map[string]any)["exposure"] != "Visible to owner" {
		t.Fatalf("Kid's trait: %v", traits)
	}
	if got := spaceByID(t, s.owner, "fam")["traits"].([]any)[0].(map[string]any)["summary"]; got != "With Kid" {
		t.Fatalf("owner's trait: %v", got)
	}

	// Kid edits; the owner sees who did.
	r := s.kid.do("PUT", "/api/v1/spaces/fam/notes/groceries", map[string]any{
		"operationId": "kid-1", "baseRevision": 1, "title": "Groceries", "content": "- Milk\n- Eggs",
	})
	if r.status != 200 {
		t.Fatalf("Kid's edit: %d %s", r.status, r.raw)
	}
	n := s.owner.do("GET", "/api/v1/spaces/fam/notes/groceries", nil)
	if n.body["updatedBy"] != float64(s.kidID) {
		t.Fatalf("updatedBy: %s", n.raw)
	}
	if l := s.owner.do("GET", "/api/v1/spaces/fam/notes", nil); l.body["notes"].([]any)[0].(map[string]any)["updatedBy"] != float64(s.kidID) {
		t.Fatalf("the list says who: %s", l.raw)
	}

	// The owner's old address for it answers that it moved.
	r = s.owner.do("PUT", "/api/v1/notes/groceries", map[string]any{
		"operationId": "own-1", "baseRevision": 1, "title": "Groceries", "content": "stale",
	})
	if r.status != 409 || r.body["error"] != "note_moved" {
		t.Fatalf("edit at the old address: %d %s", r.status, r.raw)
	}
	if r := s.owner.do("DELETE", "/api/v1/notes/groceries", map[string]any{"operationId": "own-d", "baseRevision": 2}); r.status != 409 || r.body["error"] != "note_moved" {
		t.Fatalf("delete at the old address: %d %s", r.status, r.raw)
	}
	// A note filed into the space at the account's address is sent where it belongs.
	r = s.owner.do("POST", "/api/v1/notes", map[string]any{"operationId": "own-2", "id": "late", "title": "Late", "content": "", "spaceId": "fam"})
	if r.status != 409 || r.body["error"] != "space_elsewhere" || r.body["spaceId"] != "fam" {
		t.Fatalf("create at the account's address: %d %s", r.status, r.raw)
	}
}

func TestSharedSpaceIsMembersOnly(t *testing.T) {
	s := newSharedEnv(t)
	if r := s.owner.do("POST", "/api/v1/spaces/fam/invites", map[string]any{"userId": s.kidID}); r.status != 201 {
		t.Fatalf("invite: %d %s", r.status, r.raw)
	}
	// Invited isn't joined.
	for _, path := range []string{"/api/v1/spaces/fam/sync", "/api/v1/spaces/fam/notes", "/api/v1/spaces/fam/notes/groceries"} {
		if r := s.kid.do("GET", path, nil); r.status != 404 {
			t.Fatalf("%s before joining: %d %s", path, r.status, r.raw)
		}
	}
	// A space of their own with its id is refused, so sharing that can never touch this one.
	if r := s.kid.do("PUT", "/api/v1/spaces/fam", map[string]any{"name": "Mine"}); r.status != 409 || r.body["error"] != "space_exists" {
		t.Fatalf("PUT by a non-member: %d %s", r.status, r.raw)
	}
}

func TestOnlyTheOwnerManages(t *testing.T) {
	s := newSharedEnv(t)
	s.share()
	for _, c := range []struct {
		method, path string
		body         any
	}{
		{"PUT", "/api/v1/spaces/fam", map[string]any{"name": "Ours"}},
		{"DELETE", "/api/v1/spaces/fam", nil},
		{"POST", "/api/v1/spaces/fam/invites", map[string]any{"userId": s.ownerID}},
		{"PUT", "/api/v1/spaces/fam/owner", map[string]any{"userId": s.kidID}},
		{"DELETE", fmt.Sprintf("/api/v1/spaces/fam/members/%d", s.ownerID), nil},
	} {
		if r := s.kid.do(c.method, c.path, c.body); r.status != 403 || r.body["error"] != "not_space_owner" {
			t.Fatalf("member %s %s: %d %s", c.method, c.path, r.status, r.raw)
		}
	}
	// The owner can't leave; handing on first lets them.
	if r := s.owner.do("DELETE", fmt.Sprintf("/api/v1/spaces/fam/members/%d", s.ownerID), nil); r.status != 409 || r.body["error"] != "owner_cannot_leave" {
		t.Fatalf("owner leaving: %d %s", r.status, r.raw)
	}
	if r := s.owner.do("PUT", "/api/v1/spaces/fam/owner", map[string]any{"userId": s.kidID}); r.status != 204 {
		t.Fatalf("hand on: %d %s", r.status, r.raw)
	}
	if r := s.owner.do("DELETE", fmt.Sprintf("/api/v1/spaces/fam/members/%d", s.ownerID), nil); r.status != 204 {
		t.Fatalf("former owner leaves: %d %s", r.status, r.raw)
	}
	if spaceByID(t, s.owner, "fam") != nil {
		t.Fatal("the space left their list")
	}
	if r := s.owner.do("GET", "/api/v1/spaces/fam/sync", nil); r.status != 404 {
		t.Fatalf("their access: %d", r.status)
	}
	// Alone in it, the trait is off: it's private again.
	if traits := spaceByID(t, s.kid, "fam")["traits"].([]any); len(traits) != 0 {
		t.Fatalf("traits once alone: %v", traits)
	}
}

func TestMovingNotesBetweenFiles(t *testing.T) {
	s := newSharedEnv(t)
	s.share()
	// Kid moves the note into their own space.
	r := s.kid.do("POST", "/api/v1/spaces/fam/notes/groceries/move", map[string]any{
		"operationId": "mv-1", "baseRevision": 1, "spaceId": "default",
	})
	if r.status != 200 || r.body["spaceId"] != "default" {
		t.Fatalf("move out: %d %s", r.status, r.raw)
	}
	// A retry answers the same.
	if again := s.kid.do("POST", "/api/v1/spaces/fam/notes/groceries/move", map[string]any{
		"operationId": "mv-1", "baseRevision": 1, "spaceId": "default",
	}); again.status != 200 || again.header.Get("Idempotent-Replayed") != "true" {
		t.Fatalf("retry: %d %s", again.status, again.raw)
	}
	mine := changes(t, s.kid, "/api/v1/sync")
	if len(mine) != 1 || mine[0]["title"] != "Groceries" || mine[0]["spaceId"] != "default" {
		t.Fatalf("Kid's own feed: %v", mine)
	}
	if r := s.kid.do("GET", "/api/v1/images/"+s.image, nil); r.status != 200 {
		t.Fatalf("its image came along: %d", r.status)
	}
	space := changes(t, s.owner, "/api/v1/spaces/fam/sync")
	if last := space[len(space)-1]; last["movedTo"] != "default" {
		t.Fatalf("the space's feed: %v", last)
	}
	// And back: the space's tombstone of it comes alive at a higher revision.
	r = s.kid.do("POST", "/api/v1/notes/groceries/move", map[string]any{
		"operationId": "mv-2", "baseRevision": 1, "spaceId": "fam",
	})
	if r.status != 200 || r.body["revision"] != 3.0 {
		t.Fatalf("move back: %d %s", r.status, r.raw)
	}
	// Moving within a file is an update, not a move.
	s.owner.do("PUT", "/api/v1/spaces/work", map[string]any{"name": "Work"})
	if r := s.owner.do("POST", "/api/v1/notes/x/move", map[string]any{"operationId": "mv-3", "baseRevision": 1, "spaceId": "work"}); r.status != 400 {
		t.Fatalf("move in one file: %d %s", r.status, r.raw)
	}
}

func TestSharingRefusals(t *testing.T) {
	s := newSharedEnv(t)
	if r := s.owner.do("POST", "/api/v1/spaces/default/invites", map[string]any{"userId": s.kidID}); r.status != 409 || r.body["error"] != "space_default" {
		t.Fatalf("share the default space: %d %s", r.status, r.raw)
	}
	locked := "notesd:locked:v1:" + strings.Repeat("A", 40)
	s.owner.do("POST", "/api/v1/notes", map[string]any{"operationId": "op-l", "id": "secret", "title": "Secret", "content": locked, "spaceId": "fam"})
	r := s.owner.do("POST", "/api/v1/spaces/fam/invites", map[string]any{"userId": s.kidID})
	if r.status != 409 || r.body["error"] != "space_has_locked" || r.body["count"] != 1.0 {
		t.Fatalf("share with a locked note: %d %s", r.status, r.raw)
	}
	// Nothing moved: the note is still live in the owner's file.
	if spaceByID(t, s.owner, "fam")["shared"] != nil {
		t.Fatal("a refused share leaves the space unshared")
	}
	if r := s.owner.do("GET", "/api/v1/notes/groceries", nil); r.status != 200 || r.body["spaceId"] != "fam" {
		t.Fatalf("a refused share leaves the notes where they were: %d %s", r.status, r.raw)
	}
	s.owner.do("DELETE", "/api/v1/notes/secret", map[string]any{"operationId": "op-d", "baseRevision": 1})
	s.share()
	// No locked notes in it afterwards either.
	r = s.kid.do("POST", "/api/v1/spaces/fam/notes", map[string]any{"operationId": "k-l", "id": "k", "title": "K", "content": locked})
	if r.status != 422 || r.body["error"] != "locked_in_shared_space" {
		t.Fatalf("locked note in a shared space: %d %s", r.status, r.raw)
	}
	if r := s.owner.do("POST", "/api/v1/spaces/fam/invites", map[string]any{"userId": s.kidID}); r.status != 409 || r.body["error"] != "already_member" {
		t.Fatalf("invite a member: %d %s", r.status, r.raw)
	}
}

func TestSearchCountsSharedSpaces(t *testing.T) {
	s := newSharedEnv(t)
	s.share()
	s.kid.do("POST", "/api/v1/notes", map[string]any{"operationId": "k-1", "id": "mine", "title": "Milk run", "content": ""})
	r := s.kid.do("GET", "/api/v1/search?q=milk&space=default", nil)
	if r.body["otherSpaces"] != 1.0 {
		t.Fatalf("other spaces from the account's file: %s", r.raw)
	}
	r = s.kid.do("GET", "/api/v1/spaces/fam/search?q=milk&space=fam", nil)
	if len(r.body["results"].([]any)) != 1 || r.body["otherSpaces"] != 1.0 {
		t.Fatalf("from the space's file: %s", r.raw)
	}
	r = s.kid.do("GET", "/api/v1/search?q=milk&exceptSpace=default", nil)
	if res := r.body["results"].([]any); len(res) != 1 || res[0].(map[string]any)["spaceId"] != "fam" {
		t.Fatalf("the other spaces' matches: %s", r.raw)
	}
}

func TestRemovingAnOwnerHandsTheSpaceOn(t *testing.T) {
	s := newSharedEnv(t)
	s.share()
	// A third member, who joined after the owner: the earliest-joined rule must pass them over.
	if _, err := s.auth.AddUser(context.Background(), "Cy", kidPassword, false); err != nil {
		t.Fatal(err)
	}
	cy := s.loginAs("Cy", kidPassword)
	r := s.owner.do("POST", "/api/v1/spaces/fam/invites", map[string]any{"userId": s.userID(s.owner, "Cy")})
	if j := cy.do("POST", "/api/v1/invites/"+r.body["id"].(string)+"/accept", nil); j.status != 200 {
		t.Fatalf("Cy joins: %d %s", j.status, j.raw)
	}
	// Only an admin removes accounts, and not their own: so Kid is made the owner, and the admin removes Kid.
	s.owner.do("PUT", "/api/v1/spaces/fam/owner", map[string]any{"userId": s.kidID})
	if r := s.owner.do("DELETE", fmt.Sprintf("/api/v1/users/%d", s.kidID), nil); r.status != 204 {
		t.Fatalf("remove Kid: %d %s", r.status, r.raw)
	}
	sp := spaceByID(t, s.owner, "fam")
	if sp == nil || sp["shared"].(map[string]any)["role"] != "owner" {
		t.Fatalf("the space went to the member who joined first, the admin, not Cy: %v", sp)
	}
}

func TestDeletingASharedSpace(t *testing.T) {
	s := newSharedEnv(t)
	s.share()
	if r := s.owner.do("DELETE", "/api/v1/spaces/fam", nil); r.status != 409 || r.body["error"] != "space_not_empty" {
		t.Fatalf("delete with a note in it: %d %s", r.status, r.raw)
	}
	s.owner.do("DELETE", "/api/v1/spaces/fam/notes/groceries", map[string]any{"operationId": "d-1", "baseRevision": 1})
	if r := s.owner.do("DELETE", "/api/v1/spaces/fam", nil); r.status != 204 {
		t.Fatalf("delete: %d %s", r.status, r.raw)
	}
	if r := s.kid.do("GET", "/api/v1/spaces/fam/sync", nil); r.status != 404 {
		t.Fatalf("the member's access: %d", r.status)
	}
	if spaceByID(t, s.kid, "fam") != nil {
		t.Fatal("gone from the member's list")
	}
}

func TestDecliningAndCancelling(t *testing.T) {
	s := newSharedEnv(t)
	r := s.owner.do("POST", "/api/v1/spaces/fam/invites", map[string]any{"userId": s.kidID})
	id := r.body["id"].(string)
	if traits := spaceByID(t, s.owner, "fam")["traits"].([]any); len(traits) != 1 || traits[0].(map[string]any)["summary"] != "Invited Kid" {
		t.Fatalf("pending invitation's trait: %v", traits)
	}
	if r := s.kid.do("POST", "/api/v1/invites/"+id+"/decline", nil); r.status != 204 {
		t.Fatalf("decline: %d %s", r.status, r.raw)
	}
	if r := s.kid.do("POST", "/api/v1/invites/"+id+"/accept", nil); r.status != 404 {
		t.Fatalf("accept after declining: %d %s", r.status, r.raw)
	}
	r = s.owner.do("POST", "/api/v1/spaces/fam/invites", map[string]any{"userId": s.kidID})
	if c := s.owner.do("DELETE", "/api/v1/spaces/fam/invites/"+r.body["id"].(string), nil); c.status != 204 {
		t.Fatalf("cancel: %d %s", c.status, c.raw)
	}
	if inv := s.kid.do("GET", "/api/v1/invites", nil); len(inv.body["invites"].([]any)) != 0 {
		t.Fatalf("cancelled invitation: %s", inv.raw)
	}
	people := s.kid.do("GET", "/api/v1/people", nil)
	if list := people.body["people"].([]any); len(list) != 2 {
		t.Fatalf("people: %s", people.raw)
	}
}

func TestSessionNamesTheAccount(t *testing.T) {
	s := newSharedEnv(t)
	if r := s.kid.do("GET", "/api/v1/auth/session", nil); r.body["userId"] != float64(s.kidID) {
		t.Fatalf("session: %s", r.raw)
	}
}
