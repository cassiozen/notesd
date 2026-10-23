package api

import (
	"errors"
	"net/http"

	"notes/server/internal/notes"
	"notes/server/internal/search"
	notesync "notes/server/internal/sync"
)

// GET /api/v1/notes?limit=50&cursor=...&tag=...|untagged=true|locked=true&space=...
func (s *Server) listNotes(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	limit, err := queryInt(r, "limit", notes.DefaultListSize)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	filter, err := queryFilter(r)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	items, next, err := u.Notes.List(r.Context(), filter, int(min(limit, notes.MaxListLimit)), r.URL.Query().Get("cursor"))
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	total, err := u.Notes.Count(r.Context(), filter)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"notes": items, "nextCursor": next, "hasMore": next != "", "total": total})
}

// GET /api/v1/notes/{id} — a deleted note answers 410 with its tombstone.
func (s *Server) getNote(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	n, err := u.Notes.Get(r.Context(), r.PathValue("id"))
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	if n.DeletedAt != nil {
		writeJSON(w, http.StatusGone, errorBody{Error: notes.CodeNoteDeleted, Current: &n})
		return
	}
	writeJSON(w, http.StatusOK, n)
}

// POST /api/v1/notes
//
//	{"operationId": "...", "id": "...", "title": "...", "content": "...",
//	 "createdAt": 0, "updatedAt": 0, "conflictOf": "...", "spaceId": "..."}
func (s *Server) createNote(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	var in notes.CreateInput
	if err := decode(w, r, &in); err != nil {
		s.writeErr(w, r, err)
		return
	}
	res, err := u.Notes.Create(r.Context(), in)
	s.writeMutation(w, r, res, err)
}

// PUT /api/v1/notes/{id}
//
//	{"operationId": "...", "baseRevision": 5, "title": "...", "content": "...", "updatedAt": 0,
//	 "spaceId": "...", "imageIds": [...]}
//
// Title and content are both required: a PUT replaces the whole document.
// spaceId moves the note; absent keeps its space.
// imageIds lists a locked note's images (§7.6); a plain note's are read from its content.
func (s *Server) updateNote(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	var req struct {
		OperationID  string   `json:"operationId"`
		BaseRevision int64    `json:"baseRevision"`
		Title        *string  `json:"title"`
		Content      *string  `json:"content"`
		UpdatedAt    *int64   `json:"updatedAt"`
		SpaceID      *string  `json:"spaceId"`
		ImageIDs     []string `json:"imageIds"`
	}
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	if req.Title == nil || req.Content == nil {
		s.writeErr(w, r, &notes.ValidationError{Field: "body", Message: "title and content are both required"})
		return
	}
	res, err := u.Notes.Update(r.Context(), notes.UpdateInput{
		OperationID:  req.OperationID,
		ID:           r.PathValue("id"),
		BaseRevision: req.BaseRevision,
		Title:        *req.Title,
		Content:      *req.Content,
		UpdatedAt:    req.UpdatedAt,
		SpaceID:      req.SpaceID,
		ImageIDs:     req.ImageIDs,
	})
	s.writeMutation(w, r, res, err)
}

// DELETE /api/v1/notes/{id}  {"operationId": "...", "baseRevision": 5}
func (s *Server) deleteNote(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	var req struct {
		OperationID  string `json:"operationId"`
		BaseRevision int64  `json:"baseRevision"`
	}
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	res, err := u.Notes.Delete(r.Context(), notes.DeleteInput{
		OperationID:  req.OperationID,
		ID:           r.PathValue("id"),
		BaseRevision: req.BaseRevision,
	})
	s.writeMutation(w, r, res, err)
}

func (s *Server) writeMutation(w http.ResponseWriter, r *http.Request, res notes.Result, err error) {
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	if res.Replayed {
		w.Header().Set("Idempotent-Replayed", "true")
	}
	writeJSON(w, res.HTTPStatus, res)
}

// GET /api/v1/sync?since=100&limit=100&generation=...
func (s *Server) sync(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	since, err := queryInt(r, "since", 0)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	limit, err := queryInt(r, "limit", notesync.DefaultLimit)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	page, err := notesync.Changes(r.Context(), u.DB, since, int(min(limit, notesync.MaxLimit)),
		r.URL.Query().Get("generation"))
	if errors.Is(err, notesync.ErrGenerationMismatch) {
		gen, gerr := notesync.Generation(r.Context(), u.DB)
		if gerr != nil {
			s.writeErr(w, r, gerr)
			return
		}
		writeJSON(w, http.StatusConflict, errorBody{
			Error:      "generation_mismatch",
			Message:    "the server's data was replaced (e.g. restored from backup); run a full reconciliation",
			Generation: gen,
		})
		return
	}
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, page)
}

// GET /api/v1/search?q=...&limit=30&offset=0&tag=...|untagged=true|locked=true&space=...|exceptSpace=...
//
// With space, the first page also counts the matches in every other space
// (otherSpaces), for the "N more in other spaces" row.
func (s *Server) search(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	q := r.URL.Query().Get("q")
	if len(q) > 500 {
		q = q[:500]
	}
	limit, err := queryInt(r, "limit", 30)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	offset, err := queryInt(r, "offset", 0)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	if offset > 10_000 {
		offset = 10_000
	}
	filter, err := queryFilter(r)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	results, err := search.Search(r.Context(), u.DB, q, filter, int(min(limit, search.MaxLimit)), int(offset))
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	body := map[string]any{"results": results}
	if filter.Space != "" && offset == 0 {
		other, err := search.Count(r.Context(), u.DB, q, search.Filter{ExceptSpace: filter.Space})
		if err != nil {
			s.writeErr(w, r, err)
			return
		}
		body["otherSpaces"] = other
	}
	writeJSON(w, http.StatusOK, body)
}

// GET /api/v1/filters?space=... — what the list can be narrowed to: every
// tag, and how many notes are locked (none hides the Locked view), in one
// space or in all of them.
func (s *Server) listFilters(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	filter, err := queryFilter(r)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	tags, err := search.ListTags(r.Context(), u.DB, filter.Space)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	locked, err := u.Notes.Count(r.Context(), search.Filter{Locked: true, Space: filter.Space})
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"tags": tags, "locked": locked})
}
