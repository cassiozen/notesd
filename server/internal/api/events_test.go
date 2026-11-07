package api_test

import (
	"context"
	"sync"
	"testing"

	"notes/server/internal/notes"
)

func TestWritesCarryOrigin(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c, _ := e.deviceClient()
	var mu sync.Mutex
	var events []notes.Event
	d, err := e.users.Open(context.Background(), 1)
	if err != nil {
		t.Fatal(err)
	}
	d.Notes.OnCommit(func(ev notes.Event) {
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
	if ev := last(); ev.Origin != "initial" {
		t.Fatalf("create: %+v", ev)
	}

	r := c.do("PUT", "/api/v1/notes/n1", map[string]any{
		"operationId": "o2", "baseRevision": 1, "title": "T", "content": "b",
		// Only the server marks an agent's write: a client's mark would keep its writes from firing triggers.
		"agent": map[string]any{"runId": "run_1", "only": true},
	})
	if r.status != 200 {
		t.Fatalf("update: %d %s", r.status, r.raw)
	}
	if ev := last(); ev.Origin != "" || ev.Agent != nil {
		t.Fatalf("update: %+v", ev)
	}

	if r := c.do("DELETE", "/api/v1/notes/n1", map[string]any{"operationId": "o3", "baseRevision": 2}, "X-Notesd-Sync", "someday"); r.status != 200 {
		t.Fatalf("delete: %d %s", r.status, r.raw)
	}
	if ev := last(); ev.Kind != notes.EventDeleted || ev.Origin != "other" {
		t.Fatalf("delete: %+v", ev)
	}
}
