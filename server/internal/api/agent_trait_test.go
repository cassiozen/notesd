package api_test

import (
	"fmt"
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

	// Sharing and agents are independent: the space shares with its agent, which every member sees and changes.
	s.share()
	tr = agentTrait(t, s)
	if tr == nil || tr["summary"] != "While you write · when you’re done" {
		t.Fatalf("after sharing: %+v", tr)
	}
	for _, c := range []*client{s.owner, s.kid} {
		r := c.do("GET", "/api/v1/agent/spaces/fam", nil)
		if r.status != 200 || r.body["enabled"] != true || r.body["menuLabel"] != "Tidy" {
			t.Fatalf("agent in the shared space: %d %s", r.status, r.raw)
		}
	}
	if r := s.kid.do("PUT", "/api/v1/agent/spaces/fam", map[string]any{
		"enabled": false, "prompt": "P", "menuLabel": "Tidy", "triggers": map[string]any{"newNoteDone": "T", "menu": "M"},
	}); r.status != 200 {
		t.Fatalf("a member turns it off: %d %s", r.status, r.raw)
	}
	if tr := agentTrait(t, s); tr != nil {
		t.Fatalf("off: %+v", tr)
	}
	// The owner's own file kept nothing of it.
	if r := s.owner.do("GET", "/api/v1/agent/spaces", nil); r.status != 200 || len(r.body["spaces"].([]any)) != 1 {
		t.Fatalf("agents after sharing: %d %s", r.status, r.raw)
	}
}

func TestAgentRunsInASharedSpace(t *testing.T) {
	s := newSharedEnv(t)
	s.share()
	if r := s.kid.do("PUT", "/api/v1/agent/spaces/fam", map[string]any{
		"enabled": true, "prompt": "P", "triggers": map[string]any{"noteDeleted": "Note it."},
	}); r.status != 200 {
		t.Fatalf("put agent: %d %s", r.status, r.raw)
	}
	n := s.kid.do("GET", "/api/v1/notes/groceries", nil)
	if r := s.kid.do("DELETE", "/api/v1/notes/groceries", map[string]any{"operationId": "k-del", "baseRevision": n.body["revision"]}); r.status != 200 {
		t.Fatalf("delete: %d %s", r.status, r.raw)
	}
	// Queued in the space's file, for the member who deleted it, and in every member's Activity.
	var runID string
	for _, c := range []*client{s.owner, s.kid} {
		r := c.do("GET", "/api/v1/agent/runs", nil)
		runs, _ := r.body["runs"].([]any)
		if r.status != 200 || len(runs) != 1 {
			t.Fatalf("runs: %d %s", r.status, r.raw)
		}
		run := runs[0].(map[string]any)
		if run["spaceId"] != "fam" || run["trigger"] != "noteDeleted" || run["forUserId"] != float64(s.kidID) {
			t.Fatalf("run: %+v", run)
		}
		runID = run["id"].(string)
	}
	if r := s.owner.do("GET", "/api/v1/agent/runs?space=fam", nil); r.status != 200 || len(r.body["runs"].([]any)) != 1 {
		t.Fatalf("runs in fam: %d %s", r.status, r.raw)
	}
	if r := s.owner.do("GET", "/api/v1/agent/runs?space=default", nil); r.status != 200 || len(r.body["runs"].([]any)) != 0 {
		t.Fatalf("runs in the owner's default space: %d %s", r.status, r.raw)
	}
	if r := s.owner.do("GET", "/api/v1/agent/runs/"+runID, nil); r.status != 200 || r.body["id"] != runID {
		t.Fatalf("run detail: %d %s", r.status, r.raw)
	}
	if r := s.owner.do("GET", "/api/v1/agent/usage?space=fam", nil); r.status != 200 || r.body["tokens"] != 0.0 {
		t.Fatalf("usage: %d %s", r.status, r.raw)
	}
	if r := s.owner.do("GET", "/api/v1/agent/usage", nil); r.status != 400 || r.body["field"] != "space" {
		t.Fatalf("usage without a space: %d %s", r.status, r.raw)
	}
}

func TestActivityPagesThroughEveryFile(t *testing.T) {
	s := newSharedEnv(t)
	s.share()
	for _, sp := range []string{"fam", "default"} {
		if r := s.owner.do("PUT", "/api/v1/agent/spaces/"+sp, map[string]any{
			"enabled": true, "prompt": "P", "triggers": map[string]any{"noteDeleted": "Note it."},
		}); r.status != 200 {
			t.Fatalf("put agent: %d %s", r.status, r.raw)
		}
	}
	for i := range 3 {
		for _, sp := range []string{"fam", "default"} {
			id := fmt.Sprintf("%s-%d", sp, i)
			s.owner.do("POST", "/api/v1/notes", map[string]any{"operationId": "c-" + id, "id": id, "title": id, "spaceId": sp})
			if r := s.owner.do("DELETE", "/api/v1/notes/"+id, map[string]any{"operationId": "d-" + id, "baseRevision": 1}); r.status != 200 {
				t.Fatalf("delete %s: %d %s", id, r.status, r.raw)
			}
		}
	}
	seen := map[string]bool{}
	cursor := ""
	for page := 0; ; page++ {
		r := s.owner.do("GET", "/api/v1/agent/runs?limit=4&cursor="+cursor, nil)
		if r.status != 200 || page > 3 {
			t.Fatalf("page %d: %d %s", page, r.status, r.raw)
		}
		for _, run := range r.body["runs"].([]any) {
			id := run.(map[string]any)["id"].(string)
			if seen[id] {
				t.Fatalf("run %s twice", id)
			}
			seen[id] = true
		}
		if r.body["hasMore"] != true {
			break
		}
		cursor = r.body["nextCursor"].(string)
	}
	if len(seen) != 6 {
		t.Fatalf("saw %d runs", len(seen))
	}
}
