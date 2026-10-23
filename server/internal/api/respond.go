package api

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strconv"

	"notes/server/internal/auth"
	"notes/server/internal/images"
	"notes/server/internal/locks"
	"notes/server/internal/mail"
	"notes/server/internal/notes"
	"notes/server/internal/search"
	"notes/server/internal/spaces"
	notesync "notes/server/internal/sync"
)

// Request body limit. Content is capped at 1 MiB, and JSON escaping can
// inflate it.
const maxBodyBytes = 4 << 20

// errorBody is the shape of every error response.
type errorBody struct {
	Error   string `json:"error"`
	Message string `json:"message,omitempty"`
	Field   string `json:"field,omitempty"`
	// Current is the server's latest version of the note on conflicts.
	Current *notes.Note `json:"current,omitempty"`
	// Generation is the server's sync generation on generation mismatches.
	Generation string `json:"generation,omitempty"`
	// Count is how many notes a space still holds, on space_not_empty.
	Count *int `json:"count,omitempty"`
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	if v == nil {
		return
	}
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, errorBody{Error: code, Message: message})
}

// decode reads a JSON request body into v. Unknown fields are ignored so
// newer clients keep working against older servers.
func decode(w http.ResponseWriter, r *http.Request, v any) error {
	r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(v); err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			return &notes.ValidationError{Field: "body", Message: "request body too large"}
		}
		if errors.Is(err, io.EOF) {
			return &notes.ValidationError{Field: "body", Message: "request body is required"}
		}
		return &notes.ValidationError{Field: "body", Message: "invalid JSON: " + err.Error()}
	}
	if dec.More() {
		return &notes.ValidationError{Field: "body", Message: "unexpected data after JSON object"}
	}
	return nil
}

// writeErr maps domain errors to HTTP responses.
func (s *Server) writeErr(w http.ResponseWriter, r *http.Request, err error) {
	var verr *notes.ValidationError
	var aerr *auth.InputError
	var conflict *notes.ConflictError
	var merr *mail.InputError
	var lerr *locks.InputError
	var send *mail.SendError
	var terr *images.TypeError
	var serr *spaces.InputError
	var full *spaces.NotEmptyError
	switch {
	case errors.As(err, &verr):
		writeJSON(w, http.StatusBadRequest, errorBody{Error: "invalid_request", Message: verr.Message, Field: verr.Field})
	case errors.As(err, &aerr):
		writeJSON(w, http.StatusBadRequest, errorBody{Error: "invalid_request", Message: aerr.Message, Field: aerr.Field})
	case errors.As(err, &merr):
		writeJSON(w, http.StatusBadRequest, errorBody{Error: "invalid_request", Message: merr.Message, Field: merr.Field})
	case errors.As(err, &lerr):
		writeJSON(w, http.StatusBadRequest, errorBody{Error: "invalid_request", Message: lerr.Message, Field: lerr.Field})
	case errors.As(err, &serr):
		writeJSON(w, http.StatusBadRequest, errorBody{Error: "invalid_request", Message: serr.Message, Field: serr.Field})
	case errors.As(err, &full):
		writeJSON(w, http.StatusConflict, errorBody{Error: "space_not_empty", Message: full.Error(), Count: &full.Count})
	case errors.Is(err, spaces.ErrDefault):
		writeError(w, http.StatusConflict, "space_default", err.Error())
	case errors.Is(err, locks.ErrBadVerifier):
		writeError(w, http.StatusForbidden, "bad_verifier", "unlock locked notes first; the keyring may have been reset elsewhere")
	case errors.Is(err, locks.ErrNoPassword):
		writeError(w, http.StatusConflict, "no_lock_password", err.Error())
	case errors.Is(err, locks.ErrLastKey):
		writeError(w, http.StatusConflict, "password_key_required", err.Error())
	case errors.Is(err, locks.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "lock key not found")
	case errors.As(err, &terr):
		writeError(w, http.StatusUnsupportedMediaType, "unsupported_image", terr.Message)
	case errors.Is(err, images.ErrHashMismatch):
		writeError(w, http.StatusBadRequest, "hash_mismatch", err.Error())
	case errors.Is(err, images.ErrBadID):
		writeJSON(w, http.StatusBadRequest, errorBody{Error: "invalid_request", Message: err.Error(), Field: "id"})
	case errors.Is(err, images.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "image not found")
	case errors.Is(err, mail.ErrNotConfigured):
		writeError(w, http.StatusConflict, "email_not_configured", "add a Resend API key in Settings to send email")
	case errors.As(err, &send):
		s.log.WarnContext(r.Context(), "email failed", "status", send.Status, "error", send.Message)
		writeError(w, http.StatusBadGateway, "email_failed", send.Message)
	case errors.As(err, &conflict):
		cur := conflict.Current
		writeJSON(w, http.StatusConflict, errorBody{Error: conflict.Code, Current: &cur})
	case errors.Is(err, notes.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "note not found")
	case errors.Is(err, notes.ErrOperationReuse):
		writeError(w, http.StatusUnprocessableEntity, "operation_id_reused", err.Error())
	case errors.Is(err, notesync.ErrCursorAhead):
		writeError(w, http.StatusConflict, "cursor_ahead",
			"the cursor is ahead of this server; run a full reconciliation")
	case errors.Is(err, auth.ErrUnauthenticated):
		writeError(w, http.StatusUnauthorized, "unauthenticated", "authentication required")
	case errors.Is(err, auth.ErrInvalidCredentials):
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "invalid username or password")
	case errors.Is(err, auth.ErrUsernameTaken):
		writeError(w, http.StatusConflict, "username_taken", "that username is taken")
	case errors.Is(err, auth.ErrOwnerExists):
		writeError(w, http.StatusConflict, "already_set_up", "the owner account already exists")
	case errors.Is(err, auth.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "session not found")
	case errors.Is(err, r.Context().Err()) && r.Context().Err() != nil:
		// The client went away; nobody will read this.
		writeError(w, 499, "client_closed_request", "")
	default:
		s.log.ErrorContext(r.Context(), "request failed",
			"method", r.Method, "path", r.URL.Path, "error", err)
		writeError(w, http.StatusInternalServerError, "internal_error", "internal server error")
	}
}

// queryInt parses an optional non-negative integer query parameter.
func queryInt(r *http.Request, name string, def int64) (int64, error) {
	v := r.URL.Query().Get(name)
	if v == "" {
		return def, nil
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil || n < 0 {
		return 0, &notes.ValidationError{Field: name, Message: fmt.Sprintf("%s must be a non-negative integer", name)}
	}
	return n, nil
}

// queryFilter reads one of ?tag= (a tag name, with or without #),
// ?untagged=true or ?locked=true, and with it ?space= or ?exceptSpace=
// (a space id).
func queryFilter(r *http.Request) (search.Filter, error) {
	q := r.URL.Query()
	untagged := q.Get("untagged") == "true"
	locked := q.Get("locked") == "true"
	tag := q.Get("tag")
	space, except := q.Get("space"), q.Get("exceptSpace")
	if space != "" && except != "" {
		return search.Filter{}, &notes.ValidationError{Field: "exceptSpace", Message: "exceptSpace can't be combined with space"}
	}
	if (space != "" || except != "") && !idPattern.MatchString(space+except) {
		return search.Filter{}, &notes.ValidationError{Field: "space", Message: "must be a space id"}
	}
	if untagged && locked {
		return search.Filter{}, &notes.ValidationError{Field: "locked", Message: "locked can't be combined with untagged"}
	}
	f := search.Filter{Untagged: untagged, Locked: locked, Space: space, ExceptSpace: except}
	if tag == "" {
		return f, nil
	}
	key := search.TagKey(tag)
	if key == "" || untagged || locked {
		return search.Filter{}, &notes.ValidationError{Field: "tag", Message: "tag must be a tag name, without untagged or locked"}
	}
	f.Tag = key
	return f, nil
}

var idPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)
