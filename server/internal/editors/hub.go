// Package editors keeps track of the note editors connected over the agent
// socket: which accounts have them, and which notes each has open.
package editors

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"sync"
	"time"
)

// MaxConns bounds one account's connections: a tab or an app each.
const MaxConns = 50

// ErrTooMany means the account has MaxConns connections already.
var ErrTooMany = errors.New("too many editor connections")

// Hub is every account's connected editors.
type Hub struct {
	mu    sync.Mutex
	users map[int64]map[*Conn]struct{}
	now   func() time.Time
}

// Conn is one connection: a browser tab or an app.
type Conn struct {
	ID     string
	UserID int64
	open   map[string]time.Time // note id → when the editor opened it; under Hub.mu
}

// NewHub returns an empty hub.
func NewHub() *Hub {
	return &Hub{users: map[int64]map[*Conn]struct{}{}, now: time.Now}
}

// Add registers a new connection for the user.
func (h *Hub) Add(userID int64) (*Conn, error) {
	var b [12]byte
	_, _ = rand.Read(b[:])
	c := &Conn{ID: "c_" + hex.EncodeToString(b[:]), UserID: userID, open: map[string]time.Time{}}
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
	return c, nil
}

// Remove forgets a connection that ended, with the notes it had open.
func (h *Hub) Remove(c *Conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	delete(h.users[c.UserID], c)
	if len(h.users[c.UserID]) == 0 {
		delete(h.users, c.UserID)
	}
}

// Opened records that c's editor opened the note.
func (h *Hub) Opened(c *Conn, noteID string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	c.open[noteID] = h.now()
}

// Closed records that c's editor closed the note, and reports whether it
// was the last editor anywhere with it open.
func (h *Hub) Closed(c *Conn, noteID string) (last bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	delete(c.open, noteID)
	for other := range h.users[c.UserID] {
		if _, ok := other.open[noteID]; ok {
			return false
		}
	}
	return true
}
