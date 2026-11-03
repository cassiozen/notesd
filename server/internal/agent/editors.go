package agent

import (
	"context"
	"errors"
)

// Editors reaches the account's open note editors (internal/editors), so a
// run reads what the user sees, unsaved typing and cursor included, and its
// changes land in the editor, where the user can undo them.
type Editors interface {
	// Read returns the note as an editor that has it open shows it.
	Read(ctx context.Context, noteID, prefer string) (EditorNote, error)
	// Apply changes the note in that editor from before to after, and
	// returns it as the editor then shows it: after, unless the editor's
	// Markdown writes some of it differently.
	Apply(ctx context.Context, noteID, prefer string, change EditorChange) (Fields, error)
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

// EditorChange is one change a run makes in an editor.
type EditorChange struct {
	RunID  string `json:"runId"`
	Before Fields `json:"before"`
	After  Fields `json:"after"`
}

// Fields are a note's title and content.
type Fields struct {
	Title   string `json:"title"`
	Content string `json:"content"`
}
