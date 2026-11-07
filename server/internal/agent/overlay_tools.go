package agent

import (
	"context"
	"encoding/json"
	"strings"

	"notes/server/internal/notes"
)

// placeParams are where a card goes.
const placeParams = `"at": {"type": "string", "enum": ["top", "end", "cursor"], "description": "Where the card goes: at the top (the default), the end, or the cursor."}, ` +
	`"after": {"type": "string", "description": "Instead of at: an exact quote from the note, which must appear once; the card goes under the paragraph holding it."}`

// overlayNote is the run's note as its editor shows it now, for an overlay:
// one shows only there, for the user in the note.
func (rs *runState) overlayNote(ctx context.Context) (notes.Note, error) {
	id := rs.run.NoteID
	if rs.run.Event.Title != nil {
		return notes.Note{}, refuse("this run's note is gone: there's nothing to show anything on")
	}
	// Read again: the user may have closed it, or typed, since it was read.
	delete(rs.reads, id)
	delete(rs.inEditor, id)
	n, err := rs.read(ctx, id)
	if err != nil {
		return n, err
	}
	if rs.inEditor[id] == nil {
		return n, refuse("the note isn't open in an editor, so nothing can show on it: change it directly, or do nothing, as the prompt says")
	}
	return n, nil
}

func (rs *runState) showCard(ctx context.Context, raw string) (string, error) {
	var args struct {
		Body  string `json:"body"`
		At    string `json:"at"`
		After string `json:"after"`
	}
	if err := json.Unmarshal([]byte(raw), &args); err != nil {
		return "", refuse("the arguments aren't a JSON object: %v", err)
	}
	body := strings.TrimSpace(args.Body)
	switch {
	case body == "":
		return "", refuse("body is required")
	case len([]rune(body)) > maxCardRunes:
		return "", refuse("body is at most %d characters", maxCardRunes)
	case rs.cards >= maxCards:
		return "", refuse("a run shows at most %d cards", maxCards)
	}
	n, err := rs.overlayNote(ctx)
	if err != nil {
		return "", err
	}
	place, err := placeOf(n, args.At, args.After)
	if err != nil {
		return "", err
	}
	o := Overlay{ID: newOverlayID(rs.acct.Agent.now().UnixMilli()), Kind: KindCard, Place: place, Body: body}
	if err := rs.show(ctx, []Overlay{o}); err != nil {
		return "", err
	}
	rs.cards++
	return `{"ok": true, "shown": true}`, nil
}

func (rs *runState) suggest(ctx context.Context, raw string) (string, error) {
	var args struct {
		Changes []struct {
			Find    *string `json:"find"`
			Replace *string `json:"replace"`
			Title   *string `json:"title"`
			Insert  *struct {
				At   string `json:"at"`
				Text string `json:"text"`
			} `json:"insert"`
			Why string `json:"why"`
		} `json:"changes"`
	}
	if err := json.Unmarshal([]byte(raw), &args); err != nil {
		return "", refuse("the arguments aren't a JSON object: %v", err)
	}
	switch {
	case len(args.Changes) == 0:
		return "", refuse("changes is required")
	case rs.suggestions+len(args.Changes) > maxSuggestions:
		return "", refuse("a run suggests at most %d changes", maxSuggestions)
	}
	n, err := rs.overlayNote(ctx)
	if err != nil {
		return "", err
	}
	var cursor *int
	if er := rs.inEditor[n.ID]; er != nil {
		cursor = er.cursor
	}
	list := make([]Overlay, 0, len(args.Changes))
	now := rs.acct.Agent.now().UnixMilli()
	for i, c := range args.Changes {
		var e edit
		var place Place
		kinds := 0
		if c.Find != nil || c.Replace != nil {
			kinds++
			if c.Find == nil || c.Replace == nil || *c.Find == "" {
				return "", refuse("change %d: find and replace go together, and find can't be empty", i+1)
			}
			e, place = edit{Kind: "replace", Find: *c.Find, Replace: *c.Replace}, Place{After: *c.Find}
		}
		if c.Title != nil {
			kinds++
			e, place = edit{Kind: "title", Title: strings.TrimSpace(strings.ReplaceAll(*c.Title, "\n", " "))}, Place{At: "top"}
		}
		if c.Insert != nil {
			kinds++
			at := map[string]string{"start": "top", "end": "end", "cursor": "cursor"}[c.Insert.At]
			if at == "" || strings.TrimSpace(c.Insert.Text) == "" {
				return "", refuse(`change %d: insert needs at ("start", "end" or "cursor") and text`, i+1)
			}
			text := c.Insert.Text
			if c.Insert.At != "cursor" {
				text = strings.Trim(text, "\n")
			}
			e, place = edit{Kind: "insert", At: c.Insert.At, Text: text}, Place{At: at}
		}
		if kinds != 1 {
			return "", refuse("change %d: give one of find and replace, title, or insert", i+1)
		}
		// Checked now, so the user is only offered what fits.
		next := n
		if err := e.apply(&next, cursor); err != nil {
			return "", refuse("change %d: %v", i+1, err)
		}
		if next.Title == n.Title && next.Content == n.Content {
			return "", refuse("change %d changes nothing", i+1)
		}
		why := strings.TrimSpace(c.Why)
		if r := []rune(why); len(r) > maxWhyRunes {
			why = string(r[:maxWhyRunes])
		}
		ch := e
		list = append(list, Overlay{ID: newOverlayID(now), Kind: KindSuggestion, Place: place, Body: why, Change: &ch})
	}
	if err := rs.show(ctx, list); err != nil {
		return "", err
	}
	rs.suggestions += len(list)
	return jsonString(map[string]any{"ok": true, "suggested": len(list)}), nil
}

// placeOf is where a card goes in n: a quote that appears exactly once, or
// a position (the top by default).
func placeOf(n notes.Note, at, after string) (Place, error) {
	if after != "" {
		switch c := occurrences(n.Content, after); c {
		case 1:
			return Place{After: after}, nil
		case 0:
			return Place{}, refuse("after doesn't appear in the note; copy the text exactly")
		default:
			return Place{}, refuse("after appears %d times; include more of the text around it", c)
		}
	}
	switch at {
	case "", "top":
		return Place{At: "top"}, nil
	case "end", "cursor":
		return Place{At: at}, nil
	}
	return Place{}, refuse(`at must be "top", "end" or "cursor"`)
}

// show records overlays on the run's note and tells its editors.
func (rs *runState) show(ctx context.Context, list []Overlay) error {
	for i := range list {
		list[i].RunID, list[i].NoteID = rs.run.ID, rs.run.NoteID
	}
	if err := rs.acct.Agent.addOverlays(ctx, rs.run, list); err != nil {
		return err
	}
	open, err := rs.acct.Agent.OpenOverlays(ctx, rs.run.NoteID)
	if err != nil {
		return err
	}
	if rs.acct.OnOverlays != nil {
		rs.acct.OnOverlays(rs.run.NoteID, open)
	}
	return nil
}
