package agent

import (
	"context"
	"database/sql"
	"encoding/json"
	"strings"
)

// Carried is a space's agent on its way to the shared space's own file, when the space is shared
// (§7.9, §7.11): its setup, finished runs with their edits and cards, and its token use.
type Carried struct {
	tables []carriedTable
}

type carriedTable struct {
	name    string
	columns []string
	rows    [][]any
}

// finishedRuns is a space's runs that ended: queued and running ones stay behind, and are dropped with the space.
const finishedRuns = `SELECT id FROM agent_runs WHERE space_id = ? AND status NOT IN ('queued', 'running')`

// carriedQueries are what a space's agent takes along, parents before children, each with one argument: the space.
var carriedQueries = []struct{ table, where string }{
	{"agent_spaces", "space_id = ?"},
	{"agent_triggers", "space_id = ?"},
	{"agent_runs", "id IN (" + finishedRuns + ")"},
	{"agent_run_edits", "run_id IN (" + finishedRuns + ")"},
	{"agent_overlays", "run_id IN (" + finishedRuns + ")"},
	{"agent_usage", "space_id = ?"},
}

// Export reads what a space's agent takes along when the space is shared. owner is the account
// sharing it: a run with no account of its own was for them, which the new file has to say.
func (s *Store) Export(ctx context.Context, spaceID string, owner int64) (Carried, error) {
	var c Carried
	for _, q := range carriedQueries {
		t, err := readTable(ctx, s.db.R, q.table, q.where, spaceID)
		if err != nil {
			return Carried{}, err
		}
		if q.table == "agent_runs" {
			if err := t.setBy(owner); err != nil {
				return Carried{}, err
			}
		}
		c.tables = append(c.tables, t)
	}
	return c, nil
}

// ImportTx writes a space's carried agent into the shared space's file, in its transaction.
func ImportTx(ctx context.Context, tx *sql.Tx, c Carried) error {
	for _, t := range c.tables {
		stmt := `INSERT INTO ` + t.name + ` (` + strings.Join(t.columns, ", ") + `) VALUES (?` +
			strings.Repeat(", ?", len(t.columns)-1) + `) ON CONFLICT DO NOTHING`
		for _, row := range t.rows {
			if _, err := tx.ExecContext(ctx, stmt, row...); err != nil {
				return err
			}
		}
	}
	return nil
}

// SpaceLeft drops what this file keeps of a space's agent once the space moves to its own file
// (notes.Store.OnSpaceLeft): the writing sessions and queue go, the rest went along with Export.
func (s *Store) SpaceLeft(ctx context.Context, tx *sql.Tx, spaceID string) error {
	for _, q := range []string{
		`DELETE FROM agent_sessions WHERE note_id IN (SELECT id FROM notes WHERE space_id = ?)`,
		`DELETE FROM agent_runs WHERE space_id = ?`, // edits and cards go with them
		`DELETE FROM agent_usage WHERE space_id = ?`,
		`DELETE FROM agent_spaces WHERE space_id = ?`, // and its triggers
	} {
		if _, err := tx.ExecContext(ctx, q, spaceID); err != nil {
			return err
		}
	}
	return nil
}

func readTable(ctx context.Context, db *sql.DB, table, where, spaceID string) (carriedTable, error) {
	rows, err := db.QueryContext(ctx, `SELECT * FROM `+table+` WHERE `+where, spaceID)
	if err != nil {
		return carriedTable{}, err
	}
	defer rows.Close()
	cols, err := rows.Columns()
	if err != nil {
		return carriedTable{}, err
	}
	t := carriedTable{name: table, columns: cols}
	for rows.Next() {
		row := make([]any, len(cols))
		ptrs := make([]any, len(cols))
		for i := range row {
			ptrs[i] = &row[i]
		}
		if err := rows.Scan(ptrs...); err != nil {
			return carriedTable{}, err
		}
		t.rows = append(t.rows, row)
	}
	return t, rows.Err()
}

// setBy fills in each run's account where its event names none.
func (t *carriedTable) setBy(owner int64) error {
	col := -1
	for i, c := range t.columns {
		if c == "event_json" {
			col = i
		}
	}
	if col < 0 {
		return nil
	}
	for _, row := range t.rows {
		var raw string
		switch v := row[col].(type) {
		case string:
			raw = v
		case []byte:
			raw = string(v)
		default:
			continue
		}
		var e Event
		if err := json.Unmarshal([]byte(raw), &e); err != nil {
			return err
		}
		if e.By != 0 {
			continue
		}
		e.By = owner
		b, err := json.Marshal(e)
		if err != nil {
			return err
		}
		row[col] = string(b)
	}
	return nil
}
