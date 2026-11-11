package api

import (
	"errors"
	"net/http"

	"notes/server/internal/auth"
	"notes/server/internal/clientip"
	"notes/server/internal/locks"
)

// GET /api/v1/locks — the keyring. Every entry is wrapped; the server can't
// open any of them.
func (s *Server) listLockKeys(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	keys, err := u.Locks.List(r.Context())
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"keys": keys})
}

// PUT /api/v1/locks/keys/{id}  {"kind", "credentialId"?, "salt", "kdf"?, "iterations"?, "wrapped", "verifier"}
//
// kdf is {"name": "pbkdf2-sha256", "iterations": N} for password and
// recovery, {"name": "hkdf-sha256"} for a passkey. Apps from before kdf
// existed send only iterations (0 for a passkey), which then implies it.
// Sent with kdf, iterations must equal kdf's (0 for a passkey).
func (s *Server) putLockKey(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	var req locks.PutInput
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	k, err := u.Locks.Put(r.Context(), r.PathValue("id"), req)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, k)
}

// DELETE /api/v1/locks/keys/{id}  {"verifier"}
func (s *Server) deleteLockKey(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	var req struct {
		Verifier string `json:"verifier"`
	}
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	if err := u.Locks.Delete(r.Context(), r.PathValue("id"), req.Verifier); err != nil {
		s.writeErr(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// DELETE /api/v1/locks  {"accountPassword"}
//
// Forgets the keyring after a forgotten lock password: notes locked so far
// can never be opened again. Needs the account password, rate-limited like
// a password change.
func (s *Server) resetLocks(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	key := "password:" + clientip.Of(r)
	if !s.allow(w, key) {
		return
	}
	var req struct {
		AccountPassword string `json:"accountPassword"`
	}
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	if err := s.auth.CheckPassword(r.Context(), sessionFrom(r.Context()).UserID, req.AccountPassword); err != nil {
		if errors.Is(err, auth.ErrInvalidCredentials) {
			s.limiter.Fail(key)
		}
		s.writeErr(w, r, err)
		return
	}
	if err := u.Locks.Reset(r.Context()); err != nil {
		s.writeErr(w, r, err)
		return
	}
	s.log.Warn("locked notes keyring reset", "username", sessionFrom(r.Context()).Username)
	w.WriteHeader(http.StatusNoContent)
}
