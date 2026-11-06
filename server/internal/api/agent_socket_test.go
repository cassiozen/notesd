package api_test

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"notes/server/internal/agent"
	"notes/server/internal/api"
	"notes/server/internal/editors"
)

// Bounds every socket call, so a missed reply fails the test instead of hanging it.
var ctx, _ = context.WithTimeout(context.Background(), time.Minute) //nolint:govet // lives for the test binary

// dialSocket connects; status is the refusal's, when there is one.
func (e *env) dialSocket(c *client, origin string) (conn *websocket.Conn, status int, err error) {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	h := http.Header{}
	if origin != "" {
		h.Set("Origin", origin)
	}
	if c.bearer != "" {
		h.Set("Authorization", "Bearer "+c.bearer)
	}
	conn, res, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(e.srv.URL, "http")+"/api/v1/agent/socket",
		&websocket.DialOptions{HTTPClient: c.hc, HTTPHeader: h})
	if res != nil {
		status = res.StatusCode
		if res.Body != nil {
			_ = res.Body.Close()
		}
	}
	return conn, status, err
}

// socket connects and reads the server's hello, then keeps reading so
// pings are answered.
func (e *env) socket(c *client) (*websocket.Conn, string) {
	e.t.Helper()
	conn, id := e.editorSocket(c)
	conn.CloseRead(ctx)
	return conn, id
}

// editorSocket connects and reads the hello; the test reads what follows.
func (e *env) editorSocket(c *client) (*websocket.Conn, string) {
	e.t.Helper()
	origin := e.srv.URL
	if c.bearer != "" {
		origin = ""
	}
	conn, _, err := e.dialSocket(c, origin)
	if err != nil {
		e.t.Fatal(err)
	}
	e.t.Cleanup(func() { _ = conn.CloseNow() })
	var hello struct {
		Type         string `json:"type"`
		ConnectionID string `json:"connectionId"`
		Protocol     int    `json:"protocol"`
	}
	if err := wsjson.Read(ctx, conn, &hello); err != nil || hello.Type != "hello" || hello.ConnectionID == "" || hello.Protocol != api.SocketProtocol {
		e.t.Fatalf("hello: %+v %v", hello, err)
	}
	return conn, hello.ConnectionID
}

func TestAgentSocketWhoMayConnect(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	web := e.webClient()
	device, _ := e.deviceClient()
	e.socket(web)
	e.socket(device)
	for _, tc := range []struct {
		name   string
		c      *client
		origin string
		status int
	}{
		{"web without Origin", web, "", 403},
		{"web from another site", web, "https://evil.example", 403},
		{"signed out", e.client(), e.srv.URL, 401},
	} {
		if _, status, err := e.dialSocket(tc.c, tc.origin); err == nil || status != tc.status {
			t.Errorf("%s: %d %v", tc.name, status, err)
		}
	}
}

func TestAgentSocketOutlivesTheWriteTimeout(t *testing.T) {
	// As main's server, if shorter; long enough for sign-in under -race.
	const timeout = 2 * time.Second
	e := newEnvServer(t, api.Config{}, func(s *http.Server) { s.WriteTimeout = timeout })
	e.setupOwner()
	conn, _ := e.socket(e.webClient())
	time.Sleep(timeout + 200*time.Millisecond)
	// An unknown message is skipped, and the connection still answers.
	if err := wsjson.Write(ctx, conn, map[string]any{"type": "later.feature", "noteId": "n1"}); err != nil {
		t.Fatal(err)
	}
	if err := conn.Ping(ctx); err != nil {
		t.Fatal(err)
	}
}

func TestSigningOutClosesTheSocket(t *testing.T) {
	defer func(d time.Duration) { api.SocketPing = d }(api.SocketPing)
	api.SocketPing = 20 * time.Millisecond
	e := newEnv(t)
	e.setupOwner()
	c := e.webClient()
	conn, _ := e.editorSocket(c)
	if r := c.do("POST", "/api/v1/auth/logout", nil); r.status != 204 {
		t.Fatalf("logout: %d", r.status)
	}
	var m map[string]any
	if err := wsjson.Read(ctx, conn, &m); websocket.CloseStatus(err) != websocket.StatusPolicyViolation {
		t.Fatalf("still open: %v %v", m, err)
	}
}

func TestAnExpiredSessionClosesTheSocket(t *testing.T) {
	defer func(d time.Duration) { api.SocketPing = d }(api.SocketPing)
	api.SocketPing = 20 * time.Millisecond
	e := newEnv(t)
	e.setupOwner()
	c := e.webClient()
	conn, _ := e.editorSocket(c)
	// Its row stays until the next sign-in clears it.
	e.auth.SetClock(func() time.Time { return time.Now().Add(400 * 24 * time.Hour) })
	var m map[string]any
	if err := wsjson.Read(ctx, conn, &m); websocket.CloseStatus(err) != websocket.StatusPolicyViolation {
		t.Fatalf("still open: %v %v", m, err)
	}
}

func TestClosingTheEditorEndsTheWritingSession(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	var woken atomic.Int32
	e.users.OnAgentScheduled(func(int64) { woken.Add(1) })
	c := e.webClient()
	if r := c.do("PUT", "/api/v1/agent/spaces/default", map[string]any{
		"enabled": true, "prompt": "p", "triggers": map[string]any{"newNoteDone": "Add tags."},
	}); r.status != 200 {
		t.Fatalf("agent: %d %s", r.status, r.raw)
	}
	queued := func() int {
		r := c.do("GET", "/api/v1/agent/runs", nil)
		return len(r.body["runs"].([]any))
	}
	tab1, _ := e.socket(c)
	tab2, _ := e.socket(c)
	send := func(conn *websocket.Conn, typ string, rev any) {
		t.Helper()
		if err := wsjson.Write(ctx, conn, map[string]any{"type": typ, "noteId": "n1", "revision": rev}); err != nil {
			t.Fatal(err)
		}
	}
	// Messages are handled in order; a ping round trip waits for them.
	settle := func(conn *websocket.Conn) {
		t.Helper()
		if err := conn.Ping(ctx); err != nil {
			t.Fatal(err)
		}
	}
	if r := c.do("POST", "/api/v1/notes", map[string]any{"operationId": "o1", "id": "n1", "title": "T", "content": "a"}); r.status != 201 {
		t.Fatalf("create: %d %s", r.status, r.raw)
	}
	send(tab1, "editor.opened", nil)
	send(tab2, "editor.opened", nil)
	settle(tab1)
	settle(tab2)

	// Still open in the other tab; then a save the server hasn't had yet.
	send(tab1, "editor.closed", 1)
	settle(tab1)
	send(tab2, "editor.closed", 2)
	settle(tab2)
	if n := queued(); n != 0 {
		t.Fatalf("closed too soon: %d runs", n)
	}

	woken.Store(0) // the note's session starting
	send(tab2, "editor.opened", nil)
	send(tab2, "editor.closed", 1)
	settle(tab2)
	if n := queued(); n != 1 || woken.Load() != 1 {
		t.Fatalf("runs %d, woken %d", n, woken.Load())
	}
}

func TestRunsReadThroughTheSocket(t *testing.T) {
	hub := editors.NewHub()
	e := newEnvWith(t, api.Config{Editors: hub})
	e.setupOwner()
	conn, connID := e.editorSocket(e.webClient())
	if err := wsjson.Write(ctx, conn, map[string]any{"type": "editor.opened", "noteId": "n1"}); err != nil {
		t.Fatal(err)
	}
	// The editor answers the one request it gets.
	go func() {
		var req map[string]any
		if err := wsjson.Read(ctx, conn, &req); err != nil || req["type"] != "read" || req["noteId"] != "n1" {
			t.Errorf("request: %v %v", req, err)
			return
		}
		_ = wsjson.Write(ctx, conn, map[string]any{"type": "result", "id": req["id"], "spaceId": "default",
			"title": "Draft", "content": "Typed, not saved", "cursor": 5, "revision": nil})
	}()
	var n agent.EditorNote
	var err error
	// editor.opened may not have reached the hub yet.
	for range 100 {
		if n, err = hub.For(1).Read(ctx, "n1", connID); !errors.Is(err, agent.ErrNotOpen) {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if err != nil || n.Content != "Typed, not saved" || n.Cursor == nil || *n.Cursor != 5 || n.Revision != nil {
		t.Fatalf("read: %+v %v", n, err)
	}
}
