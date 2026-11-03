package agent

import (
	"encoding/json"
	"strings"
	"time"

	"notes/server/internal/notes"
)

// instructions come first in every run, before the user's prompts.
const instructions = `You are the agent of one space in notesd, a notes app. You act on the user's notes, with the tools, only as the prompts below ask. You are not a chatbot: nobody reads your replies, except a one-line summary in an activity log.

Rules:
- Do only what the prompts ask. If nothing they ask applies, change nothing.
- Make the smallest change that does the job. Leave the user's words, formatting and order alone, unless a prompt asks you to rewrite them.
- Never write notes for the user beyond what a prompt asks.
- You can only see and change notes in this space.
- The message tells you what happened, with the note, the cursor, the space's tags and its reference notes as they are now: use them as they are, without reading them again. Read any other note before you change it.
- edit_note's find must match exactly once, so include enough of the text around what you change.
- When you're done, answer with one short line saying what you did, or "No change" and why.

Notes are Markdown: headings (#, ##, ###), **bold**, *italic*, ~~strikethrough~~, lists (- item, 1. item), task lists (- [ ] to do, - [x] done), > quotes and [links](https://example.com). A note's title is separate from its content: change it with set_title, never as a heading in the content.

Tags are part of the text: #tag, or #multi word tag# closed by a #, nested with / as in #home/garden. A tag starts a line or follows a space. Reuse the space's tags when they fit.`

var triggerNames = map[Trigger]string{
	NoteCreated: "A new note was just opened, before anything was typed",
	EditStarted: "The user started changing a note",
	NewNoteDone: "The user finished writing a new note",
	EditDone:    "The user finished changing a note",
	NoteDeleted: "A note in this space was deleted",
	MovedIn:     "A note was moved into this space",
	MovedOut:    "A note was moved out of this space",
	Menu:        "The user asked for this from a note's menu",
}

// systemPrompt is the fixed instructions, the time, the space's prompt and
// the trigger's.
func systemPrompt(c Config, t Trigger, now time.Time) string {
	var b strings.Builder
	b.WriteString(instructions)
	b.WriteString("\n\nIt is ")
	b.WriteString(now.Format("Monday, 2 January 2006, 15:04"))
	b.WriteString(" (")
	b.WriteString(now.Location().String())
	b.WriteString("). Today's date is ")
	b.WriteString(now.Format(time.DateOnly))
	b.WriteString(".\n\n## This space\n\n")
	if c.Prompt != "" {
		b.WriteString(c.Prompt)
	} else {
		b.WriteString("(No description.)")
	}
	b.WriteString("\n\n## Now: ")
	b.WriteString(triggerNames[t])
	b.WriteString("\n\n")
	b.WriteString(c.Triggers[t])
	return b.String()
}

// eventMessage tells the agent what happened, with the note when it can
// still read it.
func eventMessage(r queuedRun, n *notes.Note, cursor *int, rc runContext, spaceName func(string) string) string {
	m := map[string]any{"trigger": r.Trigger, "noteId": r.NoteID}
	switch {
	case r.Event.Title != nil:
		m["note"] = map[string]any{"id": r.NoteID, "title": *r.Event.Title, "content": deref(r.Event.Content)}
	case n != nil:
		v := noteView(*n)
		if cursor != nil {
			v["cursor"] = cursorView(n.Content, *cursor)
			v["open"] = "in the user's editor: they see your changes as you make them"
		}
		m["note"] = v
	}
	m["spaceTags"] = rc.Tags
	if rc.Tags == nil {
		m["spaceTags"] = []any{}
	}
	if len(rc.References) > 0 {
		m["referenceNotes"] = rc.References
	}
	if len(rc.Missing) > 0 {
		m["referenceNotesMissing"] = rc.Missing
	}
	switch r.Trigger {
	case NoteDeleted:
		m["note_is"] = "deleted: this is what it said. You can't open it."
	case MovedOut:
		m["note_is"] = "in another space now: this is what it said as it left. You can't open or change it."
		m["movedTo"] = spaceName(r.Event.ToSpaceID)
	case MovedIn:
		m["movedFrom"] = spaceName(r.Event.FromSpaceID)
	}
	b, _ := json.MarshalIndent(m, "", "  ")
	return string(b)
}

// cursorView is the text on either side of a cursor, as get_cursor gives it.
func cursorView(content string, at int) map[string]any {
	runes := []rune(content)
	at = min(max(at, 0), len(runes))
	return map[string]any{
		"before": string(runes[max(0, at-cursorContext):at]),
		"after":  string(runes[at:min(len(runes), at+cursorContext)]),
	}
}

func noteView(n notes.Note) map[string]any {
	return map[string]any{
		"id": n.ID, "title": n.Title, "content": n.Content,
		"createdAt": time.UnixMilli(n.CreatedAt).UTC().Format(time.RFC3339),
		"updatedAt": time.UnixMilli(n.UpdatedAt).UTC().Format(time.RFC3339),
	}
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}
