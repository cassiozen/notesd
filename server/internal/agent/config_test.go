package agent_test

import (
	"context"
	"errors"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"notes/server/internal/agent"
	"notes/server/internal/database"
	"notes/server/internal/spaces"
)

var ctx = context.Background()

func setup(t *testing.T) (*database.DB, *agent.Store) {
	t.Helper()
	db, err := database.Open(ctx, filepath.Join(t.TempDir(), "notes.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return db, agent.NewStore(db)
}

func TestConfigRoundTrip(t *testing.T) {
	db, s := setup(t)
	if _, err := spaces.NewStore(db).Put(ctx, "journal", "Journal", nil); err != nil {
		t.Fatal(err)
	}

	c, err := s.Config(ctx, "journal")
	if err != nil || c.Enabled || len(c.Triggers) != 0 || c.SpaceID != "journal" {
		t.Fatalf("before setup: %+v %v", c, err)
	}

	c, err = s.Put(ctx, agent.Config{SpaceID: "journal", Enabled: true, Prompt: "  A journal.  ", MenuLabel: "  Fix   typos ",
		Triggers: map[agent.Trigger]string{
			agent.NewNoteDone: "Tag it.", agent.Menu: "Fix typos only.", agent.NoteDeleted: "   ", "fromTheFuture": "x",
		}})
	if err != nil {
		t.Fatal(err)
	}
	if c.Prompt != "A journal." || c.MenuLabel != "Fix typos" || len(c.Triggers) != 2 || c.UpdatedAt == 0 {
		t.Fatalf("stored: %+v", c)
	}
	if !c.Acts(agent.NewNoteDone) || c.Acts(agent.NoteDeleted) {
		t.Fatalf("acts: %+v", c)
	}

	got, err := s.Config(ctx, "journal")
	if err != nil || got.Prompt != c.Prompt || got.Triggers[agent.Menu] != "Fix typos only." || len(got.Triggers) != 2 {
		t.Fatalf("read back: %+v %v", got, err)
	}

	// A full replace drops triggers that aren't sent.
	if _, err := s.Put(ctx, agent.Config{SpaceID: "journal", Prompt: "A journal.", Triggers: map[agent.Trigger]string{agent.EditDone: "x"}}); err != nil {
		t.Fatal(err)
	}
	all, err := s.Configs(ctx)
	if err != nil || len(all) != 1 || all[0].Enabled || len(all[0].Triggers) != 1 || all[0].Triggers[agent.EditDone] != "x" {
		t.Fatalf("after replace: %+v %v", all, err)
	}
	if all[0].Acts(agent.EditDone) {
		t.Fatal("a turned-off agent acts")
	}

	// Deleting the (empty) space takes its agent with it.
	if err := spaces.NewStore(db).Delete(ctx, "journal"); err != nil {
		t.Fatal(err)
	}
	all, err = s.Configs(ctx)
	if err != nil || len(all) != 0 {
		t.Fatalf("after space delete: %+v %v", all, err)
	}
	var n int
	if err := db.R.QueryRow(`SELECT count(*) FROM agent_triggers`).Scan(&n); err != nil || n != 0 {
		t.Fatalf("triggers left: %d %v", n, err)
	}
}

func TestConfigValidation(t *testing.T) {
	_, s := setup(t)
	if _, err := s.Put(ctx, agent.Config{SpaceID: "nope"}); !errors.Is(err, agent.ErrSpaceNotFound) {
		t.Fatalf("unknown space: %v", err)
	}
	if _, err := s.Config(ctx, "nope"); !errors.Is(err, agent.ErrSpaceNotFound) {
		t.Fatalf("unknown space: %v", err)
	}
	if err := s.Delete(ctx, "nope"); !errors.Is(err, agent.ErrSpaceNotFound) {
		t.Fatalf("delete unknown space: %v", err)
	}
	for _, tc := range []struct {
		c     agent.Config
		field string
	}{
		{agent.Config{Prompt: strings.Repeat("x", agent.MaxPromptBytes+1)}, "prompt"},
		{agent.Config{Triggers: map[agent.Trigger]string{agent.EditDone: strings.Repeat("x", agent.MaxPromptBytes+1)}}, "triggers.editDone"},
		{agent.Config{Triggers: map[agent.Trigger]string{agent.Menu: "Fix typos."}}, "menuLabel"},
		{agent.Config{MenuLabel: strings.Repeat("x", 41)}, "menuLabel"},
		{agent.Config{MenuLabel: "Fix\x00"}, "menuLabel"},
		{agent.Config{ReferenceNotes: []string{"a", "b", "c", "d", "e", "f"}}, "referenceNotes"},
		{agent.Config{ReferenceNotes: []string{strings.Repeat("x", 201)}}, "referenceNotes"},
		{agent.Config{ReferenceNotes: []string{"a\x00b"}}, "referenceNotes"},
	} {
		tc.c.SpaceID = spaces.Default
		var ie *agent.InputError
		if _, err := s.Put(ctx, tc.c); !errors.As(err, &ie) || ie.Field != tc.field {
			t.Errorf("want %s error, got %v", tc.field, err)
		}
	}
}

func TestTimeZone(t *testing.T) {
	db, s := setup(t)
	st, err := s.Settings(ctx)
	if err != nil || st.TimeZone != "UTC" || st.TimeZoneSource != agent.TimeZoneDefault {
		t.Fatalf("default: %+v %v", st, err)
	}

	// Nonsense from a device is ignored; the first real zone sticks.
	for _, tz := range []string{"Local", "Mars/Olympus", "Europe/Lisbon", "America/New_York"} {
		if err := s.NoteDeviceTimeZone(ctx, tz); err != nil {
			t.Fatal(err)
		}
	}
	if st, _ = s.Settings(ctx); st.TimeZone != "Europe/Lisbon" || st.TimeZoneSource != agent.TimeZoneAuto {
		t.Fatalf("auto: %+v", st)
	}
	if st.Location().String() != "Europe/Lisbon" {
		t.Fatalf("location: %v", st.Location())
	}

	var ie *agent.InputError
	if _, err := s.SetTimeZone(ctx, "Mars/Olympus"); !errors.As(err, &ie) || ie.Field != "timeZone" {
		t.Fatalf("bad zone: %v", err)
	}
	if st, err = s.SetTimeZone(ctx, "Asia/Tokyo"); err != nil || st.TimeZone != "Asia/Tokyo" || st.TimeZoneSource != agent.TimeZoneUser {
		t.Fatalf("user: %+v %v", st, err)
	}

	// After a restart, a device still doesn't override it.
	s = agent.NewStore(db)
	if err := s.NoteDeviceTimeZone(ctx, "Europe/Paris"); err != nil {
		t.Fatal(err)
	}
	if st, _ = s.Settings(ctx); st.TimeZone != "Asia/Tokyo" {
		t.Fatalf("device overrode the user's choice: %+v", st)
	}
}

func TestReferenceNotes(t *testing.T) {
	_, s := setup(t)
	put := func(refs []string) agent.Config {
		t.Helper()
		c, err := s.Put(ctx, agent.Config{SpaceID: spaces.Default, Enabled: true, ReferenceNotes: refs})
		if err != nil {
			t.Fatal(err)
		}
		return c
	}
	if c := put([]string{" Digest ", "", "Digest", "Reading  list"}); !slices.Equal(c.ReferenceNotes, []string{"Digest", "Reading list"}) {
		t.Fatalf("cleaned: %q", c.ReferenceNotes)
	}
	// A client that doesn't know them keeps them.
	if c := put(nil); !slices.Equal(c.ReferenceNotes, []string{"Digest", "Reading list"}) {
		t.Fatalf("kept: %q", c.ReferenceNotes)
	}
	if c, _ := s.Config(ctx, spaces.Default); !slices.Equal(c.ReferenceNotes, []string{"Digest", "Reading list"}) {
		t.Fatalf("read back: %q", c.ReferenceNotes)
	}
	if c := put([]string{}); len(c.ReferenceNotes) != 0 {
		t.Fatalf("cleared: %q", c.ReferenceNotes)
	}
}
