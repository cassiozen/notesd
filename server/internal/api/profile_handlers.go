package api

import (
	"errors"
	"io"
	"mime"
	"net/http"
	"strconv"

	"notes/server/internal/auth"
)

// PATCH /api/v1/profile {"displayName": "..."} — "" goes back to the username.
func (s *Server) updateProfile(w http.ResponseWriter, r *http.Request) {
	var req struct {
		DisplayName *string `json:"displayName"`
	}
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	if req.DisplayName == nil {
		writeJSON(w, http.StatusBadRequest, errorBody{Error: "invalid_request", Message: "displayName is required", Field: "displayName"})
		return
	}
	u, err := s.auth.SetDisplayName(r.Context(), sessionFrom(r.Context()).UserID, *req.DisplayName)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, u)
}

// PUT /api/v1/profile/avatar — a square JPEG as the body.
func (s *Server) putAvatar(w http.ResponseWriter, r *http.Request) {
	if ct, _, err := mime.ParseMediaType(r.Header.Get("Content-Type")); err != nil || ct != "image/jpeg" {
		writeError(w, http.StatusUnsupportedMediaType, "unsupported_image", "a photo must be a JPEG")
		return
	}
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, auth.AvatarMaxBytes))
	if err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			writeError(w, http.StatusRequestEntityTooLarge, "image_too_large", "a photo can be at most 1 MiB")
			return
		}
		s.writeErr(w, r, err)
		return
	}
	u, err := s.auth.SetAvatar(r.Context(), sessionFrom(r.Context()).UserID, data)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, u)
}

// DELETE /api/v1/profile/avatar
func (s *Server) deleteAvatar(w http.ResponseWriter, r *http.Request) {
	u, err := s.auth.RemoveAvatar(r.Context(), sessionFrom(r.Context()).UserID)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, u)
}

// GET /api/v1/users/{id}/avatar?v=<version> — cached for good when v is the
// current version (the account's avatar field), since a new photo means a new URL.
func (s *Server) getAvatar(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		s.writeErr(w, r, auth.ErrNoAvatar)
		return
	}
	data, version, err := s.auth.Avatar(r.Context(), sessionFrom(r.Context()), id)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	h := w.Header()
	etag := `"` + version + `"`
	h.Set("ETag", etag)
	if r.URL.Query().Get("v") == version {
		h.Set("Cache-Control", "private, max-age=31536000, immutable")
	} else {
		h.Set("Cache-Control", "private, no-cache")
	}
	if r.Header.Get("If-None-Match") == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	h.Set("Content-Type", "image/jpeg")
	h.Set("Content-Length", strconv.Itoa(len(data)))
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Content-Security-Policy", "sandbox; default-src 'none'")
	w.WriteHeader(http.StatusOK)
	if r.Method != http.MethodHead {
		_, _ = w.Write(data)
	}
}
