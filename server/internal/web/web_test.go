package web

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"testing/fstest"
)

func TestServeIndexFallsBackToPlaceholder(t *testing.T) {
	rec := httptest.NewRecorder()
	serveIndex(rec, httptest.NewRequest(http.MethodGet, "/", nil), fstest.MapFS{})
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "mise run build") {
		t.Fatalf("body is not the placeholder: %q", rec.Body.String())
	}
}

func TestServeIndexServesBuild(t *testing.T) {
	root := fstest.MapFS{"index.html": {Data: []byte("<!doctype html>app")}}
	rec := httptest.NewRecorder()
	serveIndex(rec, httptest.NewRequest(http.MethodGet, "/notes/x", nil), root)
	if rec.Code != http.StatusOK || rec.Body.String() != "<!doctype html>app" {
		t.Fatalf("got %d %q", rec.Code, rec.Body.String())
	}
}

func TestServesManifestAsManifest(t *testing.T) {
	root := fstest.MapFS{"manifest.webmanifest": {Data: []byte(`{"name":"notesd"}`)}}
	rec := httptest.NewRecorder()
	handler(root).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/manifest.webmanifest", nil))
	if got := rec.Header().Get("Content-Type"); rec.Code != http.StatusOK || got != "application/manifest+json" {
		t.Fatalf("got %d %q", rec.Code, got)
	}
}

// Browsers check for a new service worker on each open; a long-cached sw.js would pin users to an old build.
func TestServesServiceWorkerUncached(t *testing.T) {
	root := fstest.MapFS{"sw.js": {Data: []byte("self.addEventListener('fetch', () => {})")}}
	rec := httptest.NewRecorder()
	handler(root).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/sw.js", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	if got := rec.Header().Get("Cache-Control"); got != "no-cache" {
		t.Fatalf("Cache-Control = %q, want no-cache", got)
	}
	if got := rec.Header().Get("Content-Type"); !strings.HasPrefix(got, "text/javascript") {
		t.Fatalf("Content-Type = %q, want text/javascript", got)
	}
}

// Without it the browser refuses to compile the sync engine's WebAssembly.
func TestPolicyAllowsWebAssembly(t *testing.T) {
	root := fstest.MapFS{"index.html": {Data: []byte("<!doctype html>app")}}
	rec := httptest.NewRecorder()
	handler(root).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/", nil))
	if got := rec.Header().Get("Content-Security-Policy"); !strings.Contains(got, "script-src 'self' 'wasm-unsafe-eval'") {
		t.Fatalf("Content-Security-Policy = %q", got)
	}
}
