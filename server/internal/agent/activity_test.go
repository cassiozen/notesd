package agent_test

import (
	"encoding/base64"
	"errors"
	"strings"
	"testing"
	"time"

	"notes/server/internal/agent"
)

// tagged runs one "new note done" run on id that adds #tag at the end.
func (h *harness) tagged(id string) {
	h.t.Helper()
	h.done(id, "Milk")
	h.model.steps = append(h.model.steps, call("insert", `{"at": "end", "text": "#tag"}`), final("Tagged."))
	h.next()
}

func TestActivityLists(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.tagged("n1")
	h.advance(time.Second)
	h.done("n2", "Eggs")
	h.model.steps = append(h.model.steps, final("No change."))
	h.next()
	h.advance(time.Second)
	h.done("n3", "Queued")

	runs, next, err := h.d.Agent.Runs(ctx, agent.RunFilter{}, 2, "")
	if err != nil || len(runs) != 2 || next == "" {
		t.Fatalf("page 1: %+v %q %v", runs, next, err)
	}
	if runs[0].NoteID != "n3" || runs[0].Status != agent.StatusQueued || runs[1].NoteID != "n2" || runs[1].Outcome != agent.OutcomeNoChange {
		t.Fatalf("page 1 order: %+v", runs)
	}
	rest, next, err := h.d.Agent.Runs(ctx, agent.RunFilter{}, 2, next)
	if err != nil || len(rest) != 1 || next != "" {
		t.Fatalf("page 2: %+v %q %v", rest, next, err)
	}
	r := rest[0]
	if r.NoteID != "n1" || r.NoteTitle != "T" || r.Message != "Tagged." || r.ToolCalls != 1 || r.PromptTokens != 200 ||
		r.FinishedAt == nil || len(r.Edits) != 1 || !r.Edits[0].Undoable || r.Edits[0].Kind != "edit" {
		t.Fatalf("run: %+v", r)
	}

	if runs, _, err := h.d.Agent.Runs(ctx, agent.RunFilter{Note: "n2"}, 10, ""); err != nil || len(runs) != 1 || runs[0].NoteID != "n2" {
		t.Fatalf("by note: %+v %v", runs, err)
	}
	if runs, _, err := h.d.Agent.Runs(ctx, agent.RunFilter{Space: "home"}, 10, ""); err != nil || len(runs) != 0 {
		t.Fatalf("by space: %+v %v", runs, err)
	}
	var in *agent.InputError
	for _, c := range []string{"nope", base64.RawURLEncoding.EncodeToString([]byte("1.2junk")), base64.RawURLEncoding.EncodeToString([]byte("01.2")),
		base64.RawURLEncoding.EncodeToString([]byte("-1.2")), base64.RawURLEncoding.EncodeToString([]byte("5.0"))} {
		if _, _, err := h.d.Agent.Runs(ctx, agent.RunFilter{}, 10, c); !errors.As(err, &in) || in.Field != "cursor" {
			t.Fatalf("bad cursor %q: %v", c, err)
		}
	}

	u, err := h.d.Agent.UsageToday(ctx)
	if err != nil || u.Tokens != 330 || u.Runs != 2 || u.Day != "2023-11-14" {
		t.Fatalf("usage: %+v %v", u, err)
	}
}

func TestActivityDetail(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.tagged("n1")
	id := h.d.Agent
	runs, _, _ := id.Runs(ctx, agent.RunFilter{}, 1, "")
	d, err := id.Run(ctx, runs[0].ID)
	if err != nil || len(d.Diffs) != 1 {
		t.Fatalf("detail: %+v %v", d, err)
	}
	got := render(d.Diffs[0].Lines)
	if d.Diffs[0].TitleBefore != "T" || d.Diffs[0].TitleAfter != "T" || got != "= Milk\n+ \n+ #tag\n" {
		t.Fatalf("diff: %+v\n%s", d.Diffs[0], got)
	}
	if _, err := id.Run(ctx, "run_nope"); !errors.Is(err, agent.ErrRunNotFound) {
		t.Fatalf("unknown run: %v", err)
	}
}

func TestWhyAnEditCantBeUndone(t *testing.T) {
	for _, tc := range []struct {
		name    string
		after   func(h *harness)
		blocked string
	}{
		{"changed", func(h *harness) { h.update("n1", "Milk\n\n#tag\nmore", nil) }, agent.BlockedChanged},
		{"locked", func(h *harness) { h.update("n1", "notesd:locked:v1:"+strings.Repeat("A", 60), nil) }, agent.BlockedLocked},
		{"deleted", func(h *harness) { h.delete("n1") }, agent.BlockedDeleted},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h := newHarness(t)
			h.agent("work", agent.NewNoteDone)
			h.tagged("n1")
			tc.after(h)
			runs, _, err := h.d.Agent.Runs(ctx, agent.RunFilter{Note: "n1"}, 10, "")
			if err != nil {
				t.Fatal(err)
			}
			e := runs[len(runs)-1].Edits[0]
			if e.Undoable || e.Blocked != tc.blocked {
				t.Fatalf("edit: %+v", e)
			}
		})
	}
}
