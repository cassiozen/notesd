package userdata_test

import (
	"context"
	"sync"
	"testing"

	"notes/server/internal/notes"
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
	var events []notes.Event
	for range 2 {
		r.OnChange(func(id int64) { mu.Lock(); changed = append(changed, id); mu.Unlock() })
	}
	r.OnNote(func(id int64, ev notes.Event) {
		mu.Lock()
		defer mu.Unlock()
		if id != 7 {
			t.Errorf("user %d", id)
		}
		events = append(events, ev)
	})

	if _, err := d.Notes.Create(ctx, notes.CreateInput{OperationID: "o1", ID: "n1", Title: "T"}); err != nil {
		t.Fatal(err)
	}
	if _, err := d.Spaces.Put(ctx, "s1", "Work", nil); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(events) != 1 || events[0].NoteID != "n1" {
		t.Fatalf("events: %+v", events)
	}
	// Both change listeners, for the note and for the space.
	if len(changed) != 4 {
		t.Fatalf("changed: %v", changed)
	}
}
