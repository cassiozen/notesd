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
	tool("insert", "Add text to a note's content: at its start or its end. The way to write in an empty note.",
		`{"type": "object", "properties": {`+noteIDParam+`, "at": {"type": "string", "enum": ["start", "end"]}, "text": {"type": "string"}, `+doneParam+`}, "required": ["at", "text"], "additionalProperties": false}`),
	tool("set_content", "Replace a note's whole Markdown content. For a note you keep yourself, like a digest: for the user's writing, use edit_note or insert.",
		`{"type": "object", "properties": {`+noteIDParam+`, "content": {"type": "string"}, `+doneParam+`}, "required": ["content"], "additionalProperties": false}`),
	tool("create_note", "Create a note in this space.",
		`{"type": "object", "properties": {"title": {"type": "string"}, "content": {"type": "string"}, `+doneParam+`}, "required": ["title", "content"], "additionalProperties": false}`),
}

// writeTools are the tools that take done.
var writeTools = map[string]bool{"set_title": true, "edit_note": true, "insert": true, "set_content": true, "create_note": true}

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

func refuse(format string, a ...any) error { return &toolError{msg: fmt.Sprintf(format, a...)} }

// stopError ends the run as f says: its own note can't be worked on any more.
type stopError struct{ f finished }

func (e *stopError) Error() string { return e.f.Message }

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
		text := strings.Trim(*args.Text, "\n")
		switch args.At {
		case "start", "end":
		default:
			return "", refuse(`at must be "start" or "end"`)
		}
		return rs.write(ctx, id, edit{Kind: "insert", At: args.At, Text: text})
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
	Kind          string // "title", "replace", "insert" or "content"
	Title         string
	Find, Replace string
	At, Text      string // At: "start" or "end"; Text: the whole content, for "content"
}

// apply makes e to n, or refuses: find must appear exactly once.
func (e edit) apply(n *notes.Note) error {
	switch e.Kind {
	case "title":
		n.Title = e.Title
	case "replace":
		switch c := strings.Count(n.Content, e.Find); c {
		case 1:
			n.Content = strings.Replace(n.Content, e.Find, e.Replace, 1)
		case 0:
			return refuse("find doesn't appear in the note; read it again and copy the text exactly")
		default:
			return refuse("find appears %d times; include more of the text around it", c)
		}
	case "insert":
		n.Content = insertText(n.Content, e.Text, e.At == "start")
	case "content":
		n.Content = e.Text
	}
	return nil
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

// read returns a note in the run's space, as last read in this run if it
// was: a write must be based on what the agent saw. The run's own note
// deleted, moved or locked since stops the run, unless its trigger was that.
func (rs *runState) read(ctx context.Context, id string) (notes.Note, error) {
	if n, ok := rs.reads[id]; ok {
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
	if errors.Is(err, notes.ErrNotFound) || (err == nil && n.DeletedAt != nil) {
		return n, refuse("there's no note with id %q", id)
	}
	if err != nil {
		return n, err
	}
	if n.SpaceID != rs.run.SpaceID {
		return n, refuse("that note isn't in this space")
	}
	if locks.IsLocked(n.Content) {
		return n, refuse("that note is locked: its content is encrypted, and agents never see it")
	}
	rs.reads[id] = n
	return n, nil
}

// writeTries bounds how often a write is made again to a note that keeps
// changing under it.
const writeTries = 3

// write makes e to a note through the store, as the agent, based on the
// revision the run read; changed since, to the note as it is now.
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
		if err := e.apply(&next); err != nil {
			var te *toolError
			if try > 1 && errors.As(err, &te) {
				te.note = &cur // not what the model read: it needs this to try again
			}
			return "", err
		}
		if next.Title == cur.Title && next.Content == cur.Content {
			return `{"ok": true, "changed": false}`, nil
		}
		rs.ops++
		res, err := rs.acct.Notes.Update(ctx, notes.UpdateInput{
			OperationID: fmt.Sprintf("agent:%s:%d", rs.run.ID, rs.ops), ID: id, BaseRevision: cur.Revision,
			Title: next.Title, Content: next.Content, Agent: &notes.AgentMark{RunID: rs.run.ID, Only: true, Runner: true},
		})
		var conflict *notes.ConflictError
		var invalid *notes.ValidationError
		switch {
		case errors.As(err, &conflict) && try < writeTries:
			delete(rs.reads, id)
			continue
		case errors.As(err, &conflict):
			return "", refuse("the note kept changing as you wrote; try again")
		case errors.As(err, &invalid):
			return "", refuse("%s %s", invalid.Field, invalid.Message)
		case err != nil:
			return "", err
		}
		next.Revision, next.UpdatedAt = res.Revision, res.UpdatedAt
		rs.reads[id] = next
		return `{"ok": true, "changed": true}`, nil
	}
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
	return html.UnescapeString(strings.NewReplacer("<mark>", "", "</mark>", "").Replace(s))
}
