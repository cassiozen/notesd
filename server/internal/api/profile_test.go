package api_test

import (
	"bytes"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"strings"
	"testing"

	"notes/server/internal/auth"
)

// photo is a w×h JPEG with an EXIF segment saying where it was taken, and bytes after its end.
func photo(t *testing.T, w, h int) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for i := range img.Pix {
		img.Pix[i] = 0x80
	}
	img.Set(0, 0, color.RGBA{R: 255, A: 255})
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, nil); err != nil {
		t.Fatal(err)
	}
	exif := append([]byte("Exif\x00\x00"), []byte("GPS 48.8584N 2.2945E")...)
	segment := append([]byte{0xFF, 0xE1, byte((len(exif) + 2) >> 8), byte(len(exif) + 2)}, exif...)
	data := buf.Bytes()
	out := append(append(append([]byte{}, data[:2]...), segment...), data[2:]...)
	return append(out, []byte("trailing secret")...)
}

func avatarPath(id int64, version string) string {
	return fmt.Sprintf("/api/v1/users/%d/avatar?v=%s", id, version)
}

func TestProfileNameAndPhoto(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	e.addKid()
	admin := e.loginAs(owner, password)
	sam := e.loginAs(kid, kidPassword)
	samID := e.userID(admin, kid)
	ownerID := e.userID(admin, owner)

	r := sam.do("GET", "/api/v1/auth/session", nil)
	if r.body["userId"] != float64(samID) || r.body["displayName"] != "" || r.body["avatar"] != "" {
		t.Fatalf("no profile yet: %s", r.raw)
	}

	if r := sam.do("PATCH", "/api/v1/profile", map[string]string{"displayName": "  Sam Smith  "}); r.status != 200 || r.body["displayName"] != "Sam Smith" {
		t.Fatalf("set name: %d %s", r.status, r.raw)
	}
	for _, bad := range []string{strings.Repeat("a", 65), "Sam\nSmith"} {
		if r := sam.do("PATCH", "/api/v1/profile", map[string]string{"displayName": bad}); r.status != 400 || r.body["field"] != "displayName" {
			t.Fatalf("name %q: %d %s", bad, r.status, r.raw)
		}
	}
	if r := sam.do("PATCH", "/api/v1/profile", map[string]any{}); r.status != 400 {
		t.Fatalf("no name at all: %d %s", r.status, r.raw)
	}

	if r := sam.raw("PUT", "/api/v1/profile/avatar", "image/jpeg", photo(t, 600, 600)); r.status != 400 || r.body["field"] != "avatar" {
		t.Fatalf("too wide: %d %s", r.status, r.raw)
	}
	if r := sam.raw("PUT", "/api/v1/profile/avatar", "image/jpeg", append(photo(t, 64, 64), make([]byte, auth.AvatarMaxBytes)...)); r.status != 413 || r.body["error"] != "image_too_large" {
		t.Fatalf("too heavy: %d %s", r.status, r.raw)
	}
	if r := sam.raw("PUT", "/api/v1/profile/avatar", "image/jpeg", photo(t, 300, 200)); r.status != 400 {
		t.Fatalf("not square: %d %s", r.status, r.raw)
	}
	if r := sam.raw("PUT", "/api/v1/profile/avatar", "image/png", pngBytes); r.status != 415 {
		t.Fatalf("png: %d %s", r.status, r.raw)
	}
	if r := sam.raw("PUT", "/api/v1/profile/avatar", "image/jpeg", []byte("not a jpeg")); r.status != 400 {
		t.Fatalf("garbage: %d %s", r.status, r.raw)
	}

	r = sam.raw("PUT", "/api/v1/profile/avatar", "image/jpeg", photo(t, 64, 64))
	version, _ := r.body["avatar"].(string)
	if r.status != 200 || version == "" || r.body["displayName"] != "Sam Smith" {
		t.Fatalf("set photo: %d %s", r.status, r.raw)
	}
	r = sam.do("GET", "/api/v1/auth/session", nil)
	if r.body["displayName"] != "Sam Smith" || r.body["avatar"] != version {
		t.Fatalf("session after: %s", r.raw)
	}

	got := sam.raw("GET", avatarPath(samID, version), "", nil)
	if got.status != 200 || got.header.Get("Content-Type") != "image/jpeg" || !strings.Contains(got.header.Get("Cache-Control"), "immutable") {
		t.Fatalf("get own photo: %d %v", got.status, got.header)
	}
	if bytes.Contains(got.raw, []byte("GPS")) || bytes.Contains(got.raw, []byte("trailing secret")) {
		t.Fatal("the photo kept more than its pixels")
	}
	if _, err := jpeg.Decode(bytes.NewReader(got.raw)); err != nil {
		t.Fatalf("served photo: %v", err)
	}
	// Without the current version, the browser must ask again next time.
	if r := sam.raw("GET", avatarPath(samID, "old"), "", nil); r.status != 200 || strings.Contains(r.header.Get("Cache-Control"), "immutable") {
		t.Fatalf("stale version: %d %v", r.status, r.header)
	}

	// An admin sees everyone's, as People lists them; anyone else sees only their own.
	if r := admin.raw("GET", avatarPath(samID, version), "", nil); r.status != 200 {
		t.Fatalf("admin sees sam's: %d", r.status)
	}
	if r := admin.raw("PUT", "/api/v1/profile/avatar", "image/jpeg", photo(t, 32, 32)); r.status != 200 {
		t.Fatalf("owner's photo: %d %s", r.status, r.raw)
	}
	if r := sam.raw("GET", avatarPath(ownerID, ""), "", nil); r.status != 404 {
		t.Fatalf("sam sees the owner's: %d", r.status)
	}
	if r := sam.raw("GET", avatarPath(999, ""), "", nil); r.status != 404 {
		t.Fatalf("no such account: %d", r.status)
	}

	r = admin.do("GET", "/api/v1/users", nil)
	list, _ := r.body["users"].([]any)
	listed, _ := list[1].(map[string]any)
	if listed["displayName"] != "Sam Smith" || listed["avatar"] != version {
		t.Fatalf("people list: %s", r.raw)
	}

	if r := sam.do("DELETE", "/api/v1/profile/avatar", nil); r.status != 200 || r.body["avatar"] != "" {
		t.Fatalf("remove photo: %d %s", r.status, r.raw)
	}
	if r := sam.raw("GET", avatarPath(samID, version), "", nil); r.status != 404 {
		t.Fatalf("removed photo: %d", r.status)
	}
	if r := sam.do("PATCH", "/api/v1/profile", map[string]string{"displayName": ""}); r.status != 200 || r.body["displayName"] != "" {
		t.Fatalf("clear name: %d %s", r.status, r.raw)
	}
}

func TestInvitationSuggestsAName(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	admin := e.loginAs(owner, password)

	r := admin.do("POST", "/api/v1/users", map[string]string{"username": "mum", "displayName": "Mum"})
	user, _ := r.body["user"].(map[string]any)
	link, _ := r.body["link"].(map[string]any)
	token, _ := link["token"].(string)
	id, _ := user["id"].(float64)
	if r.status != 201 || user["displayName"] != "Mum" {
		t.Fatalf("invite: %d %s", r.status, r.raw)
	}
	if r := admin.do("POST", "/api/v1/users", map[string]string{"username": "dad", "displayName": "Dad\x00"}); r.status != 400 || r.body["field"] != "displayName" {
		t.Fatalf("bad suggestion: %d %s", r.status, r.raw)
	}

	anon := e.client()
	if r := anon.do("POST", "/api/v1/auth/setup-link", map[string]string{"token": token}); r.body["displayName"] != "Mum" {
		t.Fatalf("peek: %s", r.raw)
	}
	if r := anon.do("POST", "/api/v1/auth/setup-link/use", map[string]string{"token": token, "password": "mum's own password", "displayName": " Maria "}); r.status != 204 {
		t.Fatalf("use: %d %s", r.status, r.raw)
	}
	mum := e.loginAs("mum", "mum's own password")
	if r := mum.do("GET", "/api/v1/auth/session", nil); r.body["displayName"] != "Maria" {
		t.Fatalf("her choice: %s", r.raw)
	}

	// A reset doesn't ask, and doesn't change it.
	reset, _ := e.newLink(admin, int64(id))
	if r := anon.do("POST", "/api/v1/auth/setup-link", map[string]string{"token": reset}); r.status != 200 || r.body["displayName"] != nil {
		t.Fatalf("peek reset: %s", r.raw)
	}
	if r := anon.do("POST", "/api/v1/auth/setup-link/use", map[string]string{"token": reset, "password": "a newer password", "displayName": "Someone\nelse"}); r.status != 204 {
		t.Fatalf("use reset: %d %s", r.status, r.raw)
	}
	mum = e.loginAs("mum", "a newer password")
	if r := mum.do("GET", "/api/v1/auth/session", nil); r.body["displayName"] != "Maria" {
		t.Fatalf("after reset: %s", r.raw)
	}
}
