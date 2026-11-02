// Package locks covers locked notes (§10.1): recognizing their encrypted
// content and storing the keyring that clients unlock them with. The server
// never holds a key; it only keeps locked bodies out of search, tags,
// previews and email, and stores wrapped keys it cannot open. The exception
// is the export command, given the lock password by the owner (open.go).
package locks

import (
	"encoding/base64"
	"regexp"
	"strings"
)

// Family starts every locked note's content, whatever its version. Anything
// in the family is treated as locked, including versions this server can't
// read, so a newer app's body is never indexed, mailed or mirrored.
const Family = "notesd:locked:"

// Prefix starts a v1 locked note's content, the version this server knows;
// the rest is base64url(nonce ‖ ciphertext ‖ tag) of the Markdown body.
const Prefix = Family + "v1:"

// minSealed is a 12-byte nonce plus a 16-byte GCM tag around an empty body.
const minSealed = 12 + 16

var (
	versionPattern = regexp.MustCompile(`^v[1-9][0-9]*$`)
	opaquePattern  = regexp.MustCompile(`^notesd:locked:v[1-9][0-9]*:[A-Za-z0-9_-]+$`)
)

// EnvelopeLen is the length of the locked content for a plain body of n bytes.
func EnvelopeLen(n int) int {
	return len(Prefix) + base64.RawURLEncoding.EncodedLen(minSealed+n)
}

// IsLocked reports whether content is (or claims to be) a locked body, of
// any version.
func IsLocked(content string) bool { return strings.HasPrefix(content, Family) }

// Version is a locked body's version ("v1", "v2"…), or "" if content isn't
// locked or its version can't be parsed.
func Version(content string) string {
	rest, ok := strings.CutPrefix(content, Family)
	if !ok {
		return ""
	}
	v, _, ok := strings.Cut(rest, ":")
	if !ok || !versionPattern.MatchString(v) {
		return ""
	}
	return v
}

// Supported reports whether content is a locked body of the version this
// server can open (with the owner's password, for export).
func Supported(content string) bool { return strings.HasPrefix(content, Prefix) }

// ValidEnvelope reports whether locked content is well formed. A v1 body
// must be one line of unpadded base64url long enough to hold a nonce and
// tag: that can't check the ciphertext, but it stops an edit made on the
// encrypted text (a typed character, an added line) from destroying the
// note. A later version's body is stored as given, as long as it is one
// line of base64url after its version: the server can't know its layout.
func ValidEnvelope(content string) bool {
	if !Supported(content) {
		// Go's regexp $ matches only at the end of text, so no trailing newline gets in.
		return opaquePattern.MatchString(content)
	}
	body := strings.TrimPrefix(content, Prefix)
	// Go's decoder skips CR and LF even in strict mode; the clients' don't.
	if strings.ContainsAny(body, "\r\n") {
		return false
	}
	raw, err := base64.RawURLEncoding.Strict().DecodeString(body)
	return err == nil && len(raw) >= minSealed
}
