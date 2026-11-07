package agent

import (
	"context"
	"errors"

	"notes/server/internal/notes"
)

// Editors reaches the account's open note editors (internal/editors), so a
// run reads what the user sees, unsaved typing and cursor included, and its
// changes land in the editor, where the user can undo them.
type Editors interface {
	// Read returns the note as an editor that has it open shows it.
	Read(ctx context.Context, noteID, prefer string) (EditorNote, error)
	// Apply makes the change in that editor, and says what it did.
	Apply(ctx context.Context, noteID, prefer string, change EditorChange) (EditorApplied, error)
}

// Errors from Editors.
var (
	ErrNotOpen       = errors.New("no editor has the note open")
	ErrEditorGone    = errors.New("the editor disconnected")
	ErrEditorChanged = errors.New("the note changed in the editor")
)

// EditorNote is a note as an editor shows it. Cursor is in Unicode code
// points into Content; Revision is the server's revision of exactly this,
// nil when the editor has unsaved changes or the note was never saved.
type EditorNote struct {
	SpaceID  string `json:"spaceId"`
	Title    string `json:"title"`
	Content  string `json:"content"`
	Cursor   *int   `json:"cursor"`
	Revision *int64 `json:"revision"`
}

// EditorChange is one change a run makes in an editor: After for Before.
// An editor that no longer shows Before, the user having typed meanwhile,
// makes Op to what it shows instead, as MadeTo does, rather than the run
// reading it again.
type EditorChange struct {
	RunID  string `json:"runId"`
	Before Fields `json:"before"`
	After  Fields `json:"after"`
	Op     edit   `json:"op"`
}

// MadeTo is now with the change made to it: After, if now is Before, else
// Op made to now. Not ok when Op no longer fits: its find isn't there
// exactly once, or an insert at the cursor has none (cursor).
func (c EditorChange) MadeTo(now Fields, cursor *int) (after Fields, ok bool) {
	if now == c.Before {
		return c.After, true
	}
	if c.Op.Kind == "content" {
		return now, false // made again, the whole content would wipe out what the user typed
	}
	n := notes.Note{Title: now.Title, Content: now.Content}
	if err := c.Op.apply(&n, cursor); err != nil {
		return now, false
	}
	return Fields{n.Title, n.Content}, true
}

// EditorApplied is what an editor did with a change: what it showed just
// before (Revision: the server's revision of exactly that, nil when it had
// unsaved changes), and what it shows after, as its Markdown writes it.
type EditorApplied struct {
	Before   Fields
	Revision *int64
	Shows    Fields
}

// Fields are a note's title and content.
type Fields struct {
	Title   string `json:"title"`
	Content string `json:"content"`
}
