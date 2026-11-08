package api

import (
	"net/http"

	"notes/server/internal/agent"
)

// GET /api/v1/agent/spaces — every space's agent that has been set up. A
// space missing from the list has none.
func (s *Server) agentConfigs(w http.ResponseWriter, r *http.Request) {
	list, err := userFrom(r.Context()).Agent.Configs(r.Context())
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	if list == nil {
		list = []agent.Config{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"spaces": list})
}

// GET /api/v1/agent/spaces/{id} — the space's agent, off and empty if none
// was set up; 404 for a space that doesn't exist.
func (s *Server) agentConfig(w http.ResponseWriter, r *http.Request) {
	c, err := userFrom(r.Context()).Agent.Config(r.Context(), r.PathValue("id"))
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, c)
}

// PUT /api/v1/agent/spaces/{id}
//
//	{"enabled": true, "prompt": "...", "menuLabel": "...", "triggers": {"newNoteDone": "...", ...}}
//
// Replaces the space's agent and answers with it as stored.
func (s *Server) putAgentConfig(w http.ResponseWriter, r *http.Request) {
	var c agent.Config
	if err := decode(w, r, &c); err != nil {
		s.writeErr(w, r, err)
		return
	}
	c.SpaceID = r.PathValue("id")
	c, err := userFrom(r.Context()).Agent.Put(r.Context(), c)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, c)
}

// DELETE /api/v1/agent/spaces/{id} — forgets the space's agent.
func (s *Server) deleteAgentConfig(w http.ResponseWriter, r *http.Request) {
	if err := userFrom(r.Context()).Agent.Delete(r.Context(), r.PathValue("id")); err != nil {
		s.writeErr(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// GET /api/v1/settings/account
func (s *Server) accountSettings(w http.ResponseWriter, r *http.Request) {
	st, err := userFrom(r.Context()).Agent.Settings(r.Context())
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, st)
}

// PUT /api/v1/settings/account  {"timeZone": "Europe/Lisbon"}
func (s *Server) saveAccountSettings(w http.ResponseWriter, r *http.Request) {
	var req struct {
		TimeZone string `json:"timeZone"`
	}
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	st, err := userFrom(r.Context()).Agent.SetTimeZone(r.Context(), req.TimeZone)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, st)
}
