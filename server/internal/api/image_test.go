package api_test

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"

	"notes/server/internal/api"
)

// A 1×1 PNG.
var pngBytes, _ = base64.StdEncoding.DecodeString(
	"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==")

func sha(b []byte) string {
	s := sha256.Sum256(b)
	return hex.EncodeToString(s[:])
}

// raw sends data as the body, unlike do, which sends JSON.
func (c *client) raw(method, path, contentType string, data []byte) resp {
	c.e.t.Helper()
	req, _ := http.NewRequest(method, c.e.srv.URL+path, bytes.NewReader(data))
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	if c.bearer != "" {
		req.Header.Set("Authorization", "Bearer "+c.bearer)
	}
	if c.csrf != "" {
		req.Header.Set("X-CSRF-Token", c.csrf)
	}
	res, err := c.hc.Do(req)
	if err != nil {
		c.e.t.Fatal(err)
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(res.Body)
	out := resp{status: res.StatusCode, header: res.Header, raw: body}
	_ = json.Unmarshal(body, &out.body)
	return out
}

func TestImages(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c, _ := e.deviceClient()
	id := sha(pngBytes)
	path := "/api/v1/images/" + id

	if r := e.client().raw("PUT", path, "image/png", pngBytes); r.status != 401 {
		t.Fatalf("unauthenticated put: %d %s", r.status, r.raw)
	}
	if r := c.raw("GET", path, "", nil); r.status != 404 {
		t.Fatalf("missing: %d %s", r.status, r.raw)
	}

	r := c.raw("PUT", path, "image/png", pngBytes)
	if r.status != 201 || r.body["id"] != id || r.body["contentType"] != "image/png" || r.body["size"] != float64(len(pngBytes)) {
		t.Fatalf("put: %d %s", r.status, r.raw)
	}
	// A retry is the image already stored.
	if r := c.raw("PUT", path, "image/png", pngBytes); r.status != 200 || r.body["contentType"] != "image/png" {
		t.Fatalf("retry: %d %s", r.status, r.raw)
	}

	r = c.raw("GET", path, "", nil)
	if r.status != 200 || !bytes.Equal(r.raw, pngBytes) {
		t.Fatalf("get: %d %q", r.status, r.raw)
	}
	for k, want := range map[string]string{
		"Content-Type":            "image/png",
		"Cache-Control":           "private, max-age=31536000, immutable",
		"ETag":                    `"` + id + `"`,
		"X-Content-Type-Options":  "nosniff",
		"Content-Security-Policy": "sandbox; default-src 'none'",
	} {
		if got := r.header.Get(k); got != want {
			t.Errorf("%s = %q, want %q", k, got, want)
		}
	}
	req, _ := http.NewRequest("GET", e.srv.URL+path, nil)
	req.Header.Set("Authorization", "Bearer "+c.bearer)
	req.Header.Set("If-None-Match", `"`+id+`"`)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 304 {
		t.Fatalf("if-none-match: %d", res.StatusCode)
	}

	// A cached copy of an image that's no longer stored isn't confirmed.
	gone := sha([]byte("never stored"))
	req, _ = http.NewRequest("GET", e.srv.URL+"/api/v1/images/"+gone, nil)
	req.Header.Set("Authorization", "Bearer "+c.bearer)
	req.Header.Set("If-None-Match", `"`+gone+`"`)
	res, err = c.hc.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 404 {
		t.Fatalf("if-none-match for a missing image: %d", res.StatusCode)
	}

	locked := bytes.Repeat([]byte{7}, 40)
	if r := c.raw("PUT", "/api/v1/images/"+sha(locked), "application/vnd.notesd.locked", locked); r.status != 201 {
		t.Fatalf("locked: %d %s", r.status, r.raw)
	}

	svg := []byte(`<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>`)
	gif := []byte("GIF89a\x01\x00\x01\x00")
	for _, bad := range []struct {
		name, id, ct string
		data         []byte
		status       int
		code         string
	}{
		{"hash mismatch", sha([]byte("other")), "image/png", pngBytes, 400, "hash_mismatch"},
		{"uppercase id", strings.ToUpper(id), "image/png", pngBytes, 400, "invalid_request"},
		{"svg", sha(svg), "image/svg+xml", svg, 415, "unsupported_image"},
		{"no type", id, "", pngBytes, 415, "unsupported_image"},
		{"type mismatch", sha(gif), "image/png", gif, 415, "unsupported_image"},
		{"webp without riff", sha(gif), "image/webp", gif, 415, "unsupported_image"},
		{"empty", sha(nil), "image/jpeg", nil, 415, "unsupported_image"},
		{"locked too short", sha([]byte("short")), "application/vnd.notesd.locked", []byte("short"), 415, "unsupported_image"},
	} {
		if r := c.raw("PUT", "/api/v1/images/"+bad.id, bad.ct, bad.data); r.status != bad.status || r.body["error"] != bad.code {
			t.Errorf("%s: %d %s", bad.name, r.status, r.raw)
		}
	}
	if r := c.raw("PUT", "/api/v1/images/"+sha(gif), "image/gif", gif); r.status != 201 {
		t.Fatalf("gif: %d %s", r.status, r.raw)
	}

	big := append([]byte{0xFF, 0xD8, 0xFF}, make([]byte, 10<<20)...)
	if r := c.raw("PUT", "/api/v1/images/"+sha(big), "image/jpeg", big); r.status != 413 || r.body["error"] != "image_too_large" {
		t.Fatalf("too large: %d %s", r.status, r.raw)
	}
}

func TestImagesWebCSRF(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c := e.webClient()
	path := "/api/v1/images/" + sha(pngBytes)

	csrf := c.csrf
	c.csrf = ""
	if r := c.raw("PUT", path, "image/png", pngBytes); r.status != 403 || r.body["error"] != "csrf_failed" {
		t.Fatalf("without csrf: %d %s", r.status, r.raw)
	}
	c.csrf = csrf
	if r := c.raw("PUT", path, "image/png", pngBytes); r.status != 201 {
		t.Fatalf("with csrf: %d %s", r.status, r.raw)
	}
	// An <img> sends only the cookie.
	c.csrf = ""
	if r := c.raw("GET", path, "", nil); r.status != 200 || !bytes.Equal(r.raw, pngBytes) {
		t.Fatalf("cookie get: %d", r.status)
	}
}

func TestEmailInlinesImages(t *testing.T) {
	resend := newFakeResend(t)
	e := newEnvWith(t, api.Config{ResendURL: resend.srv.URL})
	e.setupOwner()
	c, _ := e.deviceClient()
	c.do("PUT", "/api/v1/settings/email", map[string]any{"apiKey": "re_123456789", "from": "me@example.com"})

	id := sha(pngBytes)
	c.raw("PUT", "/api/v1/images/"+id, "image/png", pngBytes)
	locked := bytes.Repeat([]byte{7}, 40)
	lockedID := sha(locked)
	c.raw("PUT", "/api/v1/images/"+lockedID, "application/vnd.notesd.locked", locked)
	missing := sha([]byte("never uploaded"))

	content := "![a dot](notesd:image/" + id + "?w=1&h=1)\n\n" +
		"![sealed](notesd:image/" + lockedID + "?w=1&h=1)\n\n" +
		"![gone](notesd:image/" + missing + "?w=1&h=1)\n\n" +
		"![again](notesd:image/" + id + "?w=1&h=1)\n\n" +
		"![no size](notesd:image/" + id + ")\n"
	c.do("POST", "/api/v1/notes", map[string]any{"operationId": "c1", "id": "n1", "title": "Pics", "content": content})
	if r := c.do("POST", "/api/v1/notes/n1/email", map[string]any{"to": []string{"a@b.c"}, "revision": 1}); r.status != 200 {
		t.Fatalf("send: %d %s", r.status, r.raw)
	}
	m := resend.sent[0]
	html := m["html"].(string)
	// Only app references are attached; one without the size isn't one.
	if strings.Count(html, `src="cid:`+id+`"`) != 2 || strings.Count(html, `src="notesd:image/`+id+`"`) != 1 {
		t.Fatalf("html: %s", html)
	}
	if !strings.Contains(html, "sealed") || !strings.Contains(html, "gone") || strings.Contains(html, lockedID) {
		t.Fatalf("alt text for unattached images: %s", html)
	}
	att, _ := m["attachments"].([]any)
	if len(att) != 1 {
		t.Fatalf("attachments: %v", m["attachments"])
	}
	a := att[0].(map[string]any)
	if a["content_id"] != id || a["content_type"] != "image/png" || a["content"] != base64.StdEncoding.EncodeToString(pngBytes) ||
		!strings.HasSuffix(a["filename"].(string), ".png") {
		t.Fatalf("attachment: %v", a)
	}
}

func TestLockedNoteImageIDs(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c, _ := e.deviceClient()
	sealed := "notesd:locked:v1:c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0"
	a, b := strings.Repeat("a", 64), strings.Repeat("b", 64)
	shows := func() []string {
		t.Helper()
		rows, err := e.userDB(1).R.Query(`SELECT image_id FROM note_images ORDER BY image_id`)
		if err != nil {
			t.Fatal(err)
		}
		defer rows.Close()
		var ids []string
		for rows.Next() {
			var id string
			_ = rows.Scan(&id)
			ids = append(ids, id)
		}
		return ids
	}

	r := c.do("POST", "/api/v1/notes", map[string]any{"operationId": "op-1", "id": "n1", "title": "T", "content": sealed, "imageIds": []string{a}})
	if r.status != 201 || strings.Join(shows(), ",") != a {
		t.Fatalf("create: %d %s, shows %q", r.status, r.raw, shows())
	}
	r = c.do("PUT", "/api/v1/notes/n1", map[string]any{"operationId": "op-2", "baseRevision": 1, "title": "T", "content": strings.Replace(sealed, "c2Vj", "d2Vj", 1), "imageIds": []string{b}})
	if r.status != 200 || strings.Join(shows(), ",") != b {
		t.Fatalf("update: %d %s, shows %q", r.status, r.raw, shows())
	}
	r = c.do("PUT", "/api/v1/notes/n1", map[string]any{"operationId": "op-3", "baseRevision": 2, "title": "T", "content": sealed, "imageIds": []string{"nope"}})
	if r.status != 400 || r.body["field"] != "imageIds" {
		t.Fatalf("bad id: %d %s", r.status, r.raw)
	}
}
