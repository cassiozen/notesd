package api

import (
	"errors"
	"io"
	"mime"
	"net/http"
	"strconv"

	"notes/server/internal/images"
)

// PUT /api/v1/images/{id} — the raw image as the body, id its SHA-256.
//
// Content-addressed, so a retry after a lost response stores nothing new
// and answers 200 instead of 201.
func (s *Server) putImage(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	ct, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || !images.Types[ct] {
		writeError(w, http.StatusUnsupportedMediaType, "unsupported_image", "images must be JPEG, PNG, WebP or GIF")
		return
	}
	if r.ContentLength > images.MaxBytes {
		writeError(w, http.StatusRequestEntityTooLarge, "image_too_large", "images can be at most 10 MiB")
		return
	}
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, images.MaxBytes))
	if err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			writeError(w, http.StatusRequestEntityTooLarge, "image_too_large", "images can be at most 10 MiB")
			return
		}
		s.writeErr(w, r, err)
		return
	}
	img, created, err := u.Images.Put(r.Context(), r.PathValue("id"), ct, data)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	status := http.StatusOK
	if created {
		status = http.StatusCreated
	}
	writeJSON(w, status, img)
}

// GET /api/v1/images/{id}
//
// Images never change, so they are cached for good. They are served as
// downloads would be, inert: no sniffing, and a sandbox in case one is ever
// opened as a page.
func (s *Server) getImage(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	id := r.PathValue("id")
	etag := `"` + id + `"`
	if r.Header.Get("If-None-Match") == etag {
		// Only for an image that's still stored: a deleted one is a 404 (§7.6).
		if _, err := u.Images.Info(r.Context(), id); err != nil {
			s.writeErr(w, r, err)
			return
		}
		w.Header().Set("ETag", etag)
		w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
		w.WriteHeader(http.StatusNotModified)
		return
	}
	img, data, err := u.Images.Get(r.Context(), id)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	h := w.Header()
	h.Set("Content-Type", img.ContentType)
	h.Set("Content-Length", strconv.Itoa(len(data)))
	h.Set("Cache-Control", "private, max-age=31536000, immutable")
	h.Set("ETag", etag)
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Content-Security-Policy", "sandbox; default-src 'none'")
	w.WriteHeader(http.StatusOK)
	if r.Method != http.MethodHead {
		_, _ = w.Write(data)
	}
}
