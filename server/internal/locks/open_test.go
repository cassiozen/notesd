package locks

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestOpenFixtures(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "shared", "crypto-fixtures", "locked.json"))
	if err != nil {
		t.Fatal(err)
	}
	type entry struct {
		Name, Password, Salt, Key, Kind, Kek, Wrapped, Plaintext, Envelope, Plain, Sealed string
		Iterations                                                                        int
	}
	var f struct{ KDF, Wraps, Envelopes, Invalid, Images []entry }
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	b := func(s string) []byte {
		t.Helper()
		out, err := decodeB64(s)
		if err != nil {
			t.Fatal(err)
		}
		return out
	}
	for _, c := range f.KDF {
		if got := PasswordKEK(c.Password, b(c.Salt), c.Iterations); !bytes.Equal(got, b(c.Key)) {
			t.Errorf("kdf %s: wrong key", c.Name)
		}
	}
	for _, c := range f.Wraps {
		if c.Kek == "" {
			continue
		}
		got, err := Unwrap(b(c.Kek), c.Kind, c.Wrapped)
		if err != nil || !bytes.Equal(got, b(c.Key)) {
			t.Errorf("wrap %s: %v", c.Name, err)
		}
		if _, err := Unwrap(b(c.Kek), "other", c.Wrapped); err == nil {
			t.Errorf("wrap %s: opened under another kind", c.Name)
		}
	}
	for _, c := range f.Envelopes {
		if got, err := Decrypt(b(c.Key), c.Envelope); err != nil || got != c.Plaintext {
			t.Errorf("envelope %s = %q, %v", c.Name, got, err)
		}
	}
	for _, c := range f.Invalid {
		if got, err := Decrypt(b(c.Key), c.Envelope); err == nil {
			t.Errorf("invalid %s opened: %q", c.Name, got)
		}
	}
	for _, c := range f.Images {
		got, err := DecryptImage(b(c.Key), b(c.Sealed))
		// A null plain is a vector that must not open.
		if c.Plain == "" {
			if err == nil {
				t.Errorf("image %s opened", c.Name)
			}
		} else if err != nil || !bytes.Equal(got, b(c.Plain)) {
			t.Errorf("image %s: %v", c.Name, err)
		}
	}
	if len(f.KDF) == 0 || len(f.Envelopes) == 0 || len(f.Invalid) == 0 || len(f.Images) == 0 {
		t.Fatal("no fixtures")
	}
}

func TestUnlockWithPassword(t *testing.T) {
	salt := bytes.Repeat([]byte{7}, 16)
	key := bytes.Repeat([]byte{9}, 32)
	keys := []Key{{Kind: KindPassword, Salt: b64(salt), Iterations: 1000,
		Wrapped: b64(seal(t, PasswordKEK("pässwörd", salt, 1000), key, "notesd:lnk:v1:password"))}}

	if got, err := UnlockWithPassword(keys, "pässwörd"); err != nil || !bytes.Equal(got, key) {
		t.Fatalf("right password: %v", err)
	}
	if _, err := UnlockWithPassword(keys, "nope"); !errors.Is(err, ErrWrongPassword) {
		t.Fatalf("wrong password: %v", err)
	}
	if _, err := UnlockWithPassword(nil, "x"); !errors.Is(err, ErrNoPassword) {
		t.Fatalf("no keyring: %v", err)
	}
	keys[0].KDF = KDF{Name: KDFPBKDF2, Iterations: 1000}
	if got, err := UnlockWithPassword(keys, "pässwörd"); err != nil || !bytes.Equal(got, key) {
		t.Fatalf("named kdf: %v", err)
	}
	keys[0].KDF = KDF{Name: "argon2id"}
	if _, err := UnlockWithPassword(keys, "pässwörd"); !errors.Is(err, ErrUnsupported) {
		t.Fatalf("unknown kdf: %v", err)
	}
	keys[0].KDF = KDF{Name: KDFPBKDF2}
	if _, err := UnlockWithPassword(keys, "pässwörd"); !errors.Is(err, ErrCannotOpen) {
		t.Fatalf("no iterations: %v", err)
	}
}

func b64(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

func seal(t *testing.T, key, plain []byte, aad string) []byte {
	t.Helper()
	block, err := aes.NewCipher(key)
	if err != nil {
		t.Fatal(err)
	}
	gcm, _ := cipher.NewGCM(block)
	nonce := bytes.Repeat([]byte{1}, gcm.NonceSize())
	return gcm.Seal(nonce, nonce, plain, []byte(aad))
}
