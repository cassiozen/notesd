package locks

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"strings"
	"unicode/utf8"

	"golang.org/x/crypto/pbkdf2"
	"golang.org/x/text/unicode/norm"
)

// Only for `notesd export --include-locked`, run by the owner with the lock
// password. Same rules as the clients: shared/crypto-fixtures/locked.json.

const (
	bodyAAD  = "notesd:locked:v1"
	imageAAD = "notesd:image:v1"
)

// Errors from opening.
var (
	ErrWrongPassword = errors.New("wrong lock password")
	ErrCannotOpen    = errors.New("could not open the locked note")
	// ErrUnsupported is a locked body or keyring entry from a newer notesd.
	ErrUnsupported = errors.New("locked by a newer version of notesd")
)

// PasswordKEK is PBKDF2-HMAC-SHA256 of the NFC-normalized password: the key
// that wraps the locked-notes key.
func PasswordKEK(password string, salt []byte, iterations int) []byte {
	return pbkdf2.Key([]byte(norm.NFC.String(password)), salt, iterations, 32, sha256.New)
}

// Unwrap opens a keyring entry's wrapped locked-notes key.
func Unwrap(kek []byte, kind, wrapped string) ([]byte, error) {
	sealed, err := decodeB64(wrapped)
	if err != nil {
		return nil, ErrCannotOpen
	}
	key, err := open(kek, sealed, "notesd:lnk:v1:"+kind)
	if err != nil || len(key) != 32 {
		return nil, ErrCannotOpen
	}
	return key, nil
}

// UnlockWithPassword returns the locked-notes key from the keyring's password entry.
func UnlockWithPassword(keys []Key, password string) ([]byte, error) {
	for _, k := range keys {
		if k.Kind != KindPassword {
			continue
		}
		kdf := k.KDF
		if kdf.Name == "" {
			kdf = legacyKDF(k.Kind, k.Iterations)
		}
		if kdf.Name != KDFPBKDF2 {
			return nil, ErrUnsupported
		}
		if kdf.Iterations <= 0 {
			return nil, ErrCannotOpen
		}
		salt, err := decodeB64(k.Salt)
		if err != nil {
			return nil, err
		}
		key, err := Unwrap(PasswordKEK(password, salt, kdf.Iterations), k.Kind, k.Wrapped)
		if err != nil {
			// The GCM tag is the only password check there is.
			return nil, ErrWrongPassword
		}
		return key, nil
	}
	return nil, ErrNoPassword
}

// Decrypt returns a locked note's Markdown body.
func Decrypt(key []byte, content string) (string, error) {
	if IsLocked(content) && !Supported(content) {
		return "", ErrUnsupported
	}
	if !ValidEnvelope(content) {
		return "", ErrCannotOpen
	}
	sealed, _ := decodeB64(strings.TrimPrefix(content, Prefix))
	plain, err := open(key, sealed, bodyAAD)
	if err != nil || !utf8.Valid(plain) {
		return "", ErrCannotOpen
	}
	return string(plain), nil
}

// DecryptImage returns a locked note's image: nonce ‖ ciphertext ‖ tag, as stored.
func DecryptImage(key, sealed []byte) ([]byte, error) {
	plain, err := open(key, sealed, imageAAD)
	if err != nil {
		return nil, ErrCannotOpen
	}
	return plain, nil
}

func open(key, sealed []byte, aad string) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	if len(sealed) < minSealed {
		return nil, ErrCannotOpen
	}
	n := gcm.NonceSize()
	return gcm.Open(nil, sealed[:n], sealed[n:], []byte(aad))
}

func decodeB64(s string) ([]byte, error) {
	if strings.ContainsAny(s, "\r\n") {
		return nil, ErrCannotOpen
	}
	return base64.RawURLEncoding.Strict().DecodeString(s)
}
