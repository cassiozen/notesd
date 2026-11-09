package publish

import (
	"context"
	"os"
	"path/filepath"
	"testing"

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
