package api_test

import "testing"

func TestAgentConfigEndpoints(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c, _ := e.deviceClient()

	if r := c.do("GET", "/api/v1/agent/spaces", nil); r.status != 200 || len(r.body["spaces"].([]any)) != 0 {
		t.Fatalf("empty list: %d %s", r.status, r.raw)
	}
	if r := c.do("GET", "/api/v1/agent/spaces/nope", nil); r.status != 404 {
		t.Fatalf("unknown space: %d %s", r.status, r.raw)
	}
	if r := c.do("PUT", "/api/v1/agent/spaces/nope", map[string]any{"enabled": true}); r.status != 404 {
		t.Fatalf("put unknown space: %d %s", r.status, r.raw)
	}
	if r := c.do("DELETE", "/api/v1/agent/spaces/nope", nil); r.status != 404 {
		t.Fatalf("delete unknown space: %d %s", r.status, r.raw)
	}
	if r := c.do("GET", "/api/v1/agent/spaces/default", nil); r.status != 200 || r.body["enabled"] != false {
		t.Fatalf("not set up: %d %s", r.status, r.raw)
	}

	r := c.do("PUT", "/api/v1/agent/spaces/default", map[string]any{
		"enabled": true, "prompt": "Keep notes findable.",
		"triggers": map[string]any{"newNoteDone": "Add tags.", "menu": "Fix typos."},
	})
	if r.status != 400 || r.body["field"] != "menuLabel" {
		t.Fatalf("menu without a label: %d %s", r.status, r.raw)
	}
	r = c.do("PUT", "/api/v1/agent/spaces/default", map[string]any{
		"enabled": true, "prompt": "Keep notes findable.", "menuLabel": "Fix typos",
		"triggers": map[string]any{"newNoteDone": "Add tags.", "menu": "Fix typos."},
	})
	triggers, _ := r.body["triggers"].(map[string]any)
	if r.status != 200 || r.body["spaceId"] != "default" || r.body["enabled"] != true || r.body["prompt"] != "Keep notes findable." ||
		triggers["newNoteDone"] != "Add tags." || triggers["menu"] != "Fix typos." {
		t.Fatalf("put: %d %s", r.status, r.raw)
	}
	r = c.do("GET", "/api/v1/agent/spaces", nil)
	if list := r.body["spaces"].([]any); len(list) != 1 || list[0].(map[string]any)["menuLabel"] != "Fix typos" {
		t.Fatalf("list: %s", r.raw)
	}
	if r := c.do("DELETE", "/api/v1/agent/spaces/default", nil); r.status != 204 {
		t.Fatalf("delete: %d", r.status)
	}
	if r := c.do("GET", "/api/v1/agent/spaces", nil); len(r.body["spaces"].([]any)) != 0 {
		t.Fatalf("after delete: %s", r.raw)
	}
}

func TestAccountTimeZone(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c, _ := e.deviceClient()

	if r := c.do("GET", "/api/v1/settings/account", nil); r.body["timeZone"] != "UTC" || r.body["timeZoneSource"] != "default" {
		t.Fatalf("default: %s", r.raw)
	}
	// Any signed-in request can carry the device's zone; the first one counts.
	c.do("GET", "/api/v1/spaces", nil, "X-Notesd-Time-Zone", "Europe/Lisbon")
	c.do("GET", "/api/v1/spaces", nil, "X-Notesd-Time-Zone", "Asia/Tokyo")
	if r := c.do("GET", "/api/v1/settings/account", nil); r.body["timeZone"] != "Europe/Lisbon" || r.body["timeZoneSource"] != "auto" {
		t.Fatalf("auto: %s", r.raw)
	}
	if r := c.do("PUT", "/api/v1/settings/account", map[string]any{"timeZone": "nowhere"}); r.status != 400 || r.body["field"] != "timeZone" {
		t.Fatalf("bad zone: %d %s", r.status, r.raw)
	}
	if r := c.do("PUT", "/api/v1/settings/account", map[string]any{"timeZone": "America/Sao_Paulo"}); r.status != 200 ||
		r.body["timeZone"] != "America/Sao_Paulo" || r.body["timeZoneSource"] != "user" {
		t.Fatalf("set: %d %s", r.status, r.raw)
	}
}
