// Package web serves the web application's build output, embedded into the
// binary at compile time.
//
// apps/web builds into internal/web/dist. Requests for files that exist are
// served directly (fingerprinted files under /assets/ are cached for a
// year); any other path that does not look like a file gets index.html, so
// client-side routes work on reload. dist is build output and only holds a
// .gitkeep in git; until the web app is built, pages get placeholder.html.
package web

import (
	"embed"
	"io/fs"
	"net/http"
	"path"
	"strings"
)

//go:embed all:dist
var dist embed.FS

//go:embed placeholder.html
var placeholder []byte

// Handler returns the static file handler for the embedded web app.
func Handler() http.Handler {
	root, err := fs.Sub(dist, "dist")
	if err != nil {
		panic(err)
	}
	return handler(root)
}

func handler(root fs.FS) http.Handler {
	files := http.FileServerFS(root)

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.Header().Set("Allow", "GET, HEAD")
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		setSecurityHeaders(w.Header())

		name := strings.TrimPrefix(path.Clean("/"+r.URL.Path), "/")
		if name == "" || name == "index.html" {
			serveIndex(w, r, root)
			return
		}
		if info, err := fs.Stat(root, name); err != nil || info.IsDir() {
			if err != nil && path.Ext(name) != "" {
				http.NotFound(w, r)
				return
			}
			serveIndex(w, r, root)
			return
		}
		if strings.HasPrefix(name, "assets/") {
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		} else {
			w.Header().Set("Cache-Control", "no-cache")
		}
		// Not in Go's built-in types; sniffed as text/plain, which nosniff then makes browsers reject.
		if path.Ext(name) == ".webmanifest" {
			w.Header().Set("Content-Type", "application/manifest+json")
		}
		files.ServeHTTP(w, r)
	})
}

func serveIndex(w http.ResponseWriter, r *http.Request, root fs.FS) {
	status := http.StatusOK
	b, err := fs.ReadFile(root, "index.html")
	if err != nil {
		status, b = http.StatusServiceUnavailable, placeholder
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.WriteHeader(status)
	if r.Method == http.MethodHead {
		return
	}
	_, _ = w.Write(b)
}

func setSecurityHeaders(h http.Header) {
	// wasm-unsafe-eval: the sync engine is SQLite compiled to WebAssembly; scripts stay 'self' only.
	h.Set("Content-Security-Policy",
		"default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; "+
			"object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Referrer-Policy", "same-origin")
	h.Set("X-Frame-Options", "DENY")
}
