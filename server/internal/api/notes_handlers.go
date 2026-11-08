package api

import (
	"errors"
	"net/http"
	"sort"

	"notes/server/internal/notes"
	"notes/server/internal/search"
	"notes/server/internal/sharing"
	notesync "notes/server/internal/sync"
	"notes/server/internal/userdata"
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

// GET /api/v1/notes/{id} — from whichever file it's in (§7.9); a deleted note answers 410 with its
// tombstone.
func (s *Server) getNote(w http.ResponseWriter, r *http.Request) {
	_, n, release, err := s.sharing.Locate(r.Context(), sessionFrom(r.Context()).UserID, r.PathValue("id"))
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	release()
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
	var in notes.CreateInput
	body, err := decodeBody(w, r, &in)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	if in.Fingerprint, err = notes.Fingerprint("create", "", body); err != nil {
		s.writeErr(w, r, err)
		return
	}
	in.Origin = syncOrigin(r)
	res, err := s.sharing.CreateNote(r.Context(), sessionFrom(r.Context()).UserID, in)
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
	var req struct {
		OperationID  string   `json:"operationId"`
		BaseRevision int64    `json:"baseRevision"`
		Title        *string  `json:"title"`
		Content      *string  `json:"content"`
		UpdatedAt    *int64   `json:"updatedAt"`
		SpaceID      *string  `json:"spaceId"`
		ImageIDs     []string `json:"imageIds"`
	}
	body, err := decodeBody(w, r, &req)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	if req.Title == nil || req.Content == nil {
		s.writeErr(w, r, &notes.ValidationError{Field: "body", Message: "title and content are both required"})
		return
	}
	fp, err := notes.Fingerprint("update", r.PathValue("id"), body)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	res, err := s.sharing.UpdateNote(r.Context(), sessionFrom(r.Context()).UserID, notes.UpdateInput{
		Fingerprint:  fp,
		OperationID:  req.OperationID,
		ID:           r.PathValue("id"),
		BaseRevision: req.BaseRevision,
		Title:        *req.Title,
		Content:      *req.Content,
		UpdatedAt:    req.UpdatedAt,
		SpaceID:      req.SpaceID,
		ImageIDs:     req.ImageIDs,
		Origin:       syncOrigin(r),
	})
	s.writeMutation(w, r, res, err)
}

// DELETE /api/v1/notes/{id}  {"operationId": "...", "baseRevision": 5}
func (s *Server) deleteNote(w http.ResponseWriter, r *http.Request) {
	var req struct {
		OperationID  string `json:"operationId"`
		BaseRevision int64  `json:"baseRevision"`
	}
	body, err := decodeBody(w, r, &req)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	fp, err := notes.Fingerprint("delete", r.PathValue("id"), body)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	res, err := s.sharing.DeleteNote(r.Context(), sessionFrom(r.Context()).UserID, notes.DeleteInput{
		Fingerprint:  fp,
		OperationID:  req.OperationID,
		ID:           r.PathValue("id"),
		BaseRevision: req.BaseRevision,
		Origin:       syncOrigin(r),
	})
	s.writeMutation(w, r, res, err)
}

// syncOrigin is what X-Notesd-Sync says about a write: "initial" while a
// device makes its first sync, "import" for an import, absent for an edit.
// Any other value is kept as "other", so a later client's new reason still
// means "not someone editing".
func syncOrigin(r *http.Request) string {
	switch v := r.Header.Get("X-Notesd-Sync"); v {
	case "":
		return ""
	case "initial", "import":
		return v
	default:
		return "other"
	}
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
	var results []search.Result
	if filter.ExceptSpace != "" {
		// The other spaces' notes are in the account's file and every shared space's.
		results, err = s.searchFiles(r, q, filter, int(min(limit, search.MaxLimit)), int(offset))
	} else {
		results, err = search.Search(r.Context(), u.DB, q, filter, int(min(limit, search.MaxLimit)), int(offset))
	}
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	body := map[string]any{"results": results}
	if filter.Space != "" && offset == 0 {
		other := 0
		err := s.eachFile(r, func(d *userdata.Data) error {
			n, err := search.Count(r.Context(), d.DB, q, search.Filter{ExceptSpace: filter.Space})
			other += n
			return err
		})
		if err != nil {
			s.writeErr(w, r, err)
			return
		}
		body["otherSpaces"] = other
	}
	writeJSON(w, http.StatusOK, body)
}

// eachFile calls fn with every notes file the caller reaches: their own and
// each shared space's they're in (§7.9).
func (s *Server) eachFile(r *http.Request, fn func(*userdata.Data) error) error {
	userID := sessionFrom(r.Context()).UserID
	own, err := s.users.Open(r.Context(), userID)
	if err != nil {
		return err
	}
	if err := fn(own); err != nil {
		return err
	}
	ids, err := s.sharing.SpaceIDs(r.Context(), userID)
	if err != nil {
		return err
	}
	for _, id := range ids {
		d, release, err := s.sharing.Open(r.Context(), userID, id)
		if errors.Is(err, sharing.ErrNotFound) {
			continue // left or deleted since the list
		} else if err != nil {
			return err
		}
		err = fn(d)
		release()
		if err != nil {
			return err
		}
	}
	return nil
}

// searchFiles searches every file the caller reaches, merging the results by
// rank before taking the page.
func (s *Server) searchFiles(r *http.Request, q string, filter search.Filter, limit, offset int) ([]search.Result, error) {
	all := []search.Result{}
	err := s.eachFile(r, func(d *userdata.Data) error {
		// Each file's best offset+limit, a page at a time: a search answers at most MaxLimit.
		for from := 0; from < offset+limit; from += search.MaxLimit {
			got, err := search.Search(r.Context(), d.DB, q, filter, search.MaxLimit, from)
			if err != nil {
				return err
			}
			all = append(all, got...)
			if len(got) < search.MaxLimit {
				break
			}
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	sort.SliceStable(all, func(i, j int) bool {
		if all[i].Rank != all[j].Rank {
			return all[i].Rank < all[j].Rank
		}
		return all[i].UpdatedAt > all[j].UpdatedAt
	})
	if offset >= len(all) {
		return []search.Result{}, nil
	}
	return all[offset:min(len(all), offset+limit)], nil
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
	tags, err := search.ListTags(r.Context(), u.DB, search.Filter{Space: filter.Space})
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
