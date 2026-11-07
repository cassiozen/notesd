package agent

import (
	"context"
	"database/sql"
	"errors"
	"sort"
	"strings"

	"notes/server/internal/locks"
	"notes/server/internal/notes"
	"notes/server/internal/search"
)

// What goes with a run's first message, so the model can act at once
// instead of spending a call on each lookup.
const (
	maxContextTags    = 100
	maxReferenceRunes = 8000
	// maxOwnEdits is how many of the agent's latest edits to the run's note
	// say which of its lines are the agent's own.
	maxOwnEdits = 20
)

// runContext is the space's tags and reference notes, as of the run's start.
type runContext struct {
	Tags       []map[string]any
	References []map[string]any
	// Missing are reference notes' titles no note in the space has.
	Missing []string
	// Own is the lines of the run's note the agent wrote itself.
	Own []string
}

func (rs *runState) gather(ctx context.Context, c Config, note *notes.Note) (runContext, error) {
	var out runContext
	if note != nil {
		own, err := rs.acct.Agent.ownLines(ctx, note.ID, note.Content)
		if err != nil {
			return out, err
		}
		out.Own = own
	}
	tags, err := search.ListTags(ctx, rs.acct.Agent.db, search.Filter{Space: rs.run.SpaceID, Unlocked: true})
	if err != nil {
		return out, err
	}
	sort.SliceStable(tags, func(i, j int) bool { return tags[i].Count > tags[j].Count })
	out.Tags = tagViews(tags[:min(len(tags), maxContextTags)])
	for _, title := range c.ReferenceNotes {
		var id string
		var locked bool
		// The newest unlocked one, else a locked one: it's there, just never sent.
		err := rs.acct.Agent.db.R.QueryRowContext(ctx, `
			SELECT id, substr(content, 1, ?) = ? AS locked FROM notes
			WHERE space_id = ? AND title = ? AND deleted_at IS NULL
			ORDER BY locked, updated_at DESC LIMIT 1`, len(locks.Family), locks.Family, rs.run.SpaceID, title).Scan(&id, &locked)
		switch {
		case errors.Is(err, sql.ErrNoRows):
			out.Missing = append(out.Missing, title)
			continue
		case err != nil:
			return out, err
		case locked || id == rs.run.NoteID:
			continue // never sent, or in the message already
		}
		n, err := rs.read(ctx, id)
		var refused *toolError
		if errors.As(err, &refused) {
			continue // locked: never sent
		}
		if err != nil {
			return out, err
		}
		v := map[string]any{"id": n.ID, "title": n.Title, "content": n.Content}
		if r := []rune(n.Content); len(r) > maxReferenceRunes {
			v["content"], v["truncated"] = string(r[:maxReferenceRunes]), true
		}
		out.References = append(out.References, v)
	}
	return out, nil
}

// tagViews is tags as the agent is shown them; [] for none.
func tagViews(tags []search.TagCount) []map[string]any {
	out := make([]map[string]any, 0, len(tags))
	for _, t := range tags {
		out = append(out, map[string]any{"tag": "#" + t.Name, "notes": t.Count})
	}
	return out
}

// ownLines is the lines the agent added to a note in its latest edits that
// content still has, in their order there: the agent's writing, not the
// user's, such as the question it asked in a new note.
func (s *Store) ownLines(ctx context.Context, noteID, content string) ([]string, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT before_content, after_content FROM agent_run_edits
		WHERE note_id = ? AND undone_at IS NULL AND text_removed = 0 ORDER BY written_at DESC LIMIT ?`, noteID, maxOwnEdits)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	added := map[string]bool{}
	for rows.Next() {
		var before, after string
		if err := rows.Scan(&before, &after); err != nil {
			return nil, err
		}
		for _, l := range diffLines(before, after) {
			if l.Op == "+" && strings.TrimSpace(l.Text) != "" {
				added[l.Text] = true
			}
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	var out []string
	for _, l := range splitLines(content) {
		if added[l] {
			out = append(out, l)
			delete(added, l) // once, however often it repeats
		}
	}
	return out, nil
}
