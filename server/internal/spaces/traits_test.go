package spaces_test

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"testing"

	"notes/server/internal/database"
	"notes/server/internal/spaces"
)

func open(t *testing.T) *spaces.Store {
	t.Helper()
	db, err := database.Open(context.Background(), filepath.Join(t.TempDir(), "notes.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return spaces.NewStore(db)
}

func keys(sp spaces.Space) []string {
	out := []string{}
	for _, t := range sp.Traits {
		out = append(out, t.Key)
	}
	return out
}

func TestNoTraitsIsAnEmptyList(t *testing.T) {
	s := open(t)
	list, err := s.List(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	// Clients read a missing list as an old server; this one always sends one.
	b, _ := json.Marshal(list[0])
	var raw map[string]any
	_ = json.Unmarshal(b, &raw)
	if got, ok := raw["traits"].([]any); !ok || len(got) != 0 {
		t.Fatalf("traits: %s", b)
	}
}

func TestTraitsInFixedOrder(t *testing.T) {
	ctx := context.Background()
	s := open(t)
	if _, err := s.Put(ctx, "work", "Work", nil); err != nil {
		t.Fatal(err)
	}
	// Added out of order: shown in TraitOrder regardless.
	s.AddTrait("agent", func(context.Context, []string) (map[string]spaces.Trait, error) {
		return map[string]spaces.Trait{"work": {Label: "Agent", Summary: "On new notes"}}, nil
	})
	s.AddTrait("shared", func(context.Context, []string) (map[string]spaces.Trait, error) {
		return map[string]spaces.Trait{"work": {Label: "Shared", Summary: "With Ana", Exposure: "Visible to Ana"}}, nil
	})

	list, err := s.List(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if got := keys(list[0]); len(got) != 0 {
		t.Fatalf("default space: %v", got)
	}
	work := list[1]
	if got := keys(work); len(got) != 2 || got[0] != "shared" || got[1] != "agent" {
		t.Fatalf("order: %v", got)
	}
	if work.Traits[0].Exposure != "Visible to Ana" || work.Traits[1].Exposure != "" {
		t.Fatalf("exposure: %+v", work.Traits)
	}

	// A write answers with the traits too, so a rename doesn't drop them from the client's row.
	sp, err := s.Put(ctx, "work", "Work 2", nil)
	if err != nil || len(sp.Traits) != 2 {
		t.Fatalf("put: %+v %v", sp, err)
	}
}

func TestTraitSourceErrorFailsTheList(t *testing.T) {
	s := open(t)
	boom := errors.New("boom")
	s.AddTrait("shared", func(context.Context, []string) (map[string]spaces.Trait, error) { return nil, boom })
	if _, err := s.List(context.Background()); !errors.Is(err, boom) {
		t.Fatalf("err: %v", err)
	}
}

func TestUnknownTraitKeyPanics(t *testing.T) {
	defer func() {
		if recover() == nil {
			t.Fatal("no panic")
		}
	}()
	open(t).AddTrait("glitter", nil)
}

func TestTraitSourceErrorStoresNothing(t *testing.T) {
	ctx := context.Background()
	s := open(t)
	boom := errors.New("boom")
	fail := true
	s.AddTrait("shared", func(context.Context, []string) (map[string]spaces.Trait, error) {
		if fail {
			return nil, boom
		}
		return nil, nil
	})
	// A failed PUT must not have been stored, or the client would retry or park a change that succeeded.
	if _, err := s.Put(ctx, "work", "Work", nil); !errors.Is(err, boom) {
		t.Fatalf("put: %v", err)
	}
	fail = false
	list, err := s.List(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 1 {
		t.Fatalf("stored: %+v", list)
	}
}
