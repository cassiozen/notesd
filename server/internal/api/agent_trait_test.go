package api_test

import (
	"slices"
	"testing"
)

func agentTrait(t *testing.T, s *sharedEnv) map[string]any {
	t.Helper()
	traits, _ := spaceByID(t, s.owner, "fam")["traits"].([]any)
	for _, tr := range traits {
		if m := tr.(map[string]any); m["key"] == "agent" {
			return m
		}
	}
	return nil
}

func TestAgentTrait(t *testing.T) {
	s := newSharedEnv(t)
	put := func(enabled bool) {
		t.Helper()
		r := s.owner.do("PUT", "/api/v1/agent/spaces/fam", map[string]any{
			"enabled": enabled, "prompt": "P", "menuLabel": "Tidy", "triggers": map[string]any{"newNoteDone": "T", "menu": "M"},
		})
		if r.status != 200 {
			t.Fatalf("put agent: %d %s", r.status, r.raw)
		}
	}
	put(true)
	tr := agentTrait(t, s)
	if tr == nil || tr["label"] != "Agent" || tr["summary"] != "While you write · when you’re done" ||
		tr["problem"] != "AI isn’t set up" || tr["exposure"] != nil {
		t.Fatalf("without AI: %+v", tr)
	}

	if r := s.owner.do("PUT", "/api/v1/settings/ai", map[string]any{
		"baseUrl": "https://api.example.com/v1", "apiKey": "sk-1234", "model": "m",
	}); r.status != 200 {
		t.Fatalf("ai: %d %s", r.status, r.raw)
	}
	tr = agentTrait(t, s)
	audience, _ := tr["audience"].([]any)
	if tr["problem"] != nil || tr["exposure"] != "Sent to the AI at api.example.com" || !slices.Equal(audience, []any{"ai:api.example.com"}) {
		t.Fatalf("with AI: %+v", tr)
	}

	// Agents run in an account's own spaces: one that's on keeps its space from being shared.
	if r := s.owner.do("POST", "/api/v1/spaces/fam/invites", map[string]any{"userId": s.kidID}); r.status != 409 || r.body["error"] != "space_has_agent" {
		t.Fatalf("share with an agent: %d %s", r.status, r.raw)
	}
	if spaceByID(t, s.owner, "fam")["shared"] != nil {
		t.Fatal("a refused share leaves the space unshared")
	}

	put(false)
	if tr := agentTrait(t, s); tr != nil {
		t.Fatalf("paused: %+v", tr)
	}
	s.share()
	// Its paused agent went with the space's row.
	if r := s.owner.do("GET", "/api/v1/agent/spaces", nil); r.status != 200 || len(r.body["spaces"].([]any)) != 0 {
		t.Fatalf("agents after sharing: %d %s", r.status, r.raw)
	}
	if r := s.owner.do("PUT", "/api/v1/agent/spaces/fam", map[string]any{"enabled": true, "prompt": "P"}); r.status != 404 {
		t.Fatalf("agent in a shared space: %d %s", r.status, r.raw)
	}
}
