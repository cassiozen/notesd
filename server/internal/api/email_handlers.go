package api

import (
	"encoding/base64"
	"errors"
	"net/http"

	"notes/server/internal/images"
	"notes/server/internal/locks"
	"notes/server/internal/mail"
)

// GET /api/v1/settings/email — the key itself is never returned. The
// settings are server-wide: every user sends with them, and editable says
// whether this one (an admin) may change them.
func (s *Server) emailSettings(w http.ResponseWriter, r *http.Request) {
	editable := sessionFrom(r.Context()).IsAdmin
	st, err := mail.Load(r.Context(), s.system)
	if errors.Is(err, mail.ErrNotConfigured) {
		writeJSON(w, http.StatusOK, map[string]any{"configured": false, "from": "", "keyHint": "", "editable": editable})
		return
	}
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"configured": true, "from": st.From, "keyHint": st.KeyHint(), "editable": editable})
}

// PUT /api/v1/settings/email  {"apiKey": "re_…", "from": "Name <a@b.c>"}
//
// apiKey may be omitted to keep the saved key.
func (s *Server) saveEmailSettings(w http.ResponseWriter, r *http.Request) {
	var req struct {
		APIKey string `json:"apiKey"`
		From   string `json:"from"`
	}
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	if err := mail.Save(r.Context(), s.system, req.APIKey, req.From); err != nil {
		s.writeErr(w, r, err)
		return
	}
	s.emailSettings(w, r)
}

// DELETE /api/v1/settings/email
func (s *Server) deleteEmailSettings(w http.ResponseWriter, r *http.Request) {
	if err := mail.Clear(r.Context(), s.system); err != nil {
		s.writeErr(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// POST /api/v1/notes/{id}/email  {"to": ["a@b.c"], "revision": 5}
//
// Sends the note as stored. revision is the one the client shows: if the
// server's differs, the client must sync first, so the email matches what
// the sender saw.
func (s *Server) emailNote(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	var req struct {
		To       []string `json:"to"`
		Revision int64    `json:"revision"`
	}
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	to, err := mail.Recipients(req.To)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	st, err := mail.Load(r.Context(), s.system)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	n, err := u.Notes.Get(r.Context(), r.PathValue("id"))
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	if n.DeletedAt != nil {
		writeError(w, http.StatusNotFound, "not_found", "note not found")
		return
	}
	if locks.IsLocked(n.Content) {
		// The server can't read it, and shouldn't mail it if it could (§10.1).
		writeError(w, http.StatusConflict, "note_locked", "locked notes can't be emailed")
		return
	}
	if n.Revision != req.Revision {
		writeError(w, http.StatusConflict, "stale_revision", "the note changed; sync and try again")
		return
	}
	html, text, err := mail.Render(n.Title, n.Content)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	attachments, err := s.emailImages(r, mail.ImageIDs(html), attachmentBudget(len(html), len(text)))
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	attached := map[string]bool{}
	for _, a := range attachments {
		attached[a.ContentID] = true
	}
	id, err := s.mailer.Send(r.Context(), st.APIKey, mail.Message{
		From: st.From, To: to, Subject: mail.Subject(n.Title),
		HTML: mail.InlineImages(html, attached), Text: text, Attachments: attachments,
	})
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"id": id})
}

// Resend refuses an email over 40 MB in all: the bodies, and the
// attachments after base64, which inflates them by a third. The margin is
// for the JSON around them.
const (
	maxEmailBytes   = 40_000_000
	emailOverhead   = 1_000_000
	minImageForMail = 100 // no image is smaller: stop looking once less is left
)

// attachmentBudget is how many raw attachment bytes fit beside bodies of these sizes.
func attachmentBudget(htmlLen, textLen int) int {
	return max(0, (maxEmailBytes-emailOverhead-htmlLen-textLen)/4*3)
}

// emailImages loads a note's images as inline attachments, up to budget
// raw bytes. Missing and encrypted ones are left out, and so is any that
// doesn't fit; their alt text stands in. Sizes are checked before any
// image's bytes are read.
func (s *Server) emailImages(r *http.Request, ids []string, budget int) ([]mail.Attachment, error) {
	u := userFrom(r.Context())
	var out []mail.Attachment
	for _, id := range ids {
		if budget < minImageForMail {
			break
		}
		info, err := u.Images.Info(r.Context(), id)
		if errors.Is(err, images.ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		if info.ContentType == images.Locked || info.Size > int64(budget) {
			continue
		}
		img, data, err := u.Images.Get(r.Context(), id)
		if errors.Is(err, images.ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		budget -= len(data)
		out = append(out, mail.Attachment{
			Filename:    id[:12] + imageExt[img.ContentType],
			Content:     base64.StdEncoding.EncodeToString(data),
			ContentType: img.ContentType,
			ContentID:   id,
		})
	}
	return out, nil
}

var imageExt = map[string]string{"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp", "image/gif": ".gif"}
