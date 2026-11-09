package api

import (
	"net/http"

	"notes/server/internal/publish"
	"notes/server/internal/sharing"
	"notes/server/internal/spaces"
	"notes/server/internal/userdata"
)

// publishTarget is the file a space the caller may publish is in: one of their own, or a shared one
// they own, like renaming it (§7.10). release is called once done with it.
func (s *Server) publishTarget(r *http.Request) (file string, d *userdata.Data, release func(), err error) {
	id := r.PathValue("id")
	userID := sessionFrom(r.Context()).UserID
	role, err := s.sharing.Role(r.Context(), userID, id)
	switch {
	case err != nil:
		return "", nil, nil, err
	case role == spaces.RoleMember:
		return "", nil, nil, sharing.ErrNotOwner
	case role == spaces.RoleOwner:
		d, release, err := s.sharing.Open(r.Context(), userID, id)
		return sharing.SpaceRef(id), d, release, err
	}
	u := userFrom(r.Context())
	if _, err := u.Spaces.Get(r.Context(), id); err != nil {
		return "", nil, nil, err
	}
	return sharing.UserRef(userID), u, func() {}, nil
}

// GET /api/v1/spaces/{id}/publish — how a space is published (404 not_published if it never was).
func (s *Server) getPublish(w http.ResponseWriter, r *http.Request) {
	file, d, release, err := s.publishTarget(r)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	defer release()
	set, err := s.publish.Get(r.Context(), file, d, r.PathValue("id"))
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, set)
}

// PUT /api/v1/spaces/{id}/publish  {"handle"?, "origin"?, "css"?} — publishes a space, or changes how;
// a field left out keeps what's stored. The first time, origin is required.
func (s *Server) putPublish(w http.ResponseWriter, r *http.Request) {
	var in publish.Input
	if err := decode(w, r, &in); err != nil {
		s.writeErr(w, r, err)
		return
	}
	file, d, release, err := s.publishTarget(r)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	defer release()
	set, err := s.publish.Publish(r.Context(), file, d, r.PathValue("id"), in)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, set)
}

// DELETE /api/v1/spaces/{id}/publish — takes a space's site down; its address stays its own.
func (s *Server) deletePublish(w http.ResponseWriter, r *http.Request) {
	file, _, release, err := s.publishTarget(r)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	defer release()
	s.writeNoContent(w, r, s.publish.Unpublish(r.Context(), file, r.PathValue("id")))
}
