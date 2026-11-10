package editors_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"testing"
	"time"

	"notes/server/internal/agent"
	"notes/server/internal/editors"
)

var ctx = context.Background()

// tab is a connection whose editor answers with answer, or not at all.
type tab struct {
	c      *editors.Conn
	asked  []map[string]any
	answer func(msg map[string]any) map[string]any
}

func connect(t *testing.T, h *editors.Hub, answer func(map[string]any) map[string]any) *tab {
	t.Helper()
	tb := &tab{answer: answer}
	c, err := h.Add(1, func(_ context.Context, msg any) error {
		m := msg.(map[string]any)
		tb.asked = append(tb.asked, m)
		if tb.answer != nil {
			reply := tb.answer(m)
			b, _ := json.Marshal(reply)
			go h.Answer(tb.c, m["id"].(string), b)
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	tb.c = c
	return tb
}

func showing(content string) func(map[string]any) map[string]any {
	return func(map[string]any) map[string]any {
		return map[string]any{"spaceId": "work", "title": "T", "content": content}
	}
}

func TestReadsFromTheRightEditor(t *testing.T) {
	h := editors.NewHub()
	ed := h.For(1)
	if _, err := ed.Read(ctx, "n1", ""); !errors.Is(err, agent.ErrNotOpen) {
		t.Fatalf("nobody has it: %v", err)
	}
	a := connect(t, h, showing("from a"))
	b := connect(t, h, showing("from b"))
	h.Opened(b.c, "n1")
	time.Sleep(time.Millisecond)
	h.Opened(a.c, "n1")
	if n, err := ed.Read(ctx, "n1", ""); err != nil || n.Content != "from a" {
		t.Fatalf("the latest to open it: %+v %v", n, err)
	}
	if n, err := ed.Read(ctx, "n1", b.c.ID); err != nil || n.Content != "from b" {
		t.Fatalf("the run's own: %+v %v", n, err)
	}
	if h.Closed(a.c, "n1") {
		t.Fatal("b still has it open")
	}
	if !h.Closed(b.c, "n1") {
		t.Fatal("nobody has it open now")
	}
	if h.Closed(b.c, "n1") || h.Closed(a.c, "n2") {
		t.Fatal("closed by an editor that didn't have it open")
	}
	if _, err := ed.Read(ctx, "n1", ""); !errors.Is(err, agent.ErrNotOpen) {
		t.Fatalf("closed everywhere: %v", err)
	}
}

func TestEditorAnswers(t *testing.T) {
	h := editors.NewHub()
	ed := h.For(1)
	tb := connect(t, h, func(m map[string]any) map[string]any {
		if m["type"] == "apply" {
			return map[string]any{"error": "changed"}
		}
		return map[string]any{"error": "locked"}
	})
	h.Opened(tb.c, "n1")
	if _, err := ed.Read(ctx, "n1", ""); !errors.Is(err, agent.ErrNotOpen) {
		t.Fatalf("a refusal reads from the store instead: %v", err)
	}
	ch := agent.EditorChange{RunID: "run_1", Before: agent.Fields{Content: "a"}, After: agent.Fields{Content: "b"}}
	if _, err := ed.Apply(ctx, "n1", "", ch); !errors.Is(err, agent.ErrEditorChanged) {
		t.Fatalf("apply: %v", err)
	}
	if got := tb.asked[1]; got["runId"] != "run_1" || got["noteId"] != "n1" || got["type"] != "apply" {
		t.Fatalf("sent: %v", got)
	}
}

func TestSilentOrClosedEditorIsGone(t *testing.T) {
	defer func(d time.Duration) { editors.CallTimeout = d }(editors.CallTimeout)
	editors.CallTimeout = 50 * time.Millisecond
	h := editors.NewHub()
	ed := h.For(1)
	silent := connect(t, h, nil)
	h.Opened(silent.c, "n1")
	if _, err := ed.Read(ctx, "n1", ""); !errors.Is(err, agent.ErrEditorGone) {
		t.Fatalf("no answer: %v", err)
	}
	editors.CallTimeout = time.Minute
	done := make(chan error)
	go func() { _, err := ed.Read(ctx, "n1", ""); done <- err }()
	time.Sleep(10 * time.Millisecond)
	h.Remove(silent.c)
	if err := <-done; !errors.Is(err, agent.ErrEditorGone) {
		t.Fatalf("disconnected: %v", err)
	}
}

func TestConnectionsAreBounded(t *testing.T) {
	h := editors.NewHub()
	for range editors.MaxConns {
		connect(t, h, nil)
	}
	if _, err := h.Add(1, nil); !errors.Is(err, editors.ErrTooMany) {
		t.Fatalf("one more: %v", err)
	}
	if _, err := h.Add(2, nil); err != nil {
		t.Fatalf("another account: %v", err)
	}
}

func TestAConnectionOpensSoManyNotes(t *testing.T) {
	h := editors.NewHub()
	a := connect(t, h, showing("x"))
	for i := range editors.MaxOpen + 10 {
		h.Opened(a.c, fmt.Sprintf("n%d", i))
	}
	if _, err := h.For(1).Read(ctx, fmt.Sprintf("n%d", editors.MaxOpen), ""); !errors.Is(err, agent.ErrNotOpen) {
		t.Fatalf("past the limit: %v", err)
	}
	if _, err := h.For(1).Read(ctx, "n0", ""); err != nil {
		t.Fatalf("within it: %v", err)
	}
}

func TestARunsOwnEndIsntTheEditorGoing(t *testing.T) {
	h := editors.NewHub()
	a := connect(t, h, nil) // never answers
	h.Opened(a.c, "n1")
	stopped, cancel := context.WithCancel(ctx)
	cancel()
	if _, err := h.For(1).Read(stopped, "n1", ""); !errors.Is(err, context.Canceled) {
		t.Fatalf("got %v", err)
	}
}
