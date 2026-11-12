package publish

import (
	"bytes"
	"context"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"notes/server/internal/database"
	"notes/server/internal/userdata"
)

func TestADeletedSpacesAddressGoesWithIt(t *testing.T) {
	ctx := context.Background()
	dir := t.TempDir()
	system, err := database.OpenSystem(ctx, filepath.Join(dir, "system.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { system.Close() })
	files := userdata.NewRegistry(dir)
	t.Cleanup(func() { files.Close() })
	s := New(system, files, nil, Config{DataDir: dir})
	t.Cleanup(func() { s.Close() })

	// A space deleted from its file, and a shared space whose file was deleted before its address was.
	sites := []Site{
		{File: "user:1", SpaceID: "nope", Handle: "deleted", Origin: "https://x", Published: true},
		{File: "space:gone", SpaceID: "gone", Handle: "nofile", Origin: "https://x", Published: true},
	}
	for _, site := range sites {
		if _, err := system.W.ExecContext(ctx, `INSERT INTO published_spaces
			(file, space_id, handle, origin, published, updated_at) VALUES (?, ?, ?, 'https://x', 1, 0)`,
			site.File, site.SpaceID, site.Handle); err != nil {
			t.Fatal(err)
		}
		if err := os.MkdirAll(filepath.Join(s.dir, site.Handle), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	s.reload(ctx)
	for _, site := range sites {
		if err := s.render(ctx, site); err != nil {
			t.Fatalf("%s: %v", site.Handle, err)
		}
		if _, err := os.Stat(filepath.Join(s.dir, site.Handle)); !os.IsNotExist(err) {
			t.Errorf("%s: its files stay: %v", site.Handle, err)
		}
	}
	var n int
	if err := system.R.QueryRowContext(ctx, `SELECT COUNT(*) FROM published_spaces`).Scan(&n); err != nil || n != 0 {
		t.Fatalf("addresses kept: %d %v", n, err)
	}
	if len(s.sites) != 0 {
		t.Fatalf("still served: %v", s.sites)
	}
}

func TestAReloadThatReadBeforeAForgetDoesNotBringTheSiteBack(t *testing.T) {
	ctx := context.Background()
	dir := t.TempDir()
	system, err := database.OpenSystem(ctx, filepath.Join(dir, "system.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { system.Close() })
	files := userdata.NewRegistry(dir)
	t.Cleanup(func() { files.Close() })
	s := New(system, files, nil, Config{DataDir: dir})
	t.Cleanup(func() { s.Close() })
	if _, err := system.W.ExecContext(ctx, `INSERT INTO published_spaces
		(file, space_id, handle, origin, published, updated_at) VALUES ('user:1', 'fam', 'family', 'https://x', 1, 0)`); err != nil {
		t.Fatal(err)
	}
	s.reload(ctx)

	// The publisher's own reload (a change woke it) reads the site, then the space is deleted before it swaps.
	var calls atomic.Int32
	raced := make(chan struct{}) // Forget's reload read and is about to swap: only without the lock
	forgot := make(chan error, 1)
	s.reloaded = func() {
		if calls.Add(1) > 1 {
			close(raced)
			return
		}
		go func() { forgot <- s.Forget(ctx, "user:1", "fam") }()
		for deadline := time.Now().Add(5 * time.Second); ; time.Sleep(5 * time.Millisecond) {
			var n int
			if err := system.R.QueryRowContext(ctx, `SELECT COUNT(*) FROM published_spaces`).Scan(&n); err != nil {
				t.Error(err)
				return
			}
			if n == 0 {
				break
			}
			if time.Now().After(deadline) {
				t.Error("Forget never deleted the address")
				return
			}
		}
		// Its delete is in: only the lock keeps Forget's reload from reading and swapping before this one.
		select {
		case <-raced:
			// Let it swap first, so this stale swap lands last.
			err := <-forgot
			forgot <- err
		case <-time.After(time.Second): // held at the lock
		}
	}
	s.reload(ctx)
	if err := <-forgot; err != nil {
		t.Fatal(err)
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	if _, ok := s.sites["family"]; ok {
		t.Fatal("a deleted space's site is served again")
	}
}

func TestADomainTwoSitesClaimOrTheAppsIsNotServed(t *testing.T) {
	var logged bytes.Buffer
	s := &Service{log: slog.New(slog.NewTextHandler(&logged, nil)), unserved: map[string]bool{}}
	// Saved before either was refused.
	sites := map[string]Site{
		"garden":  {Handle: "garden", Origin: "https://notes.example.com", Domain: "blog.example.com"},
		"recipes": {Handle: "recipes", Origin: "https://notes.example.com", Domain: "blog.example.com"},
		"work":    {Handle: "work", Origin: "https://notes.example.com", Domain: "notes.example.com"},
		"trips":   {Handle: "trips", Origin: "https://notes.example.com:8443", Domain: "trips.example.com"},
	}
	served, unserved := s.servedDomains(sites)
	if len(served) != 1 || served["trips.example.com"] != "trips" {
		t.Fatalf("served: %v", served)
	}
	if !unserved["blog.example.com"] || !unserved["notes.example.com"] || strings.Count(logged.String(), "not served") != 2 {
		t.Fatalf("left out: %v, logged %s", unserved, logged.String())
	}
	s.unserved = unserved
	logged.Reset()
	s.servedDomains(sites)
	if logged.Len() != 0 {
		t.Fatalf("logged again: %s", logged.String())
	}
}
