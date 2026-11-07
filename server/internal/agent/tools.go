package agent

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"strings"

	"notes/server/internal/llm"
	"notes/server/internal/locks"
	"notes/server/internal/notes"
	"notes/server/internal/search"
)

func tool(name, description, params string) llm.Tool {
	return llm.Tool{Type: "function", Function: llm.ToolFunction{Name: name, Description: description, Parameters: json.RawMessage(params)}}
}

const noteIDParam = `"id": {"type": "string", "description": "The note's id. Leave it out for the note this run is about."}`

// doneParam ends the run with a change, saving a model call just to say so.
const doneParam = `"done": {"type": "string", "description": "Only with your last change: one short line saying what you did. The run ends with it."}`

// tools are what the agent can do. Nothing here deletes, moves or locks a
// note, or reaches outside its space.
var tools = []llm.Tool{
	tool("get_note", "Read a note in this space: its title, Markdown content and times.",
		`{"type": "object", "properties": {`+noteIDParam+`}, "additionalProperties": false}`),
	tool("search_notes", "Full-text search in this space. Returns ids, titles and excerpts. Titles aren't unique: to open a note by name, search, then use its id.",
		`{"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"], "additionalProperties": false}`),
	tool("list_notes", "Notes in this space, most recently updated first, optionally with a tag.",
		`{"type": "object", "properties": {"tag": {"type": "string", "description": "A tag, without the #."}, "limit": {"type": "integer", "minimum": 1, "maximum": 50}}, "additionalProperties": false}`),
	tool("list_tags", "This space's tags, with how many notes have each.",
		`{"type": "object", "properties": {}, "additionalProperties": false}`),
	tool("set_title", "Change a note's title.",
		`{"type": "object", "properties": {`+noteIDParam+`, "title": {"type": "string"}, `+doneParam+`}, "required": ["title"], "additionalProperties": false}`),
	tool("edit_note", "Replace text in a note's Markdown content. find must appear exactly once; on an empty note, use insert.",
		`{"type": "object", "properties": {`+noteIDParam+`, "find": {"type": "string"}, "replace": {"type": "string"}, `+doneParam+`}, "required": ["find", "replace"], "additionalProperties": false}`),
	tool("insert", "Add text to a note's content: at its start or its end as a block of its own, or exactly at the cursor (this run's note, while the user has it open). The way to write in an empty note.",
		`{"type": "object", "properties": {`+noteIDParam+`, "at": {"type": "string", "enum": ["start", "end", "cursor"]}, "text": {"type": "string"}, `+doneParam+`}, "required": ["at", "text"], "additionalProperties": false}`),
	tool("set_content", "Replace a note's whole Markdown content. For a note you keep yourself, like a digest: for the user's writing, use edit_note or insert.",
		`{"type": "object", "properties": {`+noteIDParam+`, "content": {"type": "string"}, `+doneParam+`}, "required": ["content"], "additionalProperties": false}`),
	tool("get_cursor", "Where the user's cursor is in this run's note, while they have it open: the text just before and just after it.",
		`{"type": "object", "properties": {}, "additionalProperties": false}`),
	tool("show_card", "Show a card on this run's note while the user has it open: beside the note's text, never in it, for them to read and dismiss. Gone when they close the note. Only for this run's note, while it's open in an editor.",
		`{"type": "object", "properties": {"body": {"type": "string", "description": "What the card says, in Markdown: at most 2000 characters."}, `+placeParams+`, `+doneParam+`}, "required": ["body"], "additionalProperties": false}`),
	tool("suggest", "Suggest changes to this run's note while the user has it open: each shows as a card by the text it changes, with accept and reject buttons. Nothing changes unless the user accepts it. Only for this run's note, while it's open in an editor.",
		`{"type": "object", "properties": {"changes": {"type": "array", "minItems": 1, "maxItems": 20, "items": {"type": "object", "properties": {
			"find": {"type": "string", "description": "Text to replace: it must appear exactly once."},
			"replace": {"type": "string", "description": "What replaces find."},
			"title": {"type": "string", "description": "A new title, instead of find and replace."},
			"insert": {"type": "object", "description": "Text to add, instead of find and replace: at the start or end as a block of its own, or at the cursor.", "properties": {"at": {"type": "string", "enum": ["start", "end", "cursor"]}, "text": {"type": "string"}}, "required": ["at", "text"], "additionalProperties": false},
			"why": {"type": "string", "description": "A few words on why, shown on the card."}}, "additionalProperties": false}}, `+doneParam+`}, "required": ["changes"], "additionalProperties": false}`),
	tool("create_note", "Create a note in this space.",
		`{"type": "object", "properties": {"title": {"type": "string"}, "content": {"type": "string"}, `+doneParam+`}, "required": ["title", "content"], "additionalProperties": false}`),
}

// writeTools are the tools that take done.
var writeTools = map[string]bool{"set_title": true, "edit_note": true, "insert": true, "set_content": true, "create_note": true,
	"show_card": true, "suggest": true}

// doneWith is the summary a write call ends the run with, if it does.
func doneWith(tc llm.ToolCall) string {
	if !writeTools[tc.Function.Name] {
		return ""
	}
	var args struct {
		Done string `json:"done"`
	}
	_ = json.Unmarshal([]byte(tc.Function.Arguments), &args)
	return strings.TrimSpace(args.Done)
}

// toolError is a tool refusing, told to the model so it can try otherwise:
// with the note as it is now (note), when it changed since the model read it.
type toolError struct {
	msg  string
	note *notes.Note
}

func (e *toolError) Error() string { return e.msg }

// toolErrorResult is what the model is told of te.
func toolErrorResult(te *toolError) string {
	m := map[string]any{"error": te.msg}
	if te.note != nil {
		m["note"] = noteView(*te.note)
	}
	return jsonString(m)
}

// errNoLocking refuses a write whose content is a locked note's: agents
// never lock a note.
var errNoLocking = refuse("that content is a locked note's, and agents never lock a note")

func refuse(format string, a ...any) error { return &toolError{msg: fmt.Sprintf(format, a...)} }

// stopError ends the run as f says: its own note can't be worked on any more.
type stopError struct{ f finished }

func (e *stopError) Error() string { return e.f.Message }

// errEditorGone means the editor went away while the run was changing the
// note in it: the change may or may not have landed, so the run stops.
var errEditorGone = errors.New("the editor went away")

// errEditorClosed and errLockedInEditor end an editor trigger's run: no
// editor shows its note any more, or the one that does has it locked.
var (
	errEditorClosed   = errors.New("no editor has the note open")
	errLockedInEditor = errors.New("the note is locked in its editor")
)

// editorRead is a note as an editor showed it.
type editorRead struct {
	revision *int64 // the server's, if the editor had saved everything
	cursor   *int   // code points into the content
}

// cursorContext is how much text get_cursor shows on either side.
const cursorContext = 200

// call runs one tool call and returns its result as JSON for the model.
func (rs *runState) call(ctx context.Context, tc llm.ToolCall) (string, error) {
	var args struct {
		ID      string  `json:"id"`
		Query   string  `json:"query"`
		Tag     string  `json:"tag"`
		Limit   int     `json:"limit"`
		Title   *string `json:"title"`
		Content *string `json:"content"`
		Find    *string `json:"find"`
		Replace *string `json:"replace"`
		At      string  `json:"at"`
		Text    *string `json:"text"`
	}
	if strings.TrimSpace(tc.Function.Arguments) != "" {
		if err := json.Unmarshal([]byte(tc.Function.Arguments), &args); err != nil {
			return "", refuse("the arguments aren't a JSON object: %v", err)
		}
	}
	switch tc.Function.Name {
	case "show_card":
		return rs.showCard(ctx, tc.Function.Arguments)
	case "suggest":
		return rs.suggest(ctx, tc.Function.Arguments)
	}
	id := args.ID
	if id == "" {
		id = rs.run.NoteID
	}
	switch tc.Function.Name {
	case "get_note":
		n, err := rs.read(ctx, id)
		if err != nil {
			return "", err
		}
		return jsonString(noteView(n)), nil
	case "search_notes":
		if strings.TrimSpace(args.Query) == "" {
			return "", refuse("query is required")
		}
		found, err := search.Search(ctx, rs.acct.Agent.db, args.Query, search.Filter{Space: rs.run.SpaceID, Unlocked: true}, 20, 0)
		if err != nil {
			return "", err
		}
		out := []map[string]any{}
		for _, f := range found {
			out = append(out, map[string]any{"id": f.ID, "title": f.Title, "excerpt": plainExcerpt(f.Excerpt)})
		}
		return jsonString(out), nil
	case "list_notes":
		limit := args.Limit
		if limit <= 0 || limit > 50 {
			limit = 20
		}
		filter := search.Filter{Space: rs.run.SpaceID, Unlocked: true}
		if args.Tag != "" {
			filter.Tag = search.TagKey(strings.TrimPrefix(strings.Trim(args.Tag, " "), "#"))
		}
		items, _, err := rs.acct.Notes.List(ctx, filter, limit, "")
		if err != nil {
			return "", err
		}
		out := []map[string]any{}
		for _, it := range items {
			out = append(out, map[string]any{"id": it.ID, "title": it.Title, "preview": it.Preview})
		}
		return jsonString(out), nil
	case "list_tags":
		tags, err := search.ListTags(ctx, rs.acct.Agent.db, search.Filter{Space: rs.run.SpaceID, Unlocked: true})
		if err != nil {
			return "", err
		}
		return jsonString(tagViews(tags)), nil
	case "set_title":
		if args.Title == nil {
			return "", refuse("title is required")
		}
		title := strings.TrimSpace(strings.ReplaceAll(*args.Title, "\n", " "))
		return rs.write(ctx, id, edit{Kind: "title", Title: title})
	case "edit_note":
		if args.Find == nil || args.Replace == nil || *args.Find == "" {
			return "", refuse("find and replace are required, and find can't be empty")
		}
		return rs.write(ctx, id, edit{Kind: "replace", Find: *args.Find, Replace: *args.Replace})
	case "insert":
		if args.Text == nil || strings.TrimSpace(*args.Text) == "" {
			return "", refuse("text is required")
		}
		switch args.At {
		case "start", "end":
			return rs.write(ctx, id, edit{Kind: "insert", At: args.At, Text: strings.Trim(*args.Text, "\n")})
		case "cursor":
			if id != rs.run.NoteID {
				return "", refuse("only this run's note has a cursor")
			}
			if _, err := rs.cursor(ctx); err != nil {
				return "", err
			}
			return rs.write(ctx, id, edit{Kind: "insert", At: "cursor", Text: *args.Text})
		default:
			return "", refuse(`at must be "start", "end" or "cursor"`)
		}
	case "get_cursor":
		n, err := rs.cursor(ctx)
		if err != nil {
			return "", err
		}
		return jsonString(cursorView(n.Content, *rs.inEditor[n.ID].cursor)), nil
	case "set_content":
		if args.Content == nil {
			return "", refuse("content is required")
		}
		return rs.write(ctx, id, edit{Kind: "content", Text: *args.Content})
	case "create_note":
		if args.Title == nil || args.Content == nil {
			return "", refuse("title and content are required")
		}
		return rs.create(ctx, strings.TrimSpace(*args.Title), *args.Content)
	}
	return "", refuse("there's no tool called %q", tc.Function.Name)
}

// edit is a change the agent asked for. It's made to the note as it is when
// it lands: changed since the agent read it, to what it is now, rather than
// the run starting over.
type edit struct {
	Kind    string `json:"kind"` // "title", "replace", "insert" or "content"
	Title   string `json:"title"`
	Find    string `json:"find"`
	Replace string `json:"replace"`
	At      string `json:"at"`   // "start", "end" or "cursor"
	Text    string `json:"text"` // the whole content, for "content"
}

// apply makes e to n, or refuses: find must appear exactly once, and an
// insert at the cursor needs one (code points into the content).
func (e edit) apply(n *notes.Note, cursor *int) error {
	switch e.Kind {
	case "title":
		n.Title = e.Title
	case "replace":
		switch c := occurrences(n.Content, e.Find); c {
		case 1:
			n.Content = strings.Replace(n.Content, e.Find, e.Replace, 1)
		case 0:
			return refuse("find doesn't appear in the note; read it again and copy the text exactly")
		default:
			return refuse("find appears %d times; include more of the text around it", c)
		}
	case "insert":
		if e.At != "cursor" {
			n.Content = insertText(n.Content, e.Text, e.At == "start")
			break
		}
		if cursor == nil {
			return refuse("the note isn't open in an editor, so there's no cursor")
		}
		n.Content = insertAt(n.Content, *cursor, e.Text)
	case "content":
		n.Content = e.Text
	}
	return nil
}

// occurrences counts where sub starts in s, overlapping ones too: "aa"
// is twice in "aaa", so a find for it doesn't say which.
func occurrences(s, sub string) int {
	n := 0
	for i := strings.Index(s, sub); i >= 0; {
		n++
		j := strings.Index(s[i+1:], sub)
		if j < 0 {
			break
		}
		i += 1 + j
	}
	return n
}

// insertText adds text as its own block at the start or end of content,
// leaving the content's other end as it was.
func insertText(content, text string, atStart bool) string {
	switch {
	case strings.Trim(content, "\n") == "":
		return text + "\n"
	case atStart:
		return text + "\n\n" + strings.TrimLeft(content, "\n")
	default:
		return strings.TrimRight(content, "\n") + "\n\n" + text + "\n"
	}
}

// insertAt puts text at a code point offset into content.
func insertAt(content string, at int, text string) string {
	runes := []rune(content)
	at = min(max(at, 0), len(runes))
	return string(runes[:at]) + text + string(runes[at:])
}

// read returns a note in the run's space, as last read in this run if it
// was: a write must be based on what the agent saw. A note open in an editor
// is read from there, unsaved typing included. The run's own note deleted,
// moved or locked since stops the run, unless its trigger was that.
func (rs *runState) read(ctx context.Context, id string) (notes.Note, error) {
	if n, ok := rs.reads[id]; ok {
		return n, nil
	}
	if rs.editorOnly(id) {
		n, err := rs.readEditor(ctx, id)
		if err != nil && ctx.Err() != nil {
			return n, err // the deadline or a shutdown, told apart by the caller
		}
		if f := unusable(n, err, rs.run.SpaceID, true); f.Status != "" {
			return n, &stopError{f}
		}
		return n, nil
	}
	n, err := rs.acct.Notes.Get(ctx, id)
	if err != nil && ctx.Err() != nil {
		return n, err // the deadline or a shutdown, told apart by the caller
	}
	if id == rs.run.NoteID && rs.run.Event.Title == nil {
		if f := unusable(n, err, rs.run.SpaceID, true); f.Status != "" {
			return n, &stopError{f}
		}
	}
	missing := errors.Is(err, notes.ErrNotFound) || (err == nil && n.DeletedAt != nil)
	switch {
	case err != nil && !missing:
		return n, err
	case missing && id != rs.run.NoteID:
		return n, refuse("there's no note with id %q", id)
	case missing:
	case n.SpaceID != rs.run.SpaceID:
		return n, refuse("that note isn't in this space")
	case locks.IsLocked(n.Content):
		return n, refuse("that note is locked: its content is encrypted, and agents never see it")
	}
	// Not for a note the store has deleted: a stale editor would bring it back.
	if rs.acct.Editors != nil && !missing {
		en, err := rs.acct.Editors.Read(ctx, id, rs.prefer())
		switch {
		case err == nil && en.SpaceID != rs.run.SpaceID:
			return n, refuse("that note isn't in this space")
		case err == nil:
			n.ID, n.SpaceID, n.Title, n.Content = id, en.SpaceID, en.Title, en.Content
			rs.reads[id] = n
			rs.inEditor[id] = &editorRead{revision: en.Revision, cursor: en.Cursor}
			return n, nil
		case !errors.Is(err, ErrNotOpen) && !errors.Is(err, ErrEditorGone) && !errors.Is(err, ErrEditorLocked):
			return n, err
		}
	}
	if missing {
		return n, refuse("there's no note with id %q", id)
	}
	rs.reads[id] = n
	return n, nil
}

// stop ends the run: why says its own note can't be worked on any more.
func (rs *runState) stop(why error) error {
	return &stopError{unusable(notes.Note{}, why, rs.run.SpaceID, true)}
}

// editorOnly reports whether the run reads and writes note id only in its
// editor: an editor trigger's own note.
func (rs *runState) editorOnly(id string) bool {
	return id == rs.run.NoteID && rs.run.Trigger.InEditor()
}

// readEditor reads note id from the editor that has it open: the run's own
// first. It never reads the store, so the note needn't have reached it.
func (rs *runState) readEditor(ctx context.Context, id string) (notes.Note, error) {
	if rs.acct.Editors == nil {
		return notes.Note{}, errEditorClosed
	}
	en, err := rs.acct.Editors.Read(ctx, id, rs.prefer())
	switch {
	case errors.Is(err, ErrEditorLocked):
		return notes.Note{}, errLockedInEditor
	case errors.Is(err, ErrNotOpen), errors.Is(err, ErrEditorGone):
		return notes.Note{}, errEditorClosed
	case err != nil:
		return notes.Note{}, err
	}
	return rs.fromEditor(id, en), nil
}

// fromEditor is note id as an editor shows it, for the rest of the run.
func (rs *runState) fromEditor(id string, en EditorNote) notes.Note {
	n := notes.Note{ID: id, SpaceID: en.SpaceID, Title: en.Title, Content: en.Content}
	if en.Revision != nil {
		n.Revision = *en.Revision
	}
	rs.reads[id] = n
	rs.inEditor[id] = &editorRead{revision: en.Revision, cursor: en.Cursor}
	return n
}

// cursor reads the run's note again, for where the user's cursor is now.
func (rs *runState) cursor(ctx context.Context) (notes.Note, error) {
	id := rs.run.NoteID
	delete(rs.reads, id)
	delete(rs.inEditor, id)
	n, err := rs.read(ctx, id)
	if err != nil {
		return n, err
	}
	if er := rs.inEditor[id]; er == nil || er.cursor == nil {
		return n, refuse("the note isn't open in an editor, so there's no cursor")
	}
	return n, nil
}

// prefer is the editor this run talks to first: the one that asked for it.
func (rs *runState) prefer() string { return rs.run.Event.ConnectionID }

// writeTries bounds how often a write is made again to a note that keeps
// changing under it.
const writeTries = 3

// errChanged means a write found the note changed since the run read it:
// it's read again, and the change made to it as it is now.
var errChanged = errors.New("note changed since it was read")

// write makes e to a note, as the agent: in the editor that has it open, or
// through the store, based on what the run read; changed since, to the note
// as it is now.
func (rs *runState) write(ctx context.Context, id string, e edit) (string, error) {
	for try := 1; ; try++ {
		cur, err := rs.read(ctx, id)
		if err != nil {
			return "", err
		}
		if try > 1 && e.Kind == "content" {
			// Made to what it is now, the whole content would wipe out what changed.
			return "", &toolError{msg: "the note changed since you read it, so its content wasn't replaced; here it is now", note: &cur}
		}
		next := cur
		var cursor *int
		if er := rs.inEditor[id]; er != nil {
			cursor = er.cursor
		}
		if err := e.apply(&next, cursor); err != nil {
			var te *toolError
			if try > 1 && errors.As(err, &te) {
				te.note = &cur // not what the model read: it needs this to try again
			}
			return "", err
		}
		if locks.IsLocked(next.Content) {
			return "", errNoLocking
		}
		if next.Title == cur.Title && next.Content == cur.Content {
			return `{"ok": true, "changed": false}`, nil
		}
		switch er, ok := rs.inEditor[id]; {
		case ok:
			err = rs.writeInEditor(ctx, id, er, cur, next, e)
		case rs.editorOnly(id):
			err = rs.stop(errEditorClosed) // never under the user, in the store
		default:
			err = rs.writeStore(ctx, id, cur, next)
		}
		switch {
		case errors.Is(err, errChanged) && try < writeTries:
			delete(rs.reads, id)
			delete(rs.inEditor, id)
			continue
		case errors.Is(err, errChanged):
			return "", refuse("the note kept changing as you wrote; try again")
		case err != nil:
			return "", err
		}
		return `{"ok": true, "changed": true}`, nil
	}
}

// writeStore makes a change through the store, based on cur's revision.
func (rs *runState) writeStore(ctx context.Context, id string, cur, next notes.Note) error {
	rs.ops++
	res, err := rs.acct.Notes.Update(ctx, notes.UpdateInput{
		OperationID: fmt.Sprintf("agent:%s:%d", rs.run.ID, rs.ops), ID: id, BaseRevision: cur.Revision,
		Title: next.Title, Content: next.Content, Agent: &notes.AgentMark{RunID: rs.run.ID, Only: true, Runner: true},
	})
	var conflict *notes.ConflictError
	var invalid *notes.ValidationError
	switch {
	case errors.As(err, &conflict):
		return errChanged
	case errors.As(err, &invalid):
		return refuse("%s %s", invalid.Field, invalid.Message)
	case err != nil:
		return err
	}
	next.Revision, next.UpdatedAt = res.Revision, res.UpdatedAt
	rs.reads[id] = next
	return nil
}

// writeInEditor makes the change in the editor that has the note open,
// where the user sees it and can undo it; the editor saves it.
func (rs *runState) writeInEditor(ctx context.Context, id string, er *editorRead, cur, next notes.Note, e edit) error {
	before, after := Fields{cur.Title, cur.Content}, Fields{next.Title, next.Content}
	applied, err := rs.acct.Editors.Apply(ctx, id, rs.prefer(), EditorChange{RunID: rs.run.ID, Before: before, After: after, Op: e})
	switch {
	case errors.Is(err, ErrEditorChanged):
		return errChanged // the change no longer fits what it shows: read it again
	case rs.editorOnly(id) && errors.Is(err, ErrNotOpen):
		return rs.stop(errEditorClosed)
	case rs.editorOnly(id) && errors.Is(err, ErrEditorLocked):
		return rs.stop(errLockedInEditor)
	case errors.Is(err, ErrNotOpen), errors.Is(err, ErrEditorLocked):
		return errChanged // closed, or locked there: read it again, from the store
	case errors.Is(err, ErrEditorGone):
		return errEditorGone
	case err != nil:
		return err
	}
	rev := applied.Revision
	if rev == nil && applied.Before == before {
		rev = er.revision // an editor that doesn't say
	}
	if err := rs.acct.Agent.recordEditorEdit(ctx, rs.run.ID, id, rev, applied.Before, applied.Shows); err != nil {
		return err
	}
	next.Title, next.Content = applied.Shows.Title, applied.Shows.Content
	rs.reads[id] = next
	// Unsaved now; the cursor stays where the user left it, which this
	// change may have moved: read it again to know.
	rs.inEditor[id] = &editorRead{}
	return nil
}

func (rs *runState) create(ctx context.Context, title, content string) (string, error) {
	if locks.IsLocked(content) {
		return "", errNoLocking
	}
	rs.ops++
	id := strings.TrimPrefix(newRunID(rs.acct.Agent.now().UnixMilli()), "run_")
	space := rs.run.SpaceID
	res, err := rs.acct.Notes.Create(ctx, notes.CreateInput{
		OperationID: fmt.Sprintf("agent:%s:%d", rs.run.ID, rs.ops), ID: id, Title: title, Content: content,
		SpaceID: &space, Agent: &notes.AgentMark{RunID: rs.run.ID, Only: true, Runner: true},
	})
	var invalid *notes.ValidationError
	if errors.As(err, &invalid) {
		return "", refuse("%s %s", invalid.Field, invalid.Message)
	}
	if err != nil {
		return "", err
	}
	n := notes.Note{ID: id, Title: title, Content: content, Revision: res.Revision, SpaceID: res.SpaceID,
		CreatedAt: res.UpdatedAt, UpdatedAt: res.UpdatedAt}
	rs.reads[id] = n
	return jsonString(map[string]any{"ok": true, "id": id}), nil
}

func jsonString(v any) string {
	b, _ := json.Marshal(v)
	return string(b)
}

// plainExcerpt drops the <mark> tags search adds for the web app, and its escaping.
func plainExcerpt(s string) string {
	return html.UnescapeString(strings.NewReplacer("<mark>", "", "</mark>", "").Replace(s))
}
