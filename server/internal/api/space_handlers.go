package api

import (
	"errors"
	"net/http"
	"strconv"

	"notes/server/internal/sharing"
	"notes/server/internal/spaces"
)

// GET /api/v1/spaces — every space, the account's own and the shared ones it's
// in (§7.9), the default first, with note counts and traits.
func (s *Server) listSpaces(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	own, err := u.Spaces.List(r.Context())
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	shared, err := s.sharing.List(r.Context(), sessionFrom(r.Context()).UserID)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	if err := u.Spaces.WithTraits(r.Context(), shared); err != nil {
		s.writeErr(w, r, err)
		return
	}
	list := append(own, shared...)
	sharing.SortSpaces(list)
	writeJSON(w, http.StatusOK, map[string]any{"spaces": list})
}

// PUT /api/v1/spaces/{id}  {"name": "...", "icon": "..."} — creates or renames a space (a shared one:
// its owner only), answering with it as stored; without "icon" the icon stays.
func (s *Server) putSpace(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	var req struct {
		Name string  `json:"name"`
		Icon *string `json:"icon"`
	}
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	id := r.PathValue("id")
	userID := sessionFrom(r.Context()).UserID
	role, err := s.sharing.Role(r.Context(), userID, id)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	var sp spaces.Space
	if role != "" {
		if sp, err = s.sharing.Rename(r.Context(), userID, id, req.Name, req.Icon); err == nil {
			one := []spaces.Space{sp}
			err = u.Spaces.WithTraits(r.Context(), one)
			sp = one[0]
		}
	} else if taken, terr := s.sharing.Taken(r.Context(), id); terr != nil || taken {
		// Someone else's shared space: one of your own with its id would be two spaces in one.
		err = errors.Join(terr, sharingTaken(taken))
	} else {
		sp, err = u.Spaces.Put(r.Context(), id, req.Name, req.Icon)
	}
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, sp)
}

// DELETE /api/v1/spaces/{id} — only an empty space, and never the default; a
// shared one by its owner only.
func (s *Server) deleteSpace(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	id := r.PathValue("id")
	userID := sessionFrom(r.Context()).UserID
	role, err := s.sharing.Role(r.Context(), userID, id)
	file := sharing.UserRef(userID)
	if err == nil {
		if role != "" {
			file = sharing.SpaceRef(id)
			err = s.sharing.Delete(r.Context(), userID, id)
		} else {
			err = u.Spaces.Delete(r.Context(), id)
		}
	}
	if err == nil {
		// Its address is free again. Missed here (a stop), the publisher finds the space gone.
		err = s.publish.Forget(r.Context(), file, id)
	}
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// POST /api/v1/spaces/{id}/invites  {"userId": 2} — invites an account into a
// space the caller owns; the first invitation shares the space (§7.9).
func (s *Server) invite(w http.ResponseWriter, r *http.Request) {
	var req struct {
		UserID int64 `json:"userId"`
	}
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	in, err := s.sharing.Invite(r.Context(), sessionFrom(r.Context()).UserID, r.PathValue("id"), req.UserID)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, in)
}

// DELETE /api/v1/spaces/{id}/invites/{invite} — the owner takes an invitation back.
func (s *Server) cancelInvite(w http.ResponseWriter, r *http.Request) {
	err := s.sharing.CancelInvite(r.Context(), sessionFrom(r.Context()).UserID, r.PathValue("id"), r.PathValue("invite"))
	s.writeNoContent(w, r, err)
}

// DELETE /api/v1/spaces/{id}/members/{user} — the owner removes a member, or
// a member leaves.
func (s *Server) removeMember(w http.ResponseWriter, r *http.Request) {
	member, err := strconv.ParseInt(r.PathValue("user"), 10, 64)
	if err != nil || member <= 0 {
		s.writeErr(w, r, sharing.ErrNoUser)
		return
	}
	err = s.sharing.RemoveMember(r.Context(), sessionFrom(r.Context()).UserID, r.PathValue("id"), member)
	s.writeNoContent(w, r, err)
}

// PUT /api/v1/spaces/{id}/owner  {"userId": 2} — the owner hands the space on.
func (s *Server) transferSpace(w http.ResponseWriter, r *http.Request) {
	var req struct {
		UserID int64 `json:"userId"`
	}
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	err := s.sharing.Transfer(r.Context(), sessionFrom(r.Context()).UserID, r.PathValue("id"), req.UserID)
	s.writeNoContent(w, r, err)
}

// GET /api/v1/invites — invitations waiting for the caller.
func (s *Server) listInvites(w http.ResponseWriter, r *http.Request) {
	list, err := s.sharing.Invitations(r.Context(), sessionFrom(r.Context()).UserID)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"invites": list})
}

// POST /api/v1/invites/{invite}/accept — joins the space, answering with it.
func (s *Server) acceptInvite(w http.ResponseWriter, r *http.Request) {
	sp, err := s.sharing.Accept(r.Context(), sessionFrom(r.Context()).UserID, r.PathValue("invite"))
	if err == nil {
		one := []spaces.Space{sp}
		err = userFrom(r.Context()).Spaces.WithTraits(r.Context(), one)
		sp = one[0]
	}
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, sp)
}

// POST /api/v1/invites/{invite}/decline
func (s *Server) declineInvite(w http.ResponseWriter, r *http.Request) {
	err := s.sharing.Decline(r.Context(), sessionFrom(r.Context()).UserID, r.PathValue("invite"))
	s.writeNoContent(w, r, err)
}

// GET /api/v1/people — every account's id and username, to invite.
func (s *Server) listPeople(w http.ResponseWriter, r *http.Request) {
	list, err := s.sharing.People(r.Context())
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"people": list})
}

func sharingTaken(taken bool) error {
	if taken {
		return sharing.ErrIDTaken
	}
	return nil
}

func (s *Server) writeNoContent(w http.ResponseWriter, r *http.Request, err error) {
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
