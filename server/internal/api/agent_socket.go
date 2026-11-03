package api

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"notes/server/internal/auth"
)

// SocketProtocol is the agent socket's message format version.
const SocketProtocol = 1

// SocketPing is how often the server pings each socket: under the 60-second
// idle timeout of common proxies (nginx's proxy_read_timeout).
var SocketPing = 25 * time.Second

// socketIn is a message from an editor.
type socketIn struct {
	Type   string `json:"type"`
	ID     string `json:"id"` // on a result, the request's
	NoteID string `json:"noteId"`
	// Revision is, on editor.closed, the server's revision of what the
	// editor last showed; absent when it doesn't know (offline, a conflict).
	Revision *int64 `json:"revision"`
}

// agentSocket is the editors' connection for agents (architecture §7.8): an
// editor says which notes it has open, and closing one ends its writing
// session at once; runs read and change the notes it has open.
func (s *Server) agentSocket(w http.ResponseWriter, r *http.Request) {
	// A cross-site page can open a WebSocket with the user's cookie and no
	// preflight, so a browser session must show it came from this origin.
	if sessionFrom(r.Context()).Kind == auth.KindWeb && (r.Header.Get("Origin") == "" || !sameOrigin(r)) {
		writeError(w, http.StatusForbidden, "csrf_failed", "cross-origin request rejected")
		return
	}
	data, userID := userFrom(r.Context()), sessionFrom(r.Context()).UserID
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
	go s.pingSocket(ctx, cancel, c)

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
		if m.NoteID == "" || len(m.NoteID) > 64 {
			continue
		}
		switch m.Type {
		case "editor.opened":
			s.editors.Opened(ec, m.NoteID)
		case "editor.closed":
			if !s.editors.Closed(ec, m.NoteID) || m.Revision == nil {
				continue
			}
			queued, err := data.Agent.EditorClosed(ctx, m.NoteID, *m.Revision)
			if err != nil {
				s.log.Error("agent socket: end writing session", "error", err)
			}
			if queued && s.cfg.OnRunQueued != nil {
				s.cfg.OnRunQueued(userID)
			}
		}
		// Unknown types are skipped: a newer app may send more.
	}
}

// pingSocket keeps the connection alive through proxies, and ends it when
// the other side stops answering.
func (s *Server) pingSocket(ctx context.Context, cancel func(), c *websocket.Conn) {
	t := time.NewTicker(SocketPing)
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
	}
}
