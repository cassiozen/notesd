// Package editors keeps track of the note editors connected over the agent
// socket: which accounts have them, which notes each has open, and the
// requests agents send them.
package editors

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"notes/server/internal/agent"
)

// MaxConns bounds one account's connections: a tab or an app each.
const MaxConns = 50

// MaxOpen bounds the notes one connection says it has open.
const MaxOpen = 100

// CallTimeout bounds the wait for an editor to answer.
var CallTimeout = 10 * time.Second

// ErrTooMany means the account has MaxConns connections already.
var ErrTooMany = errors.New("too many editor connections")

// Hub is every account's connected editors.
type Hub struct {
	mu    sync.Mutex
	users map[int64]map[*Conn]struct{}
}

// Conn is one connection: a browser tab or an app.
type Conn struct {
	ID     string
	UserID int64
	send   func(ctx context.Context, msg any) error
	// outbox is Broadcast's messages, sent one at a time so a run's end
	// never overtakes its start.
	outbox chan any
	gone   chan struct{}
	// Under Hub.mu.
	open    map[string]time.Time // note id → when the editor opened it
	pending map[string]chan json.RawMessage
	next    int
}

// NewHub returns an empty hub.
func NewHub() *Hub {
	return &Hub{users: map[int64]map[*Conn]struct{}{}}
}

// Add registers a new connection for the user; send writes a message to it.
func (h *Hub) Add(userID int64, send func(ctx context.Context, msg any) error) (*Conn, error) {
	var b [12]byte
	_, _ = rand.Read(b[:])
	c := &Conn{
		ID: "c_" + hex.EncodeToString(b[:]), UserID: userID, send: send, outbox: make(chan any, 16), gone: make(chan struct{}),
		open: map[string]time.Time{}, pending: map[string]chan json.RawMessage{},
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	conns := h.users[userID]
	if len(conns) >= MaxConns {
		return nil, ErrTooMany
	}
	if conns == nil {
		conns = map[*Conn]struct{}{}
		h.users[userID] = conns
	}
	conns[c] = struct{}{}
	go c.deliver()
	return c, nil
}

func (c *Conn) deliver() {
	for {
		select {
		case msg := <-c.outbox:
			ctx, cancel := context.WithTimeout(context.Background(), CallTimeout)
			_ = c.send(ctx, msg)
			cancel()
		case <-c.gone:
			return
		}
	}
}

// Full reports whether the user has MaxConns connections already.
func (h *Hub) Full(userID int64) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.users[userID]) >= MaxConns
}

// Remove forgets a connection that ended, with the notes it had open; calls
// waiting on it fail with agent.ErrEditorGone.
func (h *Hub) Remove(c *Conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	delete(h.users[c.UserID], c)
	if len(h.users[c.UserID]) == 0 {
		delete(h.users, c.UserID)
	}
	close(c.gone)
}

// Opened records that c's editor opened the note.
func (h *Hub) Opened(c *Conn, noteID string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if _, ok := c.open[noteID]; !ok && len(c.open) >= MaxOpen {
		return // an editor shows a few notes, not this many
	}
	c.open[noteID] = time.Now()
}

// Closed records that c's editor closed the note, and reports whether it
// was the last editor anywhere with it open; never for a note c didn't open.
func (h *Hub) Closed(c *Conn, noteID string) (last bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if _, ok := c.open[noteID]; !ok {
		return false
	}
	delete(c.open, noteID)
	for other := range h.users[c.UserID] {
		if _, ok := other.open[noteID]; ok {
			return false
		}
	}
	return true
}

// Broadcast sends msg to each of the user's editors, without waiting:
// one that's slow or gone misses it.
func (h *Hub) Broadcast(userID int64, msg any) {
	h.mu.Lock()
	conns := make([]*Conn, 0, len(h.users[userID]))
	for c := range h.users[userID] {
		conns = append(conns, c)
	}
	h.mu.Unlock()
	for _, c := range conns {
		select {
		case c.outbox <- msg:
		default:
		}
	}
}

// Answer hands an editor's reply to the call waiting for it.
func (h *Hub) Answer(c *Conn, id string, reply json.RawMessage) {
	h.mu.Lock()
	ch, ok := c.pending[id]
	delete(c.pending, id)
	h.mu.Unlock()
	if ok {
		ch <- reply
	}
}

// owner is the editor a run talks to about a note: the connection prefer
// names if it has the note open, else the one that opened it last.
func (h *Hub) owner(userID int64, noteID, prefer string) *Conn {
	h.mu.Lock()
	defer h.mu.Unlock()
	var best *Conn
	var at time.Time
	for c := range h.users[userID] {
		opened, ok := c.open[noteID]
		switch {
		case !ok:
		case c.ID == prefer:
			return c
		case best == nil || opened.After(at):
			best, at = c, opened
		}
	}
	return best
}

// call sends a request and waits for its reply.
func (h *Hub) call(ctx context.Context, c *Conn, msg map[string]any) (json.RawMessage, error) {
	ch := make(chan json.RawMessage, 1)
	h.mu.Lock()
	c.next++
	id := fmt.Sprintf("s%d", c.next)
	c.pending[id] = ch
	h.mu.Unlock()
	defer func() {
		h.mu.Lock()
		delete(c.pending, id)
		h.mu.Unlock()
	}()
	msg["id"] = id
	callCtx, cancel := context.WithTimeout(ctx, CallTimeout)
	defer cancel()
	if err := c.send(callCtx, msg); err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, agent.ErrEditorGone
	}
	select {
	case reply := <-ch:
		return reply, nil
	case <-c.gone:
		return nil, agent.ErrEditorGone
	case <-callCtx.Done():
		// The run's own end (its deadline, a shutdown) isn't the editor's.
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		// No answer in time counts as gone: the run can't tell it apart.
		return nil, agent.ErrEditorGone
	}
}

// For is the user's editors, as agents reach them.
func (h *Hub) For(userID int64) agent.Editors { return account{h, userID} }

type account struct {
	h      *Hub
	userID int64
}

// reply is an editor's answer; Error is set when it couldn't do it.
type reply struct {
	Error string `json:"error"`
	agent.EditorNote
	// After an apply: what the editor showed just before (with Revision),
	// and what it shows now.
	Before *agent.Fields `json:"before"`
	Shows  *agent.Fields `json:"shows"`
}

func (a account) Read(ctx context.Context, noteID, prefer string) (agent.EditorNote, error) {
	c := a.h.owner(a.userID, noteID, prefer)
	if c == nil {
		return agent.EditorNote{}, agent.ErrNotOpen
	}
	r, err := a.do(ctx, c, map[string]any{"type": "read", "noteId": noteID})
	return r.EditorNote, err
}

func (a account) Apply(ctx context.Context, noteID, prefer string, ch agent.EditorChange) (agent.EditorApplied, error) {
	c := a.h.owner(a.userID, noteID, prefer)
	if c == nil {
		return agent.EditorApplied{}, agent.ErrNotOpen
	}
	r, err := a.do(ctx, c, map[string]any{"type": "apply", "noteId": noteID, "runId": ch.RunID,
		"before": ch.Before, "after": ch.After, "op": ch.Op})
	if err != nil {
		return agent.EditorApplied{}, err
	}
	applied := agent.EditorApplied{Before: ch.Before, Revision: r.Revision, Shows: ch.After}
	if r.Before != nil {
		applied.Before = *r.Before
	}
	if r.Shows != nil {
		applied.Shows = *r.Shows
	}
	return applied, nil
}

func (a account) do(ctx context.Context, c *Conn, msg map[string]any) (reply, error) {
	raw, err := a.h.call(ctx, c, msg)
	if err != nil {
		return reply{}, err
	}
	var r reply
	if err := json.Unmarshal(raw, &r); err != nil {
		return r, agent.ErrEditorGone
	}
	switch r.Error {
	case "":
		return r, nil
	case "changed":
		return r, agent.ErrEditorChanged
	default:
		// not_open, locked, or one this server doesn't know: read it from the store instead.
		return r, agent.ErrNotOpen
	}
}
