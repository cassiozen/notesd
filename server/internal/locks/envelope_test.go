package locks

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestEnvelopeFixtures(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "shared", "crypto-fixtures", "locked.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f struct {
		Envelopes   []struct{ Name, Envelope string } `json:"envelopes"`
		Invalid     []struct{ Name, Envelope string } `json:"invalid"`
		Unsupported []struct {
			Name, Envelope string
			Version        *string
			ServerAccepts  bool
		} `json:"unsupported"`
	}
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	for _, c := range f.Envelopes {
		if !IsLocked(c.Envelope) || !ValidEnvelope(c.Envelope) {
			t.Errorf("%s: not accepted", c.Name)
		}
	}
	// The server can't check ciphertext, only shape: these must be refused.
	shape := map[string]bool{"too short": true, "not base64url": true, "line break": true}
	for _, c := range f.Invalid {
		if shape[c.Name] && ValidEnvelope(c.Envelope) {
			t.Errorf("%s: accepted", c.Name)
		}
	}
	// Later versions are locked notes all the same, stored opaque if well formed.
	for _, c := range f.Unsupported {
		want := ""
		if c.Version != nil {
			want = *c.Version
		}
		if !IsLocked(c.Envelope) || Supported(c.Envelope) || Version(c.Envelope) != want || ValidEnvelope(c.Envelope) != c.ServerAccepts {
			t.Errorf("%s: locked %v, supported %v, version %q, accepted %v", c.Name,
				IsLocked(c.Envelope), Supported(c.Envelope), Version(c.Envelope), ValidEnvelope(c.Envelope))
		}
		if _, err := Decrypt(make([]byte, 32), c.Envelope); !errors.Is(err, ErrUnsupported) {
			t.Errorf("%s: decrypt %v", c.Name, err)
		}
	}
	if len(f.Unsupported) == 0 {
		t.Fatal("no unsupported fixtures")
	}
}

func TestValidEnvelope(t *testing.T) {
	good := Prefix + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" // 28 bytes
	for in, want := range map[string]bool{
		good:                           true,
		good + "x":                     false, // a typed character breaks the base64
		good + "\n\nmore text":         false,
		good + "==":                    false, // padded
		good[:30] + "\r\n" + good[30:]: false, // Go's decoder alone would skip these
		"plain " + good:                false,
		Prefix:                         false,
	} {
		if got := ValidEnvelope(in); got != want {
			t.Errorf("ValidEnvelope(%q) = %v, want %v", in, got, want)
		}
	}
}

func TestOpaqueEnvelope(t *testing.T) {
	for in, want := range map[string]bool{
		"notesd:locked:v2:abc":         true,
		"notesd:locked:v2:abc\n":       false,
		"notesd:locked:v2:":            false,
		"notesd:locked:v0:abc":         false,
		"notesd:locked:v02:abc":        false,
		"notesd:locked:v2:abc=":        false,
		"notesd:locked:v2:ab:c":        false,
		"notesd:locked:":               false,
		"notesd:locked:v1:abc":         false, // v1 is checked strictly
		"Notesd:locked:v2:abc":         false,
		"notesd:locked:v2x:abc":        false,
		"notesd:locked:v99999999:abc_": true,
	} {
		if got := ValidEnvelope(in); got != want {
			t.Errorf("ValidEnvelope(%q) = %v", in, got)
		}
	}
}
