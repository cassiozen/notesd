// Package agent runs each space's agent: its setup, the account settings it
// needs, and (in later files) the triggers, the run queue and the runs.
package agent

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"
	"unicode/utf8"

	"notes/server/internal/database"
)

// Trigger is an event a space's agent can act on.
type Trigger string

// Triggers. The values are stored, and are the API's keys.
const (
	NoteCreated Trigger = "noteCreated" // a new note opened in an editor, before anything is typed
	EditStarted Trigger = "editStarted" // the first change to an existing note in an editor
	NewNoteDone Trigger = "newNoteDone" // a writing session on a new note ended
	EditDone    Trigger = "editDone"    // a writing session on an existing note ended
	NoteDeleted Trigger = "noteDeleted"
	MovedIn     Trigger = "movedIn"
	MovedOut    Trigger = "movedOut"
	Menu        Trigger = "menu" // the user picked the space's command in a note's menu
)

// Triggers is every trigger, in the order apps show them.
var Triggers = []Trigger{NoteCreated, EditStarted, NewNoteDone, EditDone, NoteDeleted, MovedIn, MovedOut, Menu}

func (t Trigger) valid() bool {
	for _, k := range Triggers {
		if t == k {
			return true
		}
	}
	return false
}

// Limits on what a user writes.
const (
	MaxPromptBytes = 16 << 10
	MaxMenuLabel   = 40 // characters
)

// Config is a space's agent. Triggers holds a prompt for each trigger it
// acts on; one without a prompt does nothing.
type Config struct {
	SpaceID   string             `json:"spaceId"`
	Enabled   bool               `json:"enabled"`
	Prompt    string             `json:"prompt"`
	MenuLabel string             `json:"menuLabel"`
	Triggers  map[Trigger]string `json:"triggers"`
	UpdatedAt int64              `json:"updatedAt"`
}

// Acts reports whether the agent is on and has a prompt for t.
func (c Config) Acts(t Trigger) bool { return c.Enabled && c.Triggers[t] != "" }

// ErrSpaceNotFound means the space doesn't exist.
var ErrSpaceNotFound = errors.New("space not found")

// InputError reports an invalid setting.
type InputError struct{ Field, Message string }

func (e *InputError) Error() string { return e.Field + ": " + e.Message }

// Store holds an account's agent setup and settings, in its notes database.
type Store struct {
	db  *database.DB
	now func() time.Time
	tz  tzCache
}

// NewStore returns a Store over an account's database.
func NewStore(db *database.DB) *Store {
	return &Store{db: db, now: time.Now}
}

// SetClock overrides the clock, for tests.
func (s *Store) SetClock(now func() time.Time) { s.now = now }

// Configs returns every space's agent that has been set up.
func (s *Store) Configs(ctx context.Context) ([]Config, error) {
	return readConfigs(ctx, s.db.R, "")
}

// Config returns a space's agent: off and empty if none was set up.
func (s *Store) Config(ctx context.Context, spaceID string) (Config, error) {
	var c Config
	err := s.db.ReadTx(ctx, func(tx *sql.Tx) (err error) {
		c, err = configIn(ctx, tx, spaceID)
		return err
	})
	return c, err
}

type querier interface {
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

// configIn is Config inside a transaction, or on the read pool.
func configIn(ctx context.Context, q querier, spaceID string) (Config, error) {
	if err := spaceExists(ctx, q, spaceID); err != nil {
		return Config{}, err
	}
	cs, err := readConfigs(ctx, q, spaceID)
	if err != nil || len(cs) == 0 {
		return Config{SpaceID: spaceID, Triggers: map[Trigger]string{}}, err
	}
	return cs[0], nil
}

// spaceExists is nil for a space that exists, else ErrSpaceNotFound.
func spaceExists(ctx context.Context, q querier, spaceID string) error {
	var one int
	err := q.QueryRowContext(ctx, `SELECT 1 FROM spaces WHERE id = ?`, spaceID).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrSpaceNotFound
	}
	return err
}

func readConfigs(ctx context.Context, q querier, spaceID string) ([]Config, error) {
	rows, err := q.QueryContext(ctx, `
		SELECT a.space_id, a.enabled, a.prompt, a.menu_label, a.updated_at, t.trigger, t.prompt
		FROM agent_spaces a LEFT JOIN agent_triggers t ON t.space_id = a.space_id
		WHERE ? = '' OR a.space_id = ?
		ORDER BY a.space_id`, spaceID, spaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Config
	for rows.Next() {
		var c Config
		var trigger, prompt sql.NullString
		if err := rows.Scan(&c.SpaceID, &c.Enabled, &c.Prompt, &c.MenuLabel, &c.UpdatedAt, &trigger, &prompt); err != nil {
			return nil, err
		}
		if len(out) == 0 || out[len(out)-1].SpaceID != c.SpaceID {
			c.Triggers = map[Trigger]string{}
			out = append(out, c)
		}
		if trigger.Valid {
			out[len(out)-1].Triggers[Trigger(trigger.String)] = prompt.String
		}
	}
	return out, rows.Err()
}

// Put replaces a space's agent with c and returns it as stored. Prompts are
// trimmed; a trigger with an empty prompt is dropped, and so is a trigger
// this server doesn't know.
func (s *Store) Put(ctx context.Context, c Config) (Config, error) {
	c, err := clean(c)
	if err != nil {
		return Config{}, err
	}
	c.UpdatedAt = s.now().UnixMilli()
	err = s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		if err := spaceExists(ctx, tx, c.SpaceID); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO agent_spaces (space_id, enabled, prompt, menu_label, updated_at) VALUES (?, ?, ?, ?, ?)
			ON CONFLICT(space_id) DO UPDATE SET enabled = excluded.enabled, prompt = excluded.prompt,
				menu_label = excluded.menu_label, updated_at = excluded.updated_at`,
			c.SpaceID, c.Enabled, c.Prompt, c.MenuLabel, c.UpdatedAt); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM agent_triggers WHERE space_id = ?`, c.SpaceID); err != nil {
			return err
		}
		for t, p := range c.Triggers {
			if _, err := tx.ExecContext(ctx, `INSERT INTO agent_triggers (space_id, trigger, prompt) VALUES (?, ?, ?)`,
				c.SpaceID, string(t), p); err != nil {
				return err
			}
		}
		return nil
	})
	return c, err
}

// Delete removes a space's agent and everything written for it.
// ErrSpaceNotFound for a space that doesn't exist.
func (s *Store) Delete(ctx context.Context, spaceID string) error {
	return s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		if err := spaceExists(ctx, tx, spaceID); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `DELETE FROM agent_spaces WHERE space_id = ?`, spaceID)
		return err
	})
}

func clean(c Config) (Config, error) {
	c.Prompt = strings.TrimSpace(c.Prompt)
	if err := checkPrompt("prompt", c.Prompt); err != nil {
		return c, err
	}
	c.MenuLabel = strings.Join(strings.Fields(c.MenuLabel), " ")
	if utf8.RuneCountInString(c.MenuLabel) > MaxMenuLabel {
		return c, &InputError{Field: "menuLabel", Message: "keep the command's name to 40 characters"}
	}
	triggers := map[Trigger]string{}
	for t, p := range c.Triggers {
		p = strings.TrimSpace(p)
		if !t.valid() || p == "" {
			continue
		}
		if err := checkPrompt("triggers."+string(t), p); err != nil {
			return c, err
		}
		triggers[t] = p
	}
	c.Triggers = triggers
	if triggers[Menu] != "" && c.MenuLabel == "" {
		return c, &InputError{Field: "menuLabel", Message: "name the command, such as Fix typos"}
	}
	return c, nil
}

func checkPrompt(field, p string) error {
	if len(p) > MaxPromptBytes {
		return &InputError{Field: field, Message: "keep a prompt to 16 KiB"}
	}
	if !utf8.ValidString(p) || strings.ContainsRune(p, 0) {
		return &InputError{Field: field, Message: "must be text"}
	}
	return nil
}
