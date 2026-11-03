package api_test

import (
	"sync"
	"testing"

	"notes/server/internal/notes"
)

func TestWritesCarryAgentMarkAndOrigin(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c, _ := e.deviceClient()
	var mu sync.Mutex
	var events []notes.Event
	e.users.OnNote(func(_ int64, ev notes.Event) {
		mu.Lock()
		defer mu.Unlock()
		events = append(events, ev)
	})
	last := func() notes.Event {
		mu.Lock()
		defer mu.Unlock()
		return events[len(events)-1]
	}

	if r := c.do("POST", "/api/v1/notes", map[string]any{"operationId": "o1", "id": "n1", "title": "T", "content": "a"},
		"X-Notesd-Sync", "initial"); r.status != 201 {
		t.Fatalf("create: %d %s", r.status, r.raw)
	}
	if ev := last(); ev.Origin != "initial" || ev.Agent != nil {
		t.Fatalf("create: %+v", ev)
	}

	r := c.do("PUT", "/api/v1/notes/n1", map[string]any{
		"operationId": "o2", "baseRevision": 1, "title": "T", "content": "b", "agent": map[string]any{"runId": "run_1", "only": true},
	})
	if r.status != 200 {
		t.Fatalf("update: %d %s", r.status, r.raw)
	}
	if ev := last(); ev.Origin != "" || ev.Agent == nil || ev.Agent.RunID != "run_1" || !ev.Agent.Only {
		t.Fatalf("update: %+v", ev)
	}

	if r := c.do("DELETE", "/api/v1/notes/n1", map[string]any{"operationId": "o3", "baseRevision": 2}, "X-Notesd-Sync", "someday"); r.status != 200 {
		t.Fatalf("delete: %d %s", r.status, r.raw)
	}
	if ev := last(); ev.Kind != notes.EventDeleted || ev.Origin != "other" {
		t.Fatalf("delete: %+v", ev)
	}

	if r := c.do("PUT", "/api/v1/notes/n2", map[string]any{
		"operationId": "o4", "baseRevision": 1, "title": "T", "content": "b", "agent": map[string]any{"runId": "no spaces allowed"},
	}); r.status != 400 || r.body["field"] != "agent.runId" {
		t.Fatalf("bad mark: %d %s", r.status, r.raw)
	}
}
