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

func TestAgentSchedulingHeardAfterOpen(t *testing.T) {
	ctx := context.Background()
	r := userdata.NewRegistry(t.TempDir())
	t.Cleanup(func() { _ = r.Close() })
	d, err := r.Open(ctx, 7)
	if err != nil {
		t.Fatal(err)
	}

	var mu sync.Mutex
	var scheduled []agent.Home
	r.OnAgentScheduled(func(h agent.Home) { mu.Lock(); scheduled = append(scheduled, h); mu.Unlock() })
	if _, err := d.Agent.Put(ctx, agent.Config{SpaceID: spaces.Default, Enabled: true, Prompt: "P",
		Triggers: map[agent.Trigger]string{agent.NewNoteDone: "T"}}); err != nil {
		t.Fatal(err)
	}
	if _, err := d.Notes.Create(ctx, notes.CreateInput{OperationID: "o1", ID: "n1", Title: "T"}); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	defer mu.Unlock()
	// The new note started a writing session its agent waits on.
	if len(scheduled) != 1 || scheduled[0] != agent.UserHome(7) {
		t.Fatalf("scheduled: %v", scheduled)
	}
}

func TestSharedSpacesWakeTheirOwnWorker(t *testing.T) {
	ctx := context.Background()
	r := userdata.NewRegistry(t.TempDir())
	t.Cleanup(func() { _ = r.Close() })
	var mu sync.Mutex
	var scheduled []agent.Home
	r.OnAgentScheduled(func(h agent.Home) { mu.Lock(); scheduled = append(scheduled, h); mu.Unlock() })
	d, err := r.OpenSpace(ctx, "sh")
	if err != nil {
		t.Fatal(err)
	}
	if err := d.Notes.ShareIn(ctx, notes.SpaceRow{ID: "sh", Name: "Shared"}, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := d.Agent.Put(ctx, agent.Config{SpaceID: "sh", Enabled: true, Prompt: "P",
		Triggers: map[agent.Trigger]string{agent.NewNoteDone: "T"}}); err != nil {
		t.Fatal(err)
	}
	if _, err := d.Notes.Create(ctx, notes.CreateInput{OperationID: "o1", ID: "n1", Title: "T", UpdatedBy: 3}); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(scheduled) != 1 || scheduled[0] != agent.SpaceHome("sh") {
		t.Fatalf("scheduled: %v", scheduled)
	}
}
