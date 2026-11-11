package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"notes/server/internal/agent"
	"notes/server/internal/auth"
	"notes/server/internal/editors"
	"notes/server/internal/sharing"
	"notes/server/internal/userdata"
)

// noteIDPattern is a note id, as the notes API takes one.
var noteIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

// SocketProtocol is the agent socket's message format version.
const SocketProtocol = 1

// socketPing is how often the server pings each socket, unless
// Config.SocketPing says otherwise: under the 60-second idle timeouts of
// common proxies (nginx's proxy_read_timeout).
const socketPing = 25 * time.Second

// socketIn is a message from an editor.
type socketIn struct {
	Type   string `json:"type"`
	ID     string `json:"id"` // on a result, the request's
	NoteID string `json:"noteId"`
	// Revision is, on editor.closed, the server's revision of what the
	// editor last showed; absent when it doesn't know (offline, a conflict).
	Revision *int64 `json:"revision"`
	// On run.request: the trigger, the note's space, and what the editor
	// shows, so the run needn't ask it first.
	Trigger agent.Trigger     `json:"trigger"`
	SpaceID string            `json:"spaceId"`
	Note    *agent.EditorNote `json:"note"`
	// On overlay.answer: the answer to overlay ID, and for an accepted
	// suggestion, what the editor showed before it and shows after (with
	// Revision), as an apply's result says.
	Answer string        `json:"answer"`
	Before *agent.Fields `json:"before"`
	Shows  *agent.Fields `json:"shows"`
	// On run.request for noteCreated: the result done ahead the new note opened with.
	Prepared string `json:"prepared"`
}

// agentSocket is the editors' connection for agents (architecture §7.11): an
// editor says which notes it has open, and closing one ends its writing
// session at once; runs read and change the notes it has open.
func (s *Server) agentSocket(w http.ResponseWriter, r *http.Request) {
	// A cross-site page can open a WebSocket with the user's cookie and no
	// preflight, so a browser session must show it came from this origin.
	if sessionFrom(r.Context()).Kind == auth.KindWeb && (r.Header.Get("Origin") == "" || !sameOrigin(r)) {
		writeError(w, http.StatusForbidden, "csrf_failed", "cross-origin request rejected")
		return
	}
	userID := sessionFrom(r.Context()).UserID
	if s.editors.Full(userID) {
		writeError(w, http.StatusTooManyRequests, "rate_limited", "too many open connections")
		return
	}
	// Origin checked above, with the proxy headers Accept doesn't know.
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
	if err != nil {
		return
	}
	defer func() { _ = c.CloseNow() }()
	ec, err := s.editors.Add(userID, func(ctx context.Context, msg any) error { return wsjson.Write(ctx, c, msg) })
	if err != nil {
		// Others connected meanwhile.
		_ = c.Close(websocket.StatusTryAgainLater, "too many open connections")
		return
	}
	defer s.editors.Remove(ec)
	// A read's reply carries a whole note.
	c.SetReadLimit(4 << 20)
	ctx, cancel := context.WithCancel(context.WithoutCancel(r.Context()))
	defer cancel()
	go s.pingSocket(ctx, cancel, c, sessionFrom(r.Context()).ID)

	if err := wsjson.Write(ctx, c, map[string]any{"type": "hello", "connectionId": ec.ID, "protocol": SocketProtocol}); err != nil {
		return
	}
	for {
		var raw json.RawMessage
		var m socketIn
		if err := wsjson.Read(ctx, c, &raw); err != nil {
			return
		}
		if err := json.Unmarshal(raw, &m); err != nil {
			_ = c.Close(websocket.StatusUnsupportedData, "not a JSON object")
			return
		}
		if m.Type == "result" {
			s.editors.Answer(ec, m.ID, raw)
			continue
		}
		if m.Type == "prepare" {
			s.prepare(ctx, userID, m.SpaceID)
			continue
		}
		if !noteIDPattern.MatchString(m.NoteID) {
			continue
		}
		f, err := s.socketFile(ctx, userID, m.NoteID, m.SpaceID)
		if err != nil {
			s.log.Error("agent socket: find the note's file", "error", err)
			continue
		}
		data := f.data
		switch m.Type {
		case "editor.opened":
			// Locked through queuing: a list read before a newer one went out would undo it here.
			unlock := data.Agent.LockOverlays()
			s.editors.Opened(ec, m.NoteID)
			// Another tab's, still showing: this one shows them too.
			if list, err := data.Agent.OpenOverlays(ctx, m.NoteID); err != nil {
				s.log.Error("agent socket: overlays", "error", err)
			} else if len(list) > 0 {
				s.editors.Send(ec, agent.OverlaysMessage(m.NoteID, list))
			}
			unlock()
		case "editor.closed":
			// Locked with the check that it's the last editor, so a tab opening it meanwhile keeps them.
			unlock := data.Agent.LockOverlays()
			last := s.editors.Closed(ec, m.NoteID)
			if last {
				// The last editor closed it: nothing shows on it any more.
				if closed, err := data.Agent.CloseOverlays(ctx, m.NoteID); err != nil {
					s.log.Error("agent socket: close overlays", "error", err)
				} else if closed {
					s.editors.BroadcastTo(f.members, agent.OverlaysMessage(m.NoteID, nil))
				}
			}
			unlock()
			if !last || m.Revision == nil {
				continue
			}
			if err := data.Agent.EditorClosed(ctx, m.NoteID, *m.Revision); err != nil {
				s.log.Error("agent socket: end writing session", "error", err)
			}
		case "overlay.answer":
			var applied *agent.EditorApplied
			if m.Before != nil && m.Shows != nil {
				applied = &agent.EditorApplied{Before: *m.Before, Revision: m.Revision, Shows: *m.Shows}
			}
			s.answerOverlay(ctx, data.Agent, f.members, m.ID, m.Answer, applied)
		case "run.request":
			if m.Prepared != "" && m.Trigger == agent.NoteCreated {
				s.usePrepared(ctx, ec, f, userID, m)
				continue
			}
			// Before the run is queued, which starts it.
			s.editors.Claim(ec, m.NoteID)
			_, err := data.Agent.RequestRun(ctx, agent.RunRequest{Trigger: m.Trigger, NoteID: m.NoteID, SpaceID: m.SpaceID,
				ConnectionID: ec.ID, By: userID, Note: m.Note})
			if err != nil {
				s.editors.Unclaim(ec, m.NoteID)
			}
			if err != nil && !refusedRun(err) {
				s.log.Error("agent socket: request a run", "error", err)
			}
		}
		// Unknown types are skipped: a newer app may send more.
	}
}

// prepare has the space's "new note opened" run done ahead for this user, if it has one.
func (s *Server) prepare(ctx context.Context, userID int64, spaceID string) {
	if s.cfg.Ahead == nil || !idPattern.MatchString(spaceID) {
		return
	}
	f, err := s.socketFile(ctx, userID, "", spaceID)
	if err != nil {
		s.log.Error("agent socket: find the space's file", "error", err)
		return
	}
	s.cfg.Ahead.Prepare(f.home, spaceID, userID)
}

// usePrepared records the result done ahead a new note opened with as its run. One no longer there
// is a miss: the editor takes it out of the note, if the user hasn't typed yet, and asks for a live run.
func (s *Server) usePrepared(ctx context.Context, ec *editors.Conn, f socketFile, userID int64, m socketIn) {
	err := agent.ErrPreparedGone
	if s.cfg.Ahead != nil {
		err = s.cfg.Ahead.UsePrepared(ctx, f.home, m.Prepared, m.NoteID, m.SpaceID, userID)
	}
	if err == nil {
		return
	}
	if !errors.Is(err, agent.ErrPreparedGone) && !refusedRun(err) {
		s.log.Error("agent socket: use a result done ahead", "error", err)
	}
	s.editors.Send(ec, map[string]string{"type": "prepared.missed", "noteId": m.NoteID, "id": m.Prepared})
}

// refusedRun is a run request the space's agent or the note turns down:
// the editor asked for what its own copy of the setup allowed, which can
// be behind.
func refusedRun(err error) bool {
	var in *agent.InputError
	return errors.As(err, &in) || errors.Is(err, agent.ErrAgentOff) || errors.Is(err, agent.ErrTriggerOff) ||
		errors.Is(err, agent.ErrNoteLocked) || errors.Is(err, agent.ErrNoteGone) || errors.Is(err, agent.ErrSpaceNotFound)
}

// pingSocket keeps the connection alive through proxies, and ends it when
// the other side stops answering or its session ends: the socket was
// authenticated only when it opened, and runs keep sending it notes.
func (s *Server) pingSocket(ctx context.Context, cancel func(), c *websocket.Conn, sessionID string) {
	every := socketPing
	if s.cfg.SocketPing > 0 {
		every = s.cfg.SocketPing
	}
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		pctx, pcancel := context.WithTimeout(ctx, 10*time.Second)
		err := c.Ping(pctx)
		pcancel()
		if err != nil {
			cancel()
			return
		}
		// A failed check keeps the connection: the next tick tries again.
		if live, err := s.auth.Live(ctx, sessionID); err == nil && !live {
			_ = c.Close(websocket.StatusPolicyViolation, "signed out")
			cancel()
			return
		}
	}
}

// answerOverlay records an answer and tells the account's editors what's
// left, locked so answers from two tabs reach them in order.
func (s *Server) answerOverlay(ctx context.Context, a *agent.Store, members []int64, id, answer string, applied *agent.EditorApplied) {
	defer a.LockOverlays()()
	noteID, err := a.AnswerOverlay(ctx, id, answer, applied)
	switch {
	case errors.Is(err, agent.ErrOverlayGone), errors.Is(err, agent.ErrBadAnswer):
		return // answered elsewhere already, or not an answer it takes
	case err != nil:
		s.log.Error("agent socket: answer an overlay", "error", err)
		return
	}
	list, err := a.OpenOverlays(ctx, noteID)
	if err != nil {
		s.log.Error("agent socket: overlays", "error", err)
		return
	}
	s.editors.BroadcastTo(members, agent.OverlaysMessage(noteID, list))
}

// socketFile is the file a socket message is about, and whose editors hear of it.
type socketFile struct {
	data    *userdata.Data
	members []int64
	home    agent.Home
}

// socketFile finds the file of a message's note (§7.11): wherever the note is, else, for a new note not
// saved yet, the space's the editor names, else the account's own. In a shared space's, every member's
// editors hear of it.
func (s *Server) socketFile(ctx context.Context, userID int64, noteID, spaceID string) (socketFile, error) {
	files, release, err := s.sharing.Files(ctx, userID)
	if err != nil {
		return socketFile{}, err
	}
	// Not held through the message: a run started from it outlives it, and keeps its file open itself.
	defer release()
	f := files[0]
	if found, ok, err := sharing.FileOf(ctx, files, noteID); err != nil {
		return socketFile{}, err
	} else if ok {
		f = found
	} else if spaceID != "" {
		f = sharing.FileFor(files, spaceID)
	}
	if f.SpaceID == "" {
		return socketFile{data: f.Data, members: []int64{userID}, home: agent.UserHome(userID)}, nil
	}
	members, err := s.sharing.Members(ctx, f.SpaceID)
	return socketFile{data: f.Data, members: members, home: agent.SpaceHome(f.SpaceID)}, err
}
