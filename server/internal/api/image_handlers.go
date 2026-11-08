package api

import (
	"errors"
	"io"
	"mime"
	"net/http"
	"strconv"

	"notes/server/internal/images"
	"notes/server/internal/userdata"
)

// PUT /api/v1/images/{id} — the raw image as the body, id its SHA-256.
//
// Content-addressed, so a retry after a lost response stores nothing new
// and answers 200 instead of 201.
func (s *Server) putImage(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	ct, params, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	// A locked image's version is in its subtype; a parameter could only be
	// a version this server would ignore and mistake for v1.
	if err != nil || !images.ValidType(ct) || (images.IsLockedType(ct) && len(params) > 0) {
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
	u, err := s.imageFile(r)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
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

// imageFile is the file to serve an image from: the request's, or at the
// account's address, the first file the account reaches that has it (§7.9).
// Images are their bytes' hash, so any copy is the same image, and a note's
// pictures load at one address whichever file the note is in.
func (s *Server) imageFile(r *http.Request) (*userdata.Data, error) {
	u := userFrom(r.Context())
	id := r.PathValue("id")
	if spaceFrom(r.Context()) != "" || !images.ValidID(id) {
		return u, nil
	}
	if _, err := u.Images.Info(r.Context(), id); !errors.Is(err, images.ErrNotFound) {
		return u, nil
	}
	userID := sessionFrom(r.Context()).UserID
	ids, err := s.sharing.SpaceIDs(r.Context(), userID)
	if err != nil {
		return nil, err
	}
	for _, space := range ids {
		d, err := s.sharing.Open(r.Context(), userID, space)
		if err != nil {
			return nil, err
		}
		if _, err := d.Images.Info(r.Context(), id); err == nil {
			return d, nil
		}
	}
	return u, nil
}
