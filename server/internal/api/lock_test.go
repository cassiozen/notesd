package api_test

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func b64(n int, c byte) string {
	return base64.RawURLEncoding.EncodeToString([]byte(strings.Repeat(string(c), n)))
}

func TestLockKeyring(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c, _ := e.deviceClient()

	verifier := b64(32, 'v')
	password := map[string]any{
		"kind": "password", "salt": b64(16, 's'), "iterations": 600000, "wrapped": b64(60, 'w'), "verifier": verifier,
	}
	if r := c.do("GET", "/api/v1/locks", nil); r.status != 200 || len(r.body["keys"].([]any)) != 0 {
		t.Fatalf("empty keyring: %d %s", r.status, r.raw)
	}

	// The first entry must be the password; it sets the verifier.
	passkey := map[string]any{
		"kind": "passkey", "credentialId": "Y3JlZA", "salt": b64(16, 'p'), "wrapped": b64(60, 'k'), "verifier": verifier,
	}
	if r := c.do("PUT", "/api/v1/locks/keys/pk1", passkey); r.status != 409 || r.body["error"] != "no_lock_password" {
		t.Fatalf("passkey first: %d %s", r.status, r.raw)
	}
	if r := c.do("PUT", "/api/v1/locks/keys/password", password); r.status != 200 {
		t.Fatalf("create password: %d %s", r.status, r.raw)
	}
	if r := c.do("PUT", "/api/v1/locks/keys/pk1", passkey); r.status != 200 {
		t.Fatalf("add passkey: %d %s", r.status, r.raw)
	}

	// Without the verifier nothing changes: a stolen session can't strand the notes.
	stolen := map[string]any{}
	for k, v := range password {
		stolen[k] = v
	}
	stolen["verifier"] = b64(32, 'x')
	stolen["wrapped"] = b64(60, 'z')
	if r := c.do("PUT", "/api/v1/locks/keys/password", stolen); r.status != 403 || r.body["error"] != "bad_verifier" {
		t.Fatalf("replace without verifier: %d %s", r.status, r.raw)
	}
	if r := c.do("DELETE", "/api/v1/locks/keys/pk1", map[string]any{"verifier": b64(32, 'x')}); r.status != 403 {
		t.Fatalf("delete without verifier: %d %s", r.status, r.raw)
	}
	if r := c.do("DELETE", "/api/v1/locks/keys/password", map[string]any{"verifier": verifier}); r.status != 409 {
		t.Fatalf("delete password: %d %s", r.status, r.raw)
	}

	// Validation.
	for _, bad := range []map[string]any{
		{"kind": "password", "salt": b64(16, 's'), "iterations": 1, "wrapped": b64(60, 'w'), "verifier": verifier},
		{"kind": "password", "salt": b64(4, 's'), "iterations": 600000, "wrapped": b64(60, 'w'), "verifier": verifier},
		{"kind": "password", "salt": b64(16, 's'), "iterations": 600000, "wrapped": b64(10, 'w'), "verifier": verifier},
		{"kind": "recovery", "salt": b64(16, 's'), "iterations": 600000, "wrapped": b64(60, 'w'), "verifier": verifier},
		// Not base64url a client could decode: a length no encoding has,
		// stray bits in the last character, a line break Go would skip.
		{"kind": "password", "salt": strings.Repeat("A", 25), "iterations": 600000, "wrapped": b64(60, 'w'), "verifier": verifier},
		{"kind": "password", "salt": strings.Repeat("A", 21) + "B", "iterations": 600000, "wrapped": b64(60, 'w'), "verifier": verifier},
		{"kind": "password", "salt": b64(16, 's'), "iterations": 600000, "wrapped": b64(60, 'w')[:40] + "\n" + b64(60, 'w')[40:], "verifier": verifier},
	} {
		if r := c.do("PUT", "/api/v1/locks/keys/password", bad); r.status != 400 {
			t.Fatalf("bad %v: %d %s", bad, r.status, r.raw)
		}
	}

	argon := map[string]any{"kind": "password", "salt": b64(16, 's'), "kdf": map[string]any{"name": "argon2id", "m": 65536, "t": 3, "p": 1},
		"wrapped": b64(60, 'w'), "verifier": verifier}
	if r := c.do("PUT", "/api/v1/locks/keys/password", argon); r.status != 400 || r.body["error"] != "invalid_request" || r.body["field"] != "kdf" {
		t.Fatalf("unknown kdf: %d %s", r.status, r.raw)
	}

	r := c.do("GET", "/api/v1/locks", nil)
	keys := r.body["keys"].([]any)
	// Entries saved without a KDF get the one their kind implies.
	if kdf := keys[0].(map[string]any)["kdf"].(map[string]any); kdf["name"] != "pbkdf2-sha256" || kdf["iterations"] != 600000.0 {
		t.Fatalf("password kdf: %s", r.raw)
	}
	if kdf := keys[1].(map[string]any)["kdf"].(map[string]any); kdf["name"] != "hkdf-sha256" || keys[1].(map[string]any)["iterations"] != 0.0 {
		t.Fatalf("passkey kdf: %s", r.raw)
	}
	if len(keys) != 2 || keys[0].(map[string]any)["kind"] != "password" ||
		keys[0].(map[string]any)["wrapped"] != b64(60, 'w') || keys[1].(map[string]any)["credentialId"] != "Y3JlZA" {
		t.Fatalf("keyring: %s", r.raw)
	}
	if strings.Contains(string(r.raw), "verifier") {
		t.Fatalf("verifier leaked: %s", r.raw)
	}

	if r := c.do("DELETE", "/api/v1/locks/keys/pk1", map[string]any{"verifier": verifier}); r.status != 204 {
		t.Fatalf("delete passkey: %d %s", r.status, r.raw)
	}

	// Reset needs the account password, then a new keyring can start.
	if r := c.do("DELETE", "/api/v1/locks", map[string]any{"accountPassword": "wrong"}); r.status != 401 {
		t.Fatalf("reset wrong password: %d %s", r.status, r.raw)
	}
	if r := c.do("DELETE", "/api/v1/locks", map[string]any{"accountPassword": "correct horse battery"}); r.status != 204 {
		t.Fatalf("reset: %d %s", r.status, r.raw)
	}
	stolen["verifier"] = b64(32, 'n')
	if r := c.do("PUT", "/api/v1/locks/keys/password", stolen); r.status != 200 {
		t.Fatalf("new keyring: %d %s", r.status, r.raw)
	}
}

func TestLockedNoteOverHTTP(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c, _ := e.deviceClient()
	sealed := "notesd:locked:v1:" + b64(40, 'c')

	if r := c.do("POST", "/api/v1/notes", map[string]any{"operationId": "c1", "id": "n1", "title": "Bank", "content": sealed}); r.status != 201 {
		t.Fatalf("create: %d %s", r.status, r.raw)
	}
	if r := c.do("POST", "/api/v1/notes", map[string]any{"operationId": "c2", "id": "n2", "title": "x", "content": sealed + "!"}); r.status != 400 {
		t.Fatalf("broken envelope: %d %s", r.status, r.raw)
	}
	r := c.do("GET", "/api/v1/notes", nil)
	item := r.body["notes"].([]any)[0].(map[string]any)
	if item["locked"] != true || item["preview"] != "" {
		t.Fatalf("list: %s", r.raw)
	}
	if r := c.do("GET", "/api/v1/notes/n1", nil); r.body["content"] != sealed {
		t.Fatalf("get: %s", r.raw)
	}
	c.do("POST", "/api/v1/notes", map[string]any{"operationId": "c3", "id": "n3", "title": "Bank hours", "content": "open"})
	r = c.do("GET", "/api/v1/notes?locked=true", nil)
	if items := r.body["notes"].([]any); r.status != 200 || len(items) != 1 || items[0].(map[string]any)["id"] != "n1" || r.body["total"] != 1.0 {
		t.Fatalf("list locked: %d %s", r.status, r.raw)
	}
	if r := c.do("GET", "/api/v1/search?q=bank", nil); len(r.body["results"].([]any)) != 2 {
		t.Fatalf("search all: %s", r.raw)
	}
	if r := c.do("GET", "/api/v1/search?q=bank&locked=true", nil); len(r.body["results"].([]any)) != 1 {
		t.Fatalf("search locked: %s", r.raw)
	}
	if r := c.do("GET", "/api/v1/filters", nil); r.body["locked"] != 1.0 {
		t.Fatalf("filters locked count: %s", r.raw)
	}
	for _, q := range []string{"locked=true&untagged=true", "locked=true&tag=home"} {
		if r := c.do("GET", "/api/v1/notes?"+q, nil); r.status != 400 {
			t.Fatalf("%s: %d", q, r.status)
		}
	}
	c.do("PUT", "/api/v1/settings/email", map[string]any{"apiKey": "re_123456789", "from": "Me <me@example.com>"})
	if r := c.do("POST", "/api/v1/notes/n1/email", map[string]any{"to": []string{"a@b.c"}, "revision": 1}); r.status != 409 || r.body["error"] != "note_locked" {
		t.Fatalf("email: %d %s", r.status, r.raw)
	}
}

// Bodies locked by a later version are stored as given, if well formed, and
// kept out of everything a plain note is in.
func TestLaterLockVersionOverHTTP(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "shared", "crypto-fixtures", "locked.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f struct {
		Unsupported []struct {
			Name, Envelope string
			ServerAccepts  bool
		}
	}
	if err := json.Unmarshal(raw, &f); err != nil || len(f.Unsupported) == 0 {
		t.Fatalf("fixtures: %v", err)
	}
	e := newEnv(t)
	e.setupOwner()
	c, _ := e.deviceClient()
	accepted := 0
	for i, v := range f.Unsupported {
		r := c.do("POST", "/api/v1/notes", map[string]any{"operationId": fmt.Sprintf("c%d", i), "id": fmt.Sprintf("n%d", i), "title": "Vault", "content": v.Envelope})
		switch {
		case v.ServerAccepts && r.status == 201:
			accepted++
			if got := c.do("GET", fmt.Sprintf("/api/v1/notes/n%d", i), nil); got.body["content"] != v.Envelope {
				t.Errorf("%s: stored %s", v.Name, got.raw)
			}
		case !v.ServerAccepts && r.status == 400 && r.body["error"] == "invalid_request":
		default:
			t.Errorf("%s: %d %s", v.Name, r.status, r.raw)
		}
	}
	r := c.do("GET", "/api/v1/notes?locked=true", nil)
	if r.body["total"] != float64(accepted) {
		t.Fatalf("locked: %s", r.raw)
	}
	for _, item := range r.body["notes"].([]any) {
		if it := item.(map[string]any); it["locked"] != true || it["preview"] != "" {
			t.Fatalf("list item: %v", it)
		}
	}
	if r := c.do("GET", "/api/v1/search?q=KTA3PkVMU1phaG92", nil); len(r.body["results"].([]any)) != 0 {
		t.Fatalf("body indexed: %s", r.raw)
	}
	r = c.do("GET", "/api/v1/search?q=vault", nil)
	if res := r.body["results"].([]any); len(res) != accepted || res[0].(map[string]any)["locked"] != true {
		t.Fatalf("search: %s", r.raw)
	}
	c.do("PUT", "/api/v1/settings/email", map[string]any{"apiKey": "re_123456789", "from": "Me <me@example.com>"})
	if r := c.do("POST", "/api/v1/notes/n0/email", map[string]any{"to": []string{"a@b.c"}, "revision": 1}); r.status != 409 || r.body["error"] != "note_locked" {
		t.Fatalf("email: %d %s", r.status, r.raw)
	}
}
