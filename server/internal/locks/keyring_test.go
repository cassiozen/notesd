package locks_test

import (
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"notes/server/internal/database"
	"notes/server/internal/locks"
	"notes/server/migrations"
)

var ctx = context.Background()

func b64(n int, c byte) string {
	return base64.RawURLEncoding.EncodeToString([]byte(strings.Repeat(string(c), n)))
}

func newStore(t *testing.T) *locks.Store {
	t.Helper()
	db, err := database.Open(ctx, filepath.Join(t.TempDir(), "notes.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return locks.NewStore(db)
}

func intp(n int) *int { return &n }

// The server's verdict on each fixture keyring entry: ok is stored, an
// unsupported or invalid KDF refused.
func TestKeyringFixtures(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "shared", "crypto-fixtures", "locked.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f struct {
		Keyring []struct {
			Name, Kind, Verdict string
			Iterations          *int
			KDF                 *locks.KDF
		}
	}
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	if len(f.Keyring) == 0 {
		t.Fatal("no fixtures")
	}
	s := newStore(t)
	verifier := b64(32, 'v')
	if _, err := s.Put(ctx, "password", locks.PutInput{Kind: "password", Salt: b64(16, 's'), Iterations: intp(600000),
		Wrapped: b64(60, 'w'), Verifier: verifier}); err != nil {
		t.Fatal(err)
	}
	for i, c := range f.Keyring {
		id := c.Kind
		in := locks.PutInput{Kind: c.Kind, Salt: b64(16, 's'), Iterations: c.Iterations, KDF: c.KDF, Wrapped: b64(60, 'w'), Verifier: verifier}
		if c.Kind == locks.KindPasskey {
			id, in.CredentialID = fmt.Sprintf("pk%d", i), "Y3JlZA"
		}
		k, err := s.Put(ctx, id, in)
		var ierr *locks.InputError
		switch c.Verdict {
		case "ok":
			want := c.KDF
			if want == nil {
				if c.Kind == locks.KindPasskey {
					want = &locks.KDF{Name: locks.KDFHKDF}
				} else {
					want = &locks.KDF{Name: locks.KDFPBKDF2, Iterations: *c.Iterations}
				}
			}
			if err != nil || k.KDF != *want || k.Iterations != want.Iterations {
				t.Errorf("%s: %+v, %v", c.Name, k, err)
			}
		case "unsupported":
			if !errors.As(err, &ierr) || ierr.Field != "kdf" || ierr.Message != "unsupported key derivation function" {
				t.Errorf("%s: %v", c.Name, err)
			}
		case "invalid":
			if !errors.As(err, &ierr) {
				t.Errorf("%s: %v", c.Name, err)
			}
		default:
			t.Fatalf("%s: verdict %q", c.Name, c.Verdict)
		}
	}
}

func TestKeyringKDF(t *testing.T) {
	s := newStore(t)
	verifier := b64(32, 'v')
	put := func(id string, in locks.PutInput) (locks.Key, error) {
		in.Salt, in.Wrapped, in.Verifier = b64(16, 's'), b64(60, 'w'), verifier
		return s.Put(ctx, id, in)
	}
	pbkdf2 := &locks.KDF{Name: locks.KDFPBKDF2, Iterations: 600000}
	if k, err := put("password", locks.PutInput{Kind: "password", KDF: pbkdf2}); err != nil || k.Iterations != 600000 {
		t.Fatalf("kdf only: %+v, %v", k, err)
	}
	if _, err := put("password", locks.PutInput{Kind: "password", KDF: pbkdf2, Iterations: intp(600000)}); err != nil {
		t.Fatalf("both, agreeing: %v", err)
	}
	var ierr *locks.InputError
	for name, in := range map[string]locks.PutInput{
		"disagreeing iterations": {Kind: "password", KDF: pbkdf2, Iterations: intp(100000)},
		"passkey with pbkdf2":    {Kind: "passkey", CredentialID: "Y3JlZA", KDF: pbkdf2},
		"hkdf with iterations":   {Kind: "passkey", CredentialID: "Y3JlZA", KDF: &locks.KDF{Name: locks.KDFHKDF, Iterations: 5}},
		"too many iterations":    {Kind: "recovery", KDF: &locks.KDF{Name: locks.KDFPBKDF2, Iterations: 10_000_001}},
		"legacy, no iterations":  {Kind: "recovery"},
		"legacy passkey":         {Kind: "passkey", CredentialID: "Y3JlZA", Iterations: intp(1)},
	} {
		id := in.Kind
		if id == locks.KindPasskey {
			id = "pk"
		}
		if _, err := put(id, in); !errors.As(err, &ierr) {
			t.Errorf("%s: %v", name, err)
		}
	}
	if _, err := put("pk", locks.PutInput{Kind: "passkey", CredentialID: "Y3JlZA", Iterations: intp(0), KDF: &locks.KDF{Name: locks.KDFHKDF}}); err != nil {
		t.Fatal(err)
	}
	keys, err := s.List(ctx)
	if err != nil || len(keys) != 2 || keys[0].KDF != *pbkdf2 || keys[1].KDF != (locks.KDF{Name: locks.KDFHKDF}) || keys[1].Iterations != 0 {
		t.Fatalf("list: %+v, %v", keys, err)
	}
}

// A keyring from before KDFs were named gets them from its kinds.
func TestKDFMigration(t *testing.T) {
	path := filepath.Join(t.TempDir(), "notes.db")
	raw, err := sql.Open("sqlite", "file:"+path)
	if err != nil {
		t.Fatal(err)
	}
	for v := 1; v <= 11; v++ {
		names, _ := fs.Glob(migrations.Notes, fmt.Sprintf("%04d_*.sql", v))
		body, _ := fs.ReadFile(migrations.Notes, names[0])
		if _, err := raw.ExecContext(ctx, string(body)); err != nil {
			t.Fatalf("%s: %v", names[0], err)
		}
	}
	if _, err := raw.ExecContext(ctx, `PRAGMA user_version = 11;
		INSERT INTO lock_keys (id, kind, credential_id, salt, iterations, wrapped, created_at) VALUES
		('password', 'password', NULL, 's', 600000, 'w', 1), ('pk', 'passkey', 'c', 's', 0, 'w', 2)`); err != nil {
		t.Fatal(err)
	}
	raw.Close()
	db, err := database.Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var stored string
	if err := db.R.QueryRowContext(ctx, `SELECT kdf FROM lock_keys WHERE id = 'password'`).Scan(&stored); err != nil ||
		stored != `{"name":"pbkdf2-sha256","iterations":600000}` {
		t.Fatalf("backfilled %q, %v", stored, err)
	}
	keys, err := locks.NewStore(db).List(ctx)
	if err != nil || len(keys) != 2 || keys[0].KDF != (locks.KDF{Name: locks.KDFPBKDF2, Iterations: 600000}) ||
		keys[1].KDF != (locks.KDF{Name: locks.KDFHKDF}) {
		t.Fatalf("keys: %+v, %v", keys, err)
	}
}
