package api

import (
	"errors"
	"net/http"

	"notes/server/internal/agent"
	"notes/server/internal/llm"
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

// GET /api/v1/agent/runs?space=...&note=...&limit=50&cursor=...
//
// The activity log, newest first, with what each run changed and today's
// usage against the daily cap.
func (s *Server) agentRuns(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	limit, err := queryInt(r, "limit", 50)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	q := r.URL.Query()
	runs, next, err := u.Agent.Runs(r.Context(), agent.RunFilter{Space: q.Get("space"), Note: q.Get("note")},
		int(limit), q.Get("cursor"))
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	usage, err := u.Agent.UsageToday(r.Context())
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	limitCap := int64(llm.DefaultDailyTokenCap)
	if st, err := llm.Load(r.Context(), s.system); err == nil {
		limitCap = st.DailyTokenCap
	} else if !errors.Is(err, llm.ErrNotConfigured) {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"runs": runs, "nextCursor": next, "hasMore": next != "",
		"usage": map[string]any{"day": usage.Day, "tokens": usage.Tokens, "runs": usage.Runs, "dailyTokenCap": limitCap},
	})
}

// POST /api/v1/agent/runs/{id}/undo — puts back what a run changed, where
// nobody has changed it since. 409 nothing_to_undo when nothing could be.
func (s *Server) undoAgentRun(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	res, err := u.Agent.Undo(r.Context(), u.Notes, r.PathValue("id"))
	if errors.Is(err, agent.ErrNothingToUndo) {
		writeJSON(w, http.StatusConflict, map[string]any{"error": "nothing_to_undo",
			"message": "nothing this run changed can be undone", "skipped": res.Skipped})
		return
	}
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

// GET /api/v1/agent/runs/{id} — a run, with a line diff of each note it changed.
func (s *Server) agentRun(w http.ResponseWriter, r *http.Request) {
	d, err := userFrom(r.Context()).Agent.Run(r.Context(), r.PathValue("id"))
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, d)
}
