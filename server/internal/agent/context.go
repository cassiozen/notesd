package agent

import (
	"context"
	"database/sql"
	"errors"
	"sort"

	"notes/server/internal/locks"
	"notes/server/internal/search"
)

// What goes with a run's first message, so the model can act at once
// instead of spending a call on each lookup.
const (
	maxContextTags    = 100
	maxReferenceRunes = 8000
)

// runContext is the space's tags and reference notes, as of the run's start.
type runContext struct {
	Tags       []map[string]any
	References []map[string]any
	// Missing are reference notes' titles no note in the space has.
	Missing []string
}

func (rs *runState) gather(ctx context.Context, c Config) (runContext, error) {
	var out runContext
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
