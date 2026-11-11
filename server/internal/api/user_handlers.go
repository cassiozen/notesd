package api

import (
	"errors"
	"net/http"
	"strconv"

	"notes/server/internal/auth"
	"notes/server/internal/clientip"
)

// GET /api/v1/users — every account, for an admin managing who uses the server.
func (s *Server) listUsers(w http.ResponseWriter, r *http.Request) {
	list, err := s.auth.ListUsers(r.Context())
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	if list == nil {
		list = []auth.User{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"users": list})
}

// POST /api/v1/users {"username": "...", "displayName": "..."} — invites
// someone: an account with no password yet, and the link for them to choose
// one. displayName is optional, a suggestion they may change.
func (s *Server) inviteUser(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Username    string `json:"username"`
		DisplayName string `json:"displayName"`
	}
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	u, link, err := s.auth.Invite(r.Context(), req.Username, req.DisplayName)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	s.log.Info("user invited", "username", u.Username, "by", sessionFrom(r.Context()).Username)
	writeJSON(w, http.StatusCreated, map[string]any{"user": u, "link": link})
}

// POST /api/v1/users/{id}/link — a new setup link, replacing the last,
// with the account as it is now.
func (s *Server) newSetupLink(w http.ResponseWriter, r *http.Request) {
	id, ok := s.userID(w, r)
	if !ok {
		return
	}
	u, link, err := s.auth.NewSetupLink(r.Context(), sessionFrom(r.Context()).UserID, id)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	s.log.Info("setup link made", "username", u.Username, "by", sessionFrom(r.Context()).Username)
	writeJSON(w, http.StatusOK, map[string]any{"user": u, "link": link})
}

// PATCH /api/v1/users/{id} {"isAdmin": bool}
func (s *Server) updateUser(w http.ResponseWriter, r *http.Request) {
	id, ok := s.userID(w, r)
	if !ok {
		return
	}
	var req struct {
		IsAdmin *bool `json:"isAdmin"`
	}
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	if req.IsAdmin == nil {
		writeJSON(w, http.StatusBadRequest, errorBody{Error: "invalid_request", Message: "isAdmin is required", Field: "isAdmin"})
		return
	}
	u, err := s.auth.SetAdmin(r.Context(), sessionFrom(r.Context()).UserID, id, *req.IsAdmin)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	s.log.Info("user admin changed", "username", u.Username, "admin", u.IsAdmin, "by", sessionFrom(r.Context()).Username)
	writeJSON(w, http.StatusOK, u)
}

// DELETE /api/v1/users/{id} — removes an account and signs it out. Its
// notes database stays on disk, as with `notesd remove-user`.
func (s *Server) removeUser(w http.ResponseWriter, r *http.Request) {
	id, ok := s.userID(w, r)
	if !ok {
		return
	}
	u, err := s.auth.RemoveUserByID(r.Context(), sessionFrom(r.Context()).UserID, id)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	s.log.Info("user removed", "username", u.Username, "by", sessionFrom(r.Context()).Username)
	// Their own spaces' sites came down with the account (RemoveAccount): stop serving them now.
	s.publish.Reload(r.Context())
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) userID(w http.ResponseWriter, r *http.Request) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		s.writeErr(w, r, auth.ErrNoUser)
		return 0, false
	}
	return id, true
}

// POST /api/v1/auth/setup-link {"token": "..."} — whose a setup link is.
// POST, so the token stays out of the logged path.
func (s *Server) peekSetupLink(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Token string `json:"token"`
	}
	if !s.decodeLink(w, r, &req) {
		return
	}
	u, err := s.auth.PeekSetupLink(r.Context(), req.Token)
	if err != nil {
		s.linkFailed(w, r, err)
		return
	}
	resp := map[string]any{"username": u.Username, "pending": u.Pending}
	// Only an invitation asks for a name; a reset is someone already settled in.
	if u.Pending {
		resp["displayName"] = u.DisplayName
	}
	writeJSON(w, http.StatusOK, resp)
}

// POST /api/v1/auth/setup-link/use {"token": "...", "password": "...", "displayName": "..."}
// — sets the password, and an invitation's name if given; the app then signs in.
func (s *Server) useSetupLink(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Token       string  `json:"token"`
		Password    string  `json:"password"`
		DisplayName *string `json:"displayName"`
	}
	if !s.decodeLink(w, r, &req) {
		return
	}
	u, err := s.auth.UseSetupLink(r.Context(), req.Token, req.Password, req.DisplayName)
	if err != nil {
		s.linkFailed(w, r, err)
		return
	}
	s.log.Info("password set from a link; sessions revoked", "username", u.Username)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) decodeLink(w http.ResponseWriter, r *http.Request, v any) bool {
	if !s.allow(w, "link:"+clientip.Of(r)) {
		return false
	}
	if err := decode(w, r, v); err != nil {
		s.writeErr(w, r, err)
		return false
	}
	return true
}

func (s *Server) linkFailed(w http.ResponseWriter, r *http.Request, err error) {
	if errors.Is(err, auth.ErrLinkInvalid) {
		s.limiter.Fail("link:" + clientip.Of(r))
	}
	s.writeErr(w, r, err)
}
