package api

import (
	"bytes"
	"context"
	"errors"
	"net/http"
	"time"

	"notes/server/internal/llm"
)

// GET /api/v1/settings/ai — the key itself is never returned. Like email,
// the provider is server-wide: every account's agents use it, and editable
// says whether this user (an admin) may change it.
func (s *Server) aiSettings(w http.ResponseWriter, r *http.Request) {
	editable := sessionFrom(r.Context()).IsAdmin
	st, err := llm.Load(r.Context(), s.system)
	if errors.Is(err, llm.ErrNotConfigured) {
		writeJSON(w, http.StatusOK, map[string]any{
			"configured": false, "baseUrl": "", "model": "", "keyHint": "",
			"dailyTokenCap": llm.DefaultDailyTokenCap, "editable": editable,
		})
		return
	}
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"configured": true, "baseUrl": st.BaseURL, "model": st.Model, "keyHint": st.KeyHint(),
		"dailyTokenCap": st.DailyTokenCap, "editable": editable,
	})
}

// PUT /api/v1/settings/ai  {"baseUrl", "apiKey"?, "model", "dailyTokenCap"?}
//
// apiKey may be omitted to keep the saved key. Saving doesn't call the
// provider; POST /settings/ai/test does.
func (s *Server) saveAISettings(w http.ResponseWriter, r *http.Request) {
	var in llm.Input
	if err := decode(w, r, &in); err != nil {
		s.writeErr(w, r, err)
		return
	}
	if err := llm.Save(r.Context(), s.system, in); err != nil {
		s.writeErr(w, r, err)
		return
	}
	s.aiChanged()
	s.aiSettings(w, r)
}

// DELETE /api/v1/settings/ai
func (s *Server) deleteAISettings(w http.ResponseWriter, r *http.Request) {
	if err := llm.Clear(r.Context(), s.system); err != nil {
		s.writeErr(w, r, err)
		return
	}
	s.aiChanged()
	w.WriteHeader(http.StatusNoContent)
}

const testWriteDeadline = 2*time.Minute + 30*time.Second

// POST /api/v1/settings/ai/test  [{"baseUrl", "apiKey"?, "model"}]
//
// Probes the provider with the settings in the body, before they're saved
// (apiKey omitted uses the saved key), or with the saved settings when there
// is no body. 502 ai_test_failed carries the reason for the admin.
func (s *Server) testAISettings(w http.ResponseWriter, r *http.Request) {
	body, err := readBody(w, r)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	var st llm.Settings
	if len(bytes.TrimSpace(body)) == 0 {
		st, err = llm.Load(r.Context(), s.system)
	} else {
		var in llm.Input
		if err = unmarshalBody(body, &in); err == nil {
			st, err = llm.Resolve(r.Context(), s.system, in)
		}
	}
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	// The probe may take the client's whole timeout (a local model loading),
	// longer than the server's default write deadline.
	if err := http.NewResponseController(w).SetWriteDeadline(time.Now().Add(testWriteDeadline)); err != nil {
		s.log.WarnContext(r.Context(), "ai test: extend write deadline", "err", err)
	}
	res, err := s.ai.Test(r.Context(), st)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "model": res.Model, "latencyMs": res.Latency.Milliseconds()})
}

const listModelsTimeout = 30 * time.Second

// POST /api/v1/settings/ai/models  {"baseUrl", "apiKey"?}
//
// The provider's models, for picking one before anything is saved (apiKey
// omitted uses the saved key). A POST because the body carries the key.
func (s *Server) listAIModels(w http.ResponseWriter, r *http.Request) {
	var in llm.Input
	if err := decode(w, r, &in); err != nil {
		s.writeErr(w, r, err)
		return
	}
	st, err := llm.ResolveProvider(r.Context(), s.system, in)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), listModelsTimeout)
	defer cancel()
	models, err := s.ai.Models(ctx, st)
	if errors.Is(err, context.DeadlineExceeded) && r.Context().Err() == nil {
		err = &llm.ProviderError{Message: "the provider didn't answer in time"}
	}
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"models": models})
}

func (s *Server) aiChanged() {
	if s.cfg.OnAIChanged != nil {
		s.cfg.OnAIChanged()
	}
}
