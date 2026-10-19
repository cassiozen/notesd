package api_test

import (
	"strings"
	"testing"
)

func spaceNames(t *testing.T, c *client) []string {
	t.Helper()
	r := c.do("GET", "/api/v1/spaces", nil)
	if r.status != 200 {
		t.Fatalf("list spaces: %d %s", r.status, r.raw)
	}
	var names []string
	for _, sp := range r.body["spaces"].([]any) {
		names = append(names, sp.(map[string]any)["name"].(string))
	}
	return names
}

func TestSpaces(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c, _ := e.deviceClient()

	if got := spaceNames(t, c); strings.Join(got, ",") != "Notes" {
		t.Fatalf("a new install has the default space: %q", got)
	}

	// Creating is a PUT by an id the client chose; whitespace is tidied.
	r := c.do("PUT", "/api/v1/spaces/work1", map[string]any{"name": "  Work\tstuff "})
	if r.status != 200 || r.body["id"] != "work1" || r.body["name"] != "Work stuff" || r.body["noteCount"] != 0.0 {
		t.Fatalf("create: %d %s", r.status, r.raw)
	}
	// Two devices creating the same name offline: the second gets a number.
	if r := c.do("PUT", "/api/v1/spaces/work2", map[string]any{"name": "WORK STUFF"}); r.status != 200 || r.body["name"] != "WORK STUFF 2" {
		t.Fatalf("clashing name: %d %s", r.status, r.raw)
	}
	// A full-length name that clashes is shortened to fit its number.
	long := strings.Repeat("x", 64)
	c.do("PUT", "/api/v1/spaces/long1", map[string]any{"name": long})
	if r := c.do("PUT", "/api/v1/spaces/long2", map[string]any{"name": long}); r.status != 200 || r.body["name"] != strings.Repeat("x", 62)+" 2" {
		t.Fatalf("long clash: %d %s", r.status, r.raw)
	}
	for _, id := range []string{"long1", "long2"} {
		c.do("DELETE", "/api/v1/spaces/"+id, nil)
	}
	// Retrying the same request doesn't clash with itself.
	if r := c.do("PUT", "/api/v1/spaces/work1", map[string]any{"name": "Work stuff"}); r.body["name"] != "Work stuff" {
		t.Fatalf("retry: %s", r.raw)
	}
	// Renaming, including the default space.
	if r := c.do("PUT", "/api/v1/spaces/work2", map[string]any{"name": "Side project"}); r.body["name"] != "Side project" {
		t.Fatalf("rename: %s", r.raw)
	}
	if r := c.do("PUT", "/api/v1/spaces/default", map[string]any{"name": "Personal"}); r.status != 200 {
		t.Fatalf("rename default: %d %s", r.status, r.raw)
	}
	if got := spaceNames(t, c); strings.Join(got, ",") != "Personal,Side project,Work stuff" {
		t.Fatalf("default first, then by name: %q", got)
	}

	// An icon is set with the name and kept by a PUT without one.
	if r := c.do("PUT", "/api/v1/spaces/work1", map[string]any{"name": "Work stuff", "icon": "fun-duck"}); r.status != 200 || r.body["icon"] != "fun-duck" {
		t.Fatalf("set icon: %d %s", r.status, r.raw)
	}
	if r := c.do("PUT", "/api/v1/spaces/work1", map[string]any{"name": "Work stuff"}); r.body["icon"] != "fun-duck" {
		t.Fatalf("a PUT without an icon keeps it: %s", r.raw)
	}
	if r := c.do("PUT", "/api/v1/spaces/new1", map[string]any{"name": "Fresh"}); r.body["icon"] != "" {
		t.Fatalf("a new space without an icon has none: %s", r.raw)
	}
	if r := c.do("PUT", "/api/v1/spaces/work1", map[string]any{"name": "Work stuff", "icon": "Bad Icon"}); r.status != 400 {
		t.Fatalf("bad icon: %d %s", r.status, r.raw)
	}
	if r := c.do("DELETE", "/api/v1/spaces/new1", nil); r.status != 204 {
		t.Fatalf("delete new1: %d %s", r.status, r.raw)
	}

	for _, bad := range []struct{ id, name string }{
		{"work1", "  "},
		{"work1", strings.Repeat("x", 65)},
		{"work1", "a\x00b"},
		{"bad.id", "Fine"},
	} {
		if r := c.do("PUT", "/api/v1/spaces/"+bad.id, map[string]any{"name": bad.name}); r.status != 400 {
			t.Fatalf("bad %q %q: %d %s", bad.id, bad.name, r.status, r.raw)
		}
	}

	// Only an empty space can be deleted, and never the default.
	if r := c.do("POST", "/api/v1/notes", map[string]any{"operationId": "c1", "id": "n1", "title": "Plan", "content": "", "spaceId": "work1"}); r.status != 201 || r.body["spaceId"] != "work1" {
		t.Fatalf("create in a space: %d %s", r.status, r.raw)
	}
	if r := c.do("DELETE", "/api/v1/spaces/work1", nil); r.status != 409 || r.body["error"] != "space_not_empty" || r.body["count"] != 1.0 {
		t.Fatalf("delete non-empty: %d %s", r.status, r.raw)
	}
	if r := c.do("DELETE", "/api/v1/spaces/default", nil); r.status != 409 || r.body["error"] != "space_default" {
		t.Fatalf("delete default: %d %s", r.status, r.raw)
	}
	if r := c.do("DELETE", "/api/v1/spaces/work2", nil); r.status != 204 {
		t.Fatalf("delete empty: %d %s", r.status, r.raw)
	}
	if r := c.do("DELETE", "/api/v1/spaces/work2", nil); r.status != 204 {
		t.Fatalf("delete again: %d %s", r.status, r.raw)
	}

	// A note filed into a space deleted elsewhere lands in the default.
	r = c.do("PUT", "/api/v1/notes/n1", map[string]any{"operationId": "u1", "baseRevision": 1, "title": "Plan", "content": "", "spaceId": "work2"})
	if r.status != 200 || r.body["status"] != "updated" || r.body["spaceId"] != "default" {
		t.Fatalf("move to a deleted space: %d %s", r.status, r.raw)
	}
	r = c.do("GET", "/api/v1/sync?since=0", nil)
	if ch := r.body["changes"].([]any); len(ch) != 1 || ch[0].(map[string]any)["spaceId"] != "default" {
		t.Fatalf("sync carries the space: %s", r.raw)
	}
}

func TestSpaceFilters(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c, _ := e.deviceClient()
	c.do("PUT", "/api/v1/spaces/work", map[string]any{"name": "Work"})
	for _, n := range []struct{ id, space, content string }{
		{"a", "", "garden #home"},
		{"b", "default", "garden #home/roof"},
		{"c", "work", "garden #clients"},
		{"d", "work", "budget #clients #home"},
	} {
		body := map[string]any{"operationId": "c" + n.id, "id": n.id, "title": "", "content": n.content}
		if n.space != "" {
			body["spaceId"] = n.space
		}
		if r := c.do("POST", "/api/v1/notes", body); r.status != 201 {
			t.Fatalf("create %s: %d %s", n.id, r.status, r.raw)
		}
	}

	r := c.do("GET", "/api/v1/notes?space=work", nil)
	if r.body["total"] != 2.0 || r.body["notes"].([]any)[0].(map[string]any)["spaceId"] != "work" {
		t.Fatalf("list in a space: %s", r.raw)
	}
	if r := c.do("GET", "/api/v1/notes?space=default&tag=home", nil); r.body["total"] != 2.0 {
		t.Fatalf("space and tag: %s", r.raw)
	}
	if r := c.do("GET", "/api/v1/notes?space=work&tag=home", nil); r.body["total"] != 1.0 {
		t.Fatalf("space and tag: %s", r.raw)
	}

	// Each space lists its own tags; the same tag key is shared.
	r = c.do("GET", "/api/v1/filters?space=work", nil)
	var keys []string
	for _, tg := range r.body["tags"].([]any) {
		keys = append(keys, tg.(map[string]any)["key"].(string))
	}
	if strings.Join(keys, ",") != "clients,home" {
		t.Fatalf("tags in work: %s", r.raw)
	}
	if r := c.do("GET", "/api/v1/filters", nil); len(r.body["tags"].([]any)) != 3 {
		t.Fatalf("tags everywhere: %s", r.raw)
	}

	// Search in a space counts the matches elsewhere, and can list them.
	r = c.do("GET", "/api/v1/search?q=garden&space=default", nil)
	if len(r.body["results"].([]any)) != 2 || r.body["otherSpaces"] != 1.0 {
		t.Fatalf("search in a space: %s", r.raw)
	}
	r = c.do("GET", "/api/v1/search?q=garden&exceptSpace=default", nil)
	res := r.body["results"].([]any)
	if len(res) != 1 || res[0].(map[string]any)["spaceId"] != "work" || r.body["otherSpaces"] != nil {
		t.Fatalf("search other spaces: %s", r.raw)
	}
	if r := c.do("GET", "/api/v1/search?q=garden&space=a&exceptSpace=b", nil); r.status != 400 {
		t.Fatalf("both: %d", r.status)
	}
	if r := c.do("GET", "/api/v1/notes?space=no.pe", nil); r.status != 400 {
		t.Fatalf("bad space: %d", r.status)
	}
}
