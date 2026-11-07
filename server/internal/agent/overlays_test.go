package agent_test

import (
	"errors"
	"strings"
	"testing"

	"notes/server/internal/agent"
	"notes/server/internal/llm"
)

// showing runs a "new note done" run on n1, open in an editor with content,
// whose model makes the given calls.
func (h *harness) showing(content string, calls ...[2]string) {
	h.t.Helper()
	h.done("n1", content)
	h.openInEditor("n1", content, ip(0))
	for _, c := range calls {
		h.model.steps = append(h.model.steps, call(c[0], c[1]))
	}
	h.model.steps = append(h.model.steps, final("Shown."))
	h.next()
}

func (h *harness) open(noteID string) []agent.Overlay {
	h.t.Helper()
	list, err := h.d.Agent.OpenOverlays(ctx, noteID)
	if err != nil {
		h.t.Fatal(err)
	}
	return list
}

func TestShowCard(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.showing("Milk\n\nEggs",
		[2]string{"show_card", `{"body": "What did you cook?"}`},
		[2]string{"show_card", `{"body": "Under eggs", "after": "Eggs"}`},
	)
	list := h.open("n1")
	if len(list) != 2 || list[0].Kind != agent.KindCard || list[0].Body != "What did you cook?" || list[0].Place.At != "top" ||
		list[1].Place.After != "Eggs" || list[0].RunID != h.lastRunID() {
		t.Fatalf("open: %+v", list)
	}
	if len(h.shown["n1"]) != 2 {
		t.Fatalf("editors were told %+v", h.shown["n1"])
	}
	// Showing isn't changing: the note and its outcome stay as they were.
	if h.content("n1") != "Milk\n\nEggs" || len(h.editor.changes) != 0 || h.only().outcome != agent.OutcomeNoChange {
		t.Fatalf("changed: %q %+v", h.content("n1"), h.only())
	}
	d, _ := h.d.Agent.Run(ctx, h.lastRunID())
	if d.Overlays == nil || d.Overlays.Cards != 2 || d.Overlays.Suggestions != 0 {
		t.Fatalf("activity: %+v", d.Overlays)
	}

	if _, err := h.d.Agent.AnswerOverlay(ctx, list[0].ID, "accept", &agent.EditorApplied{}); !errors.Is(err, agent.ErrBadAnswer) {
		t.Fatalf("a card was accepted: %v", err)
	}
	if note, err := h.d.Agent.AnswerOverlay(ctx, list[0].ID, "dismiss", nil); err != nil || note != "n1" {
		t.Fatalf("dismiss: %q %v", note, err)
	}
	if _, err := h.d.Agent.AnswerOverlay(ctx, list[0].ID, "dismiss", nil); !errors.Is(err, agent.ErrOverlayGone) {
		t.Fatalf("dismissed twice: %v", err)
	}
	if left := h.open("n1"); len(left) != 1 || left[0].ID != list[1].ID {
		t.Fatalf("left: %+v", left)
	}
}

func TestShowCardRefuses(t *testing.T) {
	for _, tc := range []struct{ name, args, want string }{
		{"no body", `{"body": "  "}`, "body is required"},
		{"long body", `{"body": "` + strings.Repeat("a", 2001) + `"}`, "at most 2000"},
		{"missing quote", `{"body": "x", "after": "Bread"}`, "doesn't appear"},
		{"repeated quote", `{"body": "x", "after": "Milk"}`, "appears 2 times"},
		{"bad place", `{"body": "x", "at": "middle"}`, `at must be`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h := newHarness(t)
			h.agent("work", agent.NewNoteDone)
			h.showing("Milk\nMilk", [2]string{"show_card", tc.args})
			if got := lastToolResult(h.model.requests[1]); !strings.Contains(got, tc.want) {
				t.Fatalf("told %s", got)
			}
			if len(h.open("n1")) != 0 {
				t.Fatal("shown anyway")
			}
		})
	}

	t.Run("at most three", func(t *testing.T) {
		h := newHarness(t)
		h.agent("work", agent.NewNoteDone)
		card := [2]string{"show_card", `{"body": "x"}`}
		h.showing("Milk", card, card, card, card)
		if got := lastToolResult(h.model.requests[4]); !strings.Contains(got, "at most 3") || len(h.open("n1")) != 3 {
			t.Fatalf("told %s, %d open", got, len(h.open("n1")))
		}
	})

	t.Run("not open in an editor", func(t *testing.T) {
		h := newHarness(t)
		h.agent("work", agent.NewNoteDone)
		h.done("n1", "Milk")
		h.model.steps = append(h.model.steps, call("show_card", `{"body": "x"}`), final("No."))
		h.next()
		if got := lastToolResult(h.model.requests[1]); !strings.Contains(got, "isn't open in an editor") || len(h.open("n1")) != 0 {
			t.Fatalf("told %s", got)
		}
	})

	t.Run("closed since it was read", func(t *testing.T) {
		h := newHarness(t)
		h.agent("work", agent.NewNoteDone)
		h.done("n1", "Milk")
		h.openInEditor("n1", "Milk", nil)
		h.model.steps = append(h.model.steps,
			func(llm.ChatRequest) (*llm.ChatResponse, error) {
				delete(h.editor.notes, "n1")
				return toolReply("show_card", `{"body": "x"}`), nil
			}, final("No."))
		h.next()
		if got := lastToolResult(h.model.requests[1]); !strings.Contains(got, "isn't open in an editor") {
			t.Fatalf("told %s", got)
		}
	})
}

func TestSuggest(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone)
	h.showing("Mlik and eggs", [2]string{"suggest", `{"changes": [
		{"find": "Mlik", "replace": "Milk", "why": "Typo"},
		{"title": "Groceries"},
		{"insert": {"at": "end", "text": "\n#groceries\n"}, "why": "Tag it"}
	]}`})
	list := h.open("n1")
	if len(list) != 3 {
		t.Fatalf("open: %+v", list)
	}
	typo, title, tag := list[0], list[1], list[2]
	if typo.Kind != agent.KindSuggestion || typo.Body != "Typo" || typo.Place.After != "Mlik" ||
		title.Place.At != "top" || tag.Place.At != "end" || tag.Body != "Tag it" {
		t.Fatalf("open: %+v", list)
	}
	if c := typo.Change; c == nil || c.Kind != "replace" || c.Find != "Mlik" || c.Replace != "Milk" ||
		tag.Change.Text != "#groceries" || title.Change.Title != "Groceries" {
		t.Fatalf("changes: %+v %+v %+v", typo.Change, title.Change, tag.Change)
	}
	if h.content("n1") != "Mlik and eggs" || len(h.editor.changes) != 0 {
		t.Fatal("a suggestion changed the note")
	}

	// Accepting records the editor's change as the run's edit.
	before := agent.Fields{Title: "T", Content: "Mlik and eggs"}
	shows := agent.Fields{Title: "T", Content: "Milk and eggs"}
	if _, err := h.d.Agent.AnswerOverlay(ctx, typo.ID, "accept", nil); !errors.Is(err, agent.ErrBadAnswer) {
		t.Fatalf("accepted without the change: %v", err)
	}
	if _, err := h.d.Agent.AnswerOverlay(ctx, typo.ID, "dismiss", nil); !errors.Is(err, agent.ErrBadAnswer) {
		t.Fatalf("dismissed a suggestion: %v", err)
	}
	if _, err := h.d.Agent.AnswerOverlay(ctx, typo.ID, "accept", &agent.EditorApplied{Before: before, Shows: shows}); err != nil {
		t.Fatal(err)
	}
	if _, err := h.d.Agent.AnswerOverlay(ctx, title.ID, "reject", nil); err != nil {
		t.Fatal(err)
	}
	if _, err := h.d.Agent.AnswerOverlay(ctx, tag.ID, "stale", nil); err != nil {
		t.Fatal(err)
	}
	if len(h.open("n1")) != 0 {
		t.Fatal("still open")
	}
	id := h.lastRunID()
	d, _ := h.d.Agent.Run(ctx, id)
	if d.Outcome != agent.OutcomeEdited || len(d.Edits) != 1 || d.Overlays == nil ||
		d.Overlays.Suggestions != 3 || d.Overlays.Accepted != 1 {
		t.Fatalf("run: %+v %+v", d.Run, d.Overlays)
	}

	// The editor saves it: undo puts the typo back.
	h.update("n1", "Milk and eggs", nil)
	if _, err := h.undo(id); err != nil {
		t.Fatal(err)
	}
	if got := h.content("n1"); got != "Mlik and eggs" {
		t.Fatalf("undo put back %q", got)
	}
}

func TestSuggestRefuses(t *testing.T) {
	for _, tc := range []struct{ name, args, want string }{
		{"none", `{"changes": []}`, "changes is required"},
		{"two kinds", `{"changes": [{"find": "Milk", "replace": "Oat", "title": "x"}]}`, "give one of"},
		{"no replace", `{"changes": [{"find": "Milk"}]}`, "go together"},
		{"missing", `{"changes": [{"find": "Bread", "replace": "Oat"}]}`, "doesn't appear"},
		{"no change", `{"changes": [{"find": "Milk", "replace": "Milk"}]}`, "changes nothing"},
		{"bad insert", `{"changes": [{"insert": {"at": "middle", "text": "x"}}]}`, "insert needs"},
		{"too many", `{"changes": [` + strings.Repeat(`{"title": "x"},`, 20) + `{"title": "x"}]}`, "at most 20"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h := newHarness(t)
			h.agent("work", agent.NewNoteDone)
			h.showing("Milk", [2]string{"suggest", tc.args})
			if got := lastToolResult(h.model.requests[1]); !strings.Contains(got, tc.want) {
				t.Fatalf("told %s", got)
			}
			if len(h.open("n1")) != 0 {
				t.Fatal("shown anyway")
			}
		})
	}
}

func TestALaterRunReplacesItsRulesOverlays(t *testing.T) {
	h := newHarness(t)
	h.agent("work", agent.NewNoteDone, agent.EditDone)
	h.showing("Milk", [2]string{"show_card", `{"body": "first"}`})
	first := h.open("n1")

	// Another rule's run (editDone) leaves them.
	h.update("n1", "Milk!", nil)
	h.advance(agent.IdleAfter)
	h.close()
	h.model.steps = append(h.model.steps, call("show_card", `{"body": "edit 1"}`), final("Shown."))
	h.next()
	if list := h.open("n1"); len(list) != 2 || list[0].ID != first[0].ID {
		t.Fatalf("open: %+v", list)
	}

	// Its own rule's next run replaces its.
	h.update("n1", "Milk!!", nil)
	h.advance(agent.IdleAfter)
	h.close()
	h.model.steps = append(h.model.steps, call("show_card", `{"body": "edit 2"}`), final("Shown."))
	h.next()
	list := h.open("n1")
	if len(list) != 2 || list[0].ID != first[0].ID || list[1].Body != "edit 2" {
		t.Fatalf("open: %+v", list)
	}
	if got := h.shown["n1"]; len(got) != 2 {
		t.Fatalf("editors were told %+v", got)
	}
}

func TestOverlaysEnd(t *testing.T) {
	for _, tc := range []struct {
		name string
		do   func(h *harness)
	}{
		{"the last editor closed", func(h *harness) {
			if ok, err := h.d.Agent.CloseOverlays(ctx, "n1"); !ok || err != nil {
				h.t.Fatalf("close: %v %v", ok, err)
			}
			if ok, _ := h.d.Agent.CloseOverlays(ctx, "n1"); ok {
				h.t.Fatal("closed twice")
			}
		}},
		{"locked", func(h *harness) { h.update("n1", "notesd:locked:v1:"+strings.Repeat("A", 60), nil) }},
		{"deleted", func(h *harness) { h.delete("n1") }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h := newHarness(t)
			h.agent("work", agent.NewNoteDone)
			h.showing("Milk", [2]string{"show_card", `{"body": "a secret"}`})
			tc.do(h)
			if len(h.open("n1")) != 0 {
				t.Fatal("still open")
			}
			d, _ := h.d.Agent.Run(ctx, h.lastRunID())
			if d.Overlays == nil || d.Overlays.Cards != 1 {
				t.Fatalf("activity forgot it showed one: %+v", d.Overlays)
			}
			if tc.name == "the last editor closed" {
				return
			}
			var n int
			if err := h.db.R.QueryRow(`SELECT count(*) FROM agent_overlays WHERE body LIKE '%secret%'`).Scan(&n); err != nil || n != 0 {
				t.Fatalf("%d overlays keep the note's text", n)
			}
		})
	}
}

func TestOnlyEditorRunsAreToldToSuggestChangesToTheUsersWords(t *testing.T) {
	for _, tr := range agent.Triggers {
		system := agent.SystemPrompt(agent.Config{Prompt: "p", Triggers: map[agent.Trigger]string{tr: "Fix typos."}}, tr)
		told := strings.Contains(system, "Change their own words (fixes, rewording, cuts) with suggest") &&
			strings.Contains(system, "add your own text (a title, tags, a list) directly")
		if told != tr.InEditor() {
			t.Errorf("%s: told %v", tr, told)
		}
		// Before the rule's prompt, which wins.
		if told && strings.Index(system, "with suggest") > strings.Index(system, "Fix typos.") {
			t.Errorf("%s: after the prompt", tr)
		}
	}
}
