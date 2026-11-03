package agent

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
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
		`{"type": "object", "properties": {`+noteIDParam+`, "title": {"type": "string"}}, "required": ["title"], "additionalProperties": false}`),
	tool("edit_note", "Replace text in a note's Markdown content. find must appear exactly once.",
		`{"type": "object", "properties": {`+noteIDParam+`, "find": {"type": "string"}, "replace": {"type": "string"}}, "required": ["find", "replace"], "additionalProperties": false}`),
	tool("insert", "Add text to a note's content: at its start or its end as a block of its own, or exactly at the cursor (this run's note, while the user has it open).",
		`{"type": "object", "properties": {`+noteIDParam+`, "at": {"type": "string", "enum": ["start", "end", "cursor"]}, "text": {"type": "string"}}, "required": ["at", "text"], "additionalProperties": false}`),
	tool("get_cursor", "Where the user's cursor is in this run's note, while they have it open: the text just before and just after it.",
		`{"type": "object", "properties": {}, "additionalProperties": false}`),
	tool("create_note", "Create a note in this space.",
		`{"type": "object", "properties": {"title": {"type": "string"}, "content": {"type": "string"}}, "required": ["title", "content"], "additionalProperties": false}`),
}

// toolError is a tool refusing, told to the model so it can try otherwise.
type toolError struct{ msg string }

func (e *toolError) Error() string { return e.msg }

func refuse(format string, a ...any) error { return &toolError{fmt.Sprintf(format, a...)} }

// errRestart means the note changed under the run: it starts over.
var errRestart = errors.New("note changed since it was read")

// errEditorGone means the editor went away while the run was changing the
// note in it: the change may or may not have landed, so the run stops.
var errEditorGone = errors.New("the editor went away")

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
		out := []map[string]any{}
		for _, t := range tags {
			out = append(out, map[string]any{"tag": "#" + t.Name, "notes": t.Count})
		}
		return jsonString(out), nil
	case "set_title":
		if args.Title == nil {
			return "", refuse("title is required")
		}
		title := strings.TrimSpace(strings.ReplaceAll(*args.Title, "\n", " "))
		return rs.write(ctx, id, func(n *notes.Note) error { n.Title = title; return nil })
	case "edit_note":
		if args.Find == nil || args.Replace == nil || *args.Find == "" {
			return "", refuse("find and replace are required, and find can't be empty")
		}
		return rs.write(ctx, id, func(n *notes.Note) error {
			switch c := strings.Count(n.Content, *args.Find); c {
			case 1:
				n.Content = strings.Replace(n.Content, *args.Find, *args.Replace, 1)
				return nil
			case 0:
				return refuse("find doesn't appear in the note; read it again and copy the text exactly")
			default:
				return refuse("find appears %d times; include more of the text around it", c)
			}
		})
	case "insert":
		if args.Text == nil || strings.TrimSpace(*args.Text) == "" {
			return "", refuse("text is required")
		}
		switch args.At {
		case "start", "end":
			text := strings.Trim(*args.Text, "\n")
			return rs.write(ctx, id, func(n *notes.Note) error {
				n.Content = insertText(n.Content, text, args.At == "start")
				return nil
			})
		case "cursor":
			if id != rs.run.NoteID {
				return "", refuse("only this run's note has a cursor")
			}
			if _, err := rs.cursor(ctx); err != nil {
				return "", err
			}
			text := *args.Text
			return rs.write(ctx, id, func(n *notes.Note) error {
				er := rs.inEditor[id]
				if er == nil || er.cursor == nil {
					return refuse("the note isn't open in an editor, so there's no cursor")
				}
				n.Content = insertAt(n.Content, *er.cursor, text)
				return nil
			})
		default:
			return "", refuse(`at must be "start", "end" or "cursor"`)
		}
	case "get_cursor":
		n, err := rs.cursor(ctx)
		if err != nil {
			return "", err
		}
		return jsonString(cursorView(n.Content, *rs.inEditor[n.ID].cursor)), nil
	case "create_note":
		if args.Title == nil || args.Content == nil {
			return "", refuse("title and content are required")
		}
		return rs.create(ctx, strings.TrimSpace(*args.Title), *args.Content)
	}
	return "", refuse("there's no tool called %q", tc.Function.Name)
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
// is read from there, unsaved typing included; this run's own note may not
// have reached the store yet.
func (rs *runState) read(ctx context.Context, id string) (notes.Note, error) {
	if n, ok := rs.reads[id]; ok {
		return n, nil
	}
	n, err := rs.acct.Notes.Get(ctx, id)
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
	if rs.acct.Editors != nil {
		en, err := rs.acct.Editors.Read(ctx, id, rs.prefer())
		switch {
		case err == nil && en.SpaceID != rs.run.SpaceID:
			return n, refuse("that note isn't in this space")
		case err == nil:
			n.ID, n.SpaceID, n.Title, n.Content = id, en.SpaceID, en.Title, en.Content
			rs.reads[id] = n
			rs.inEditor[id] = &editorRead{revision: en.Revision, cursor: en.Cursor}
			return n, nil
		case !errors.Is(err, ErrNotOpen) && !errors.Is(err, ErrEditorGone):
			return n, err
		}
	}
	if missing {
		return n, refuse("there's no note with id %q", id)
	}
	rs.reads[id] = n
	return n, nil
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
func (rs *runState) prefer() string { return rs.run.ConnectionID }

// write changes a note through the store, as the agent, based on the
// revision the run read.
func (rs *runState) write(ctx context.Context, id string, change func(*notes.Note) error) (string, error) {
	cur, err := rs.read(ctx, id)
	if err != nil {
		return "", err
	}
	next := cur
	if err := change(&next); err != nil {
		return "", err
	}
	if next.Title == cur.Title && next.Content == cur.Content {
		return `{"ok": true, "changed": false}`, nil
	}
	if er, ok := rs.inEditor[id]; ok {
		return rs.writeInEditor(ctx, id, er, cur, next)
	}
	rs.ops++
	res, err := rs.acct.Notes.Update(ctx, notes.UpdateInput{
		OperationID: fmt.Sprintf("agent:%s:%d", rs.run.ID, rs.ops), ID: id, BaseRevision: cur.Revision,
		Title: next.Title, Content: next.Content, Agent: &notes.AgentMark{RunID: rs.run.ID, Only: true, Runner: true},
	})
	var conflict *notes.ConflictError
	if errors.As(err, &conflict) {
		return "", errRestart
	}
	var invalid *notes.ValidationError
	if errors.As(err, &invalid) {
		return "", refuse("%s %s", invalid.Field, invalid.Message)
	}
	if err != nil {
		return "", err
	}
	next.Revision, next.UpdatedAt = res.Revision, res.UpdatedAt
	rs.reads[id] = next
	return `{"ok": true, "changed": true}`, nil
}

// writeInEditor makes the change in the editor that has the note open,
// where the user sees it and can undo it; the editor saves it.
func (rs *runState) writeInEditor(ctx context.Context, id string, er *editorRead, cur, next notes.Note) (string, error) {
	before, after := Fields{cur.Title, cur.Content}, Fields{next.Title, next.Content}
	after, err := rs.acct.Editors.Apply(ctx, id, rs.prefer(), EditorChange{RunID: rs.run.ID, Before: before, After: after})
	switch {
	case errors.Is(err, ErrEditorChanged), errors.Is(err, ErrNotOpen):
		return "", errRestart
	case errors.Is(err, ErrEditorGone):
		return "", errEditorGone
	case err != nil:
		return "", err
	}
	if err := rs.acct.Agent.recordEditorEdit(ctx, rs.run.ID, id, er.revision, before, after); err != nil {
		return "", err
	}
	next.Title, next.Content = after.Title, after.Content
	rs.reads[id] = next
	// Unsaved now; the cursor stays where the user left it, which this
	// change may have moved: read it again to know.
	rs.inEditor[id] = &editorRead{}
	return `{"ok": true, "changed": true}`, nil
}

func (rs *runState) create(ctx context.Context, title, content string) (string, error) {
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
	s = strings.NewReplacer("<mark>", "", "</mark>", "").Replace(s)
	return strings.NewReplacer("&lt;", "<", "&gt;", ">", "&quot;", `"`, "&#39;", "'", "&amp;", "&").Replace(s)
}
