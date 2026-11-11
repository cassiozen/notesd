package api_test

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"notes/server/internal/agent"
	"notes/server/internal/api"
	"notes/server/internal/editors"
)

// dialSocket connects; status is the refusal's, when there is one.
func (e *env) dialSocket(c *client, origin string) (conn *websocket.Conn, status int, err error) {
	ctx := e.ctx
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
	ctx := e.ctx
	e.t.Helper()
	conn, id := e.editorSocket(c)
	conn.CloseRead(ctx)
	return conn, id
}

// editorSocket connects and reads the hello; the test reads what follows.
func (e *env) editorSocket(c *client) (*websocket.Conn, string) {
	ctx := e.ctx
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
	ctx := e.ctx
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
	e := newEnvWith(t, api.Config{SocketPing: 20 * time.Millisecond})
	ctx := e.ctx
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
	e := newEnvWith(t, api.Config{SocketPing: 20 * time.Millisecond})
	ctx := e.ctx
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
	ctx := e.ctx
	e.setupOwner()
	var woken atomic.Int32
	e.users.OnAgentScheduled(func(agent.Home) { woken.Add(1) })
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
	ctx := e.ctx
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

func TestRequestAgentRun(t *testing.T) {
	e := newEnv(t)
	ctx := e.ctx
	e.setupOwner()
	var woken atomic.Int32
	e.users.OnAgentScheduled(func(agent.Home) { woken.Add(1) })
	c := e.webClient()
	if r := c.do("PUT", "/api/v1/agent/spaces/default", map[string]any{
		"enabled": true, "prompt": "p", "triggers": map[string]any{"noteCreated": "Ask a question."},
	}); r.status != 200 {
		t.Fatalf("agent: %d %s", r.status, r.raw)
	}
	if r := c.do("POST", "/api/v1/notes", map[string]any{"operationId": "o1", "id": "n1", "title": "T"}); r.status != 201 {
		t.Fatalf("create: %d %s", r.status, r.raw)
	}
	conn, _ := e.socket(c)
	ask := func(m map[string]any) {
		t.Helper()
		m["type"] = "run.request"
		if err := wsjson.Write(ctx, conn, m); err != nil {
			t.Fatal(err)
		}
		// Messages are handled in order; a ping round trip waits for them.
		if err := conn.Ping(ctx); err != nil {
			t.Fatal(err)
		}
	}
	runs := func() []any {
		r := c.do("GET", "/api/v1/agent/runs", nil)
		return r.body["runs"].([]any)
	}
	// Turned down: the space's agent doesn't act on it, not an editor's trigger, no such note.
	ask(map[string]any{"trigger": "editStarted", "noteId": "n1"})
	ask(map[string]any{"trigger": "noteDeleted", "noteId": "n1"})
	ask(map[string]any{"trigger": "editStarted", "noteId": "nope"})
	if n := len(runs()); n != 0 || woken.Load() != 0 {
		t.Fatalf("refused: %d runs, woken %d", n, woken.Load())
	}
	// A draft the server hasn't got, with what its editor shows.
	ask(map[string]any{"trigger": "noteCreated", "noteId": "draft", "spaceId": "default",
		"note": map[string]any{"spaceId": "default", "title": "", "content": "", "cursor": 0}})
	if r := runs(); len(r) != 1 || r[0].(map[string]any)["trigger"] != "noteCreated" || woken.Load() != 1 {
		t.Fatalf("draft: %v, woken %d", r, woken.Load())
	}
}

func TestOverlaysThroughTheSocket(t *testing.T) {
	e := newEnv(t)
	ctx := e.ctx
	e.setupOwner()
	c := e.webClient()
	if r := c.do("PUT", "/api/v1/agent/spaces/default", map[string]any{
		"enabled": true, "prompt": "p", "triggers": map[string]any{"noteCreated": "Ask a question."},
	}); r.status != 200 {
		t.Fatalf("agent: %d %s", r.status, r.raw)
	}
	if r := c.do("POST", "/api/v1/notes", map[string]any{"operationId": "o1", "id": "n1", "title": "T", "content": "Mlik"}); r.status != 201 {
		t.Fatalf("create: %d %s", r.status, r.raw)
	}
	d, err := e.users.Open(ctx, 1)
	if err != nil {
		t.Fatal(err)
	}
	runID, err := d.Agent.RequestRun(ctx, agent.RunRequest{Trigger: agent.NoteCreated, NoteID: "n1", SpaceID: "default"})
	if err != nil {
		t.Fatal(err)
	}
	// As a run's show_card and suggest leave them.
	if err := d.DB.WriteTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `INSERT INTO agent_overlays (id, run_id, note_id, kind, place, body, change, created_at) VALUES
			('ov_card', ?, 'n1', 'card', '{"at":"top"}', 'Hi', NULL, 1),
			('ov_fix', ?, 'n1', 'suggestion', '{"after":"Mlik"}', 'Typo', '{"kind":"replace","find":"Mlik","replace":"Milk"}', 1)`, runID, runID)
		return err
	}); err != nil {
		t.Fatal(err)
	}

	type shown struct {
		Type     string          `json:"type"`
		NoteID   string          `json:"noteId"`
		Overlays []agent.Overlay `json:"overlays"`
	}
	read := func(conn *websocket.Conn) shown {
		t.Helper()
		var m shown
		if err := wsjson.Read(ctx, conn, &m); err != nil || m.Type != "overlays" || m.NoteID != "n1" {
			t.Fatalf("read: %+v %v", m, err)
		}
		return m
	}
	send := func(conn *websocket.Conn, m map[string]any) {
		t.Helper()
		m["noteId"] = "n1"
		if err := wsjson.Write(ctx, conn, m); err != nil {
			t.Fatal(err)
		}
	}
	tab1, _ := e.editorSocket(c)
	tab2, _ := e.editorSocket(c)

	// Opening the note shows what's open on it.
	send(tab1, map[string]any{"type": "editor.opened"})
	if m := read(tab1); len(m.Overlays) != 2 || m.Overlays[1].Change == nil || m.Overlays[1].Change.Find != "Mlik" {
		t.Fatalf("on open: %+v", m)
	}
	send(tab2, map[string]any{"type": "editor.opened"})
	read(tab2)

	// An answer from one tab reaches both. A wrong one changes nothing.
	send(tab1, map[string]any{"type": "overlay.answer", "id": "ov_card", "answer": "accept"})
	send(tab1, map[string]any{"type": "overlay.answer", "id": "ov_card", "answer": "dismiss"})
	for _, tab := range []*websocket.Conn{tab1, tab2} {
		if m := read(tab); len(m.Overlays) != 1 || m.Overlays[0].ID != "ov_fix" {
			t.Fatalf("after dismiss: %+v", m)
		}
	}
	send(tab2, map[string]any{"type": "overlay.answer", "id": "ov_fix", "answer": "accept", "revision": 1,
		"before": map[string]any{"title": "T", "content": "Mlik"}, "shows": map[string]any{"title": "T", "content": "Milk"}})
	for _, tab := range []*websocket.Conn{tab1, tab2} {
		if m := read(tab); len(m.Overlays) != 0 {
			t.Fatalf("after accept: %+v", m)
		}
	}
	r, err := d.Agent.Run(ctx, runID)
	if err != nil || len(r.Edits) != 1 || r.Overlays == nil || r.Overlays.Accepted != 1 {
		t.Fatalf("run: %+v %v", r, err)
	}

	// Whatever's open closes with the note's last editor.
	if err := d.DB.WriteTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `INSERT INTO agent_overlays (id, run_id, note_id, kind, place, body, created_at)
			VALUES ('ov_2', ?, 'n1', 'card', '{"at":"top"}', 'Again', 2)`, runID)
		return err
	}); err != nil {
		t.Fatal(err)
	}
	send(tab1, map[string]any{"type": "editor.closed"})
	send(tab2, map[string]any{"type": "editor.closed"})
	for _, tab := range []*websocket.Conn{tab1, tab2} {
		if m := read(tab); len(m.Overlays) != 0 {
			t.Fatalf("after close: %+v", m)
		}
	}
	if list, err := d.Agent.OpenOverlays(ctx, "n1"); err != nil || len(list) != 0 {
		t.Fatalf("still open: %+v %v", list, err)
	}
}

// In a shared space the socket works on the space's file, and every member's editors hear of it (§7.11).
func TestSocketInASharedSpace(t *testing.T) {
	s := newSharedEnv(t)
	ctx := s.ctx
	s.share()
	var mu sync.Mutex
	var woken []agent.Home
	s.users.OnAgentScheduled(func(h agent.Home) { mu.Lock(); woken = append(woken, h); mu.Unlock() })
	if r := s.kid.do("PUT", "/api/v1/agent/spaces/fam", map[string]any{
		"enabled": true, "prompt": "p", "triggers": map[string]any{"noteCreated": "Ask a question."},
	}); r.status != 200 {
		t.Fatalf("agent: %d %s", r.status, r.raw)
	}
	kid, _ := s.editorSocket(s.kid)
	owner, _ := s.editorSocket(s.owner)
	send := func(m map[string]any) {
		t.Helper()
		if err := wsjson.Write(ctx, kid, m); err != nil {
			t.Fatal(err)
		}
	}

	// The kid's editor asks: a run in the space's file, for the kid, woken in that file's worker.
	send(map[string]any{"type": "run.request", "trigger": "noteCreated", "noteId": "draft", "spaceId": "fam",
		"note": map[string]any{"spaceId": "fam", "title": "", "content": "", "cursor": 0}})
	var runs []any
	deadline := time.Now().Add(5 * time.Second)
	for len(runs) == 0 && time.Now().Before(deadline) {
		runs, _ = s.owner.do("GET", "/api/v1/agent/runs?space=fam", nil).body["runs"].([]any)
	}
	if len(runs) != 1 || runs[0].(map[string]any)["forUserId"] != float64(s.kidID) {
		t.Fatalf("runs: %v", runs)
	}
	mu.Lock()
	if !slices.Contains(woken, agent.SpaceHome("fam")) {
		t.Fatalf("woken: %v", woken)
	}
	mu.Unlock()

	// A card shown on a note there: once the last editor closes the note, both members' editors hear it went.
	fam, err := s.users.OpenSpace(ctx, "fam")
	if err != nil {
		t.Fatal(err)
	}
	runID := runs[0].(map[string]any)["id"].(string)
	if _, err := fam.DB.W.ExecContext(ctx, `INSERT INTO agent_overlays (id, run_id, note_id, kind, place, body, created_at)
		VALUES ('ov1', ?, 'groceries', 'card', '{"at":"top"}', 'Hi', 1)`, runID); err != nil {
		t.Fatal(err)
	}
	send(map[string]any{"type": "editor.opened", "noteId": "groceries"})
	send(map[string]any{"type": "editor.closed", "noteId": "groceries"})
	for {
		var m struct {
			Type     string `json:"type"`
			NoteID   string `json:"noteId"`
			Overlays []any  `json:"overlays"`
		}
		read, cancel := context.WithTimeout(ctx, 5*time.Second)
		err := wsjson.Read(read, owner, &m)
		cancel()
		if err != nil {
			t.Fatalf("the owner's editor heard nothing: %v", err)
		}
		if m.Type == "overlays" && m.NoteID == "groceries" && len(m.Overlays) == 0 {
			break
		}
	}
}

// fakeAhead hears what the socket asks of results done ahead.
type fakeAhead struct {
	mu       sync.Mutex
	prepared []string
	used     []string
	gone     string // the id it no longer has
}

func (f *fakeAhead) Prepare(home agent.Home, spaceID string, userID int64) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.prepared = append(f.prepared, string(home)+"/"+spaceID)
}

func (f *fakeAhead) UsePrepared(_ context.Context, home agent.Home, id, noteID, spaceID string, _ int64) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.used = append(f.used, string(home)+"/"+spaceID+"/"+id+"/"+noteID)
	if id == f.gone {
		return agent.ErrPreparedGone
	}
	return nil
}

func TestPreparedThroughTheSocket(t *testing.T) {
	ahead := &fakeAhead{gone: "run_2"}
	e := newEnvWith(t, api.Config{Ahead: ahead})
	ctx := e.ctx
	e.setupOwner()
	conn, _ := e.editorSocket(e.webClient())
	send := func(m map[string]any) {
		t.Helper()
		if err := wsjson.Write(ctx, conn, m); err != nil {
			t.Fatal(err)
		}
	}
	send(map[string]any{"type": "prepare", "spaceId": "default"})
	send(map[string]any{"type": "prepare", "spaceId": "../bad"})
	send(map[string]any{"type": "run.request", "trigger": "noteCreated", "noteId": "n1", "spaceId": "default", "prepared": "run_1"})
	send(map[string]any{"type": "run.request", "trigger": "noteCreated", "noteId": "n2", "spaceId": "default", "prepared": "run_2"})
	var missed map[string]string
	if err := wsjson.Read(ctx, conn, &missed); err != nil {
		t.Fatal(err)
	}
	if missed["type"] != "prepared.missed" || missed["noteId"] != "n2" || missed["id"] != "run_2" {
		t.Fatalf("missed: %v", missed)
	}
	ahead.mu.Lock()
	defer ahead.mu.Unlock()
	if want := []string{"user:1/default"}; !slices.Equal(ahead.prepared, want) {
		t.Fatalf("prepared %v", ahead.prepared)
	}
	if want := []string{"user:1/default/run_1/n1", "user:1/default/run_2/n2"}; !slices.Equal(ahead.used, want) {
		t.Fatalf("used %v", ahead.used)
	}
}
