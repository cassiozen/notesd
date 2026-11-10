package api

import (
	"context"
	"errors"
	"net/http"
	"slices"

	"notes/server/internal/agent"
	"notes/server/internal/llm"
	"notes/server/internal/sharing"
	"notes/server/internal/userdata"
)

// Agents live in the file that holds their space (§7.11): the account's own, or a shared space's.

// agentStore is the agent store of the file holding spaceID, held until release.
func (s *Server) agentStore(ctx context.Context, spaceID string) (*agent.Store, func(), error) {
	f, release, err := s.sharing.ForSpace(ctx, sessionFrom(ctx).UserID, spaceID)
	if err != nil {
		return nil, nil, err
	}
	return f.Data.Agent, release, nil
}

// agentFiles is every file the account reaches, its own first, held until release.
func (s *Server) agentFiles(ctx context.Context) ([]sharing.File, func(), error) {
	return s.sharing.Files(ctx, sessionFrom(ctx).UserID)
}

// GET /api/v1/agent/spaces — every space's agent that has been set up, in
// the account's spaces and the shared ones it's in. A space missing from
// the list has none.
func (s *Server) agentConfigs(w http.ResponseWriter, r *http.Request) {
	files, release, err := s.agentFiles(r.Context())
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	defer release()
	list := []agent.Config{}
	for _, f := range files {
		c, err := f.Data.Agent.Configs(r.Context())
		if err != nil {
			s.writeErr(w, r, err)
			return
		}
		list = append(list, c...)
	}
	writeJSON(w, http.StatusOK, map[string]any{"spaces": list})
}

// GET /api/v1/agent/spaces/{id} — the space's agent, off and empty if none
// was set up; 404 for a space that doesn't exist.
func (s *Server) agentConfig(w http.ResponseWriter, r *http.Request) {
	a, release, err := s.agentStore(r.Context(), r.PathValue("id"))
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	defer release()
	c, err := a.Config(r.Context(), r.PathValue("id"))
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
	a, release, err := s.agentStore(r.Context(), c.SpaceID)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	defer release()
	c, err = a.Put(r.Context(), c)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, c)
}

// DELETE /api/v1/agent/spaces/{id} — forgets the space's agent.
func (s *Server) deleteAgentConfig(w http.ResponseWriter, r *http.Request) {
	a, release, err := s.agentStore(r.Context(), r.PathValue("id"))
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	defer release()
	if err := a.Delete(r.Context(), r.PathValue("id")); err != nil {
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
// The activity log, newest first, with what each run changed: a space's, or
// every file's the account reaches.
func (s *Server) agentRuns(w http.ResponseWriter, r *http.Request) {
	limit, err := queryInt(r, "limit", 50)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	q := r.URL.Query()
	filter := agent.RunFilter{Space: q.Get("space"), Note: q.Get("note")}
	files, release, err := s.agentFiles(r.Context())
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	defer release()
	if filter.Space != "" {
		files = []sharing.File{sharing.FileFor(files, filter.Space)}
	}
	var pages [][]agent.Run
	more := false
	for _, f := range files {
		runs, next, err := f.Data.Agent.Runs(r.Context(), filter, int(limit), q.Get("cursor"))
		if err != nil {
			s.writeErr(w, r, err)
			return
		}
		pages, more = append(pages, runs), more || next != ""
	}
	runs, next := agent.MergeRuns(pages, int(limit), more)
	writeJSON(w, http.StatusOK, map[string]any{"runs": runs, "nextCursor": next, "hasMore": next != ""})
}

// GET /api/v1/agent/usage?space=... — the space's usage today, against the
// daily cap per space. Today is in the time zone of the account asking.
func (s *Server) agentUsage(w http.ResponseWriter, r *http.Request) {
	space := r.URL.Query().Get("space")
	if space == "" {
		writeJSON(w, http.StatusBadRequest, errorBody{Error: "invalid_request", Message: "space is required", Field: "space"})
		return
	}
	st, err := userFrom(r.Context()).Agent.Settings(r.Context())
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	a, release, err := s.agentStore(r.Context(), space)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	defer release()
	usage, err := a.UsageToday(r.Context(), space, st.Location())
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
	writeJSON(w, http.StatusOK, map[string]any{"day": usage.Day, "tokens": usage.Tokens, "runs": usage.Runs, "dailyTokenCap": limitCap})
}

// POST /api/v1/agent/runs/{id}/undo — puts back what a run changed, where
// nobody has changed it since. 409 nothing_to_undo when nothing could be.
func (s *Server) undoAgentRun(w http.ResponseWriter, r *http.Request) {
	files, release, err := s.agentFiles(r.Context())
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	defer release()
	f, err := runFile(r.Context(), files, r.PathValue("id"))
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	res, err := f.Data.Agent.Undo(r.Context(), f.Data.Notes, r.PathValue("id"), sessionFrom(r.Context()).UserID)
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
	files, release, err := s.agentFiles(r.Context())
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	defer release()
	f, err := runFile(r.Context(), files, r.PathValue("id"))
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	d, err := f.Data.Agent.Run(r.Context(), r.PathValue("id"))
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, d)
}

// runFile is the file whose activity has run id: run ids are unique across files.
func runFile(ctx context.Context, files []sharing.File, id string) (sharing.File, error) {
	for _, f := range files {
		ok, err := f.Data.Agent.HasRun(ctx, id)
		if err != nil {
			return sharing.File{}, err
		}
		if ok {
			return f, nil
		}
	}
	return sharing.File{}, agent.ErrRunNotFound
}

// agentConfigsFor reads the agents of an account's spaces wherever they are: its own file, and each
// shared space's it's in (§7.11).
func agentConfigsFor(userID int64, own *userdata.Data, share *sharing.Service, users *userdata.Registry) func(context.Context, []string) ([]agent.Config, error) {
	return func(ctx context.Context, ids []string) ([]agent.Config, error) {
		list, err := own.Agent.Configs(ctx)
		if err != nil {
			return nil, err
		}
		shared, err := share.SpaceIDs(ctx, userID)
		if err != nil {
			return nil, err
		}
		for _, id := range shared {
			if !slices.Contains(ids, id) {
				continue
			}
			// The account is in it; no hold, as listing it may already be inside one.
			d, err := users.OpenSpaceIfExists(ctx, id)
			if err != nil || d == nil {
				return list, err
			}
			c, err := d.Agent.Configs(ctx)
			if err != nil {
				return nil, err
			}
			list = append(list, c...)
		}
		return list, nil
	}
}
