package userdata_test

import (
	"context"
	"sync"
	"testing"

	"notes/server/internal/agent"
	"notes/server/internal/notes"
	"notes/server/internal/spaces"
	"notes/server/internal/userdata"
)

func TestHooksAddedAfterOpenHearWrites(t *testing.T) {
	ctx := context.Background()
	r := userdata.NewRegistry(t.TempDir())
	t.Cleanup(func() { _ = r.Close() })
	d, err := r.Open(ctx, 7)
	if err != nil {
		t.Fatal(err)
	}

	var mu sync.Mutex
	var changed []int64
	var scheduled []int64
	for range 2 {
		r.OnChange(func(id int64) { mu.Lock(); changed = append(changed, id); mu.Unlock() })
	}
	r.OnAgentScheduled(func(id int64) { mu.Lock(); scheduled = append(scheduled, id); mu.Unlock() })
	if _, err := d.Agent.Put(ctx, agent.Config{SpaceID: spaces.Default, Enabled: true, Prompt: "P",
		Triggers: map[agent.Trigger]string{agent.NewNoteDone: "T"}}); err != nil {
		t.Fatal(err)
	}

	if _, err := d.Notes.Create(ctx, notes.CreateInput{OperationID: "o1", ID: "n1", Title: "T"}); err != nil {
		t.Fatal(err)
	}
	if _, err := d.Spaces.Put(ctx, "s1", "Work", nil); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	defer mu.Unlock()
	// The new note started a writing session its agent waits on.
	if len(scheduled) != 1 || scheduled[0] != 7 {
		t.Fatalf("scheduled: %v", scheduled)
	}
	// Both change listeners, for the note and for the space.
	if len(changed) != 4 {
		t.Fatalf("changed: %v", changed)
	}
}
