package api

import "net/http"

// GET /api/v1/spaces — every space, the default first, with its note count.
func (s *Server) listSpaces(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	list, err := u.Spaces.List(r.Context())
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"spaces": list})
}

// PUT /api/v1/spaces/{id}  {"name": "...", "icon": "..."}
//
// Creates or renames the space, answering with it as stored: a name another
// space has gets a number appended. Without "icon" the icon stays as it is.
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
	sp, err := u.Spaces.Put(r.Context(), r.PathValue("id"), req.Name, req.Icon)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, sp)
}

// DELETE /api/v1/spaces/{id} — only an empty space, and never the default.
func (s *Server) deleteSpace(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	if err := u.Spaces.Delete(r.Context(), r.PathValue("id")); err != nil {
		s.writeErr(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
