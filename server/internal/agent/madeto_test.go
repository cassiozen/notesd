package agent_test

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"notes/server/internal/agent"
)

// The cases every client's madeTo passes too (shared/agent-fixtures/made-to.json).
func TestMadeToSharedCases(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "shared", "agent-fixtures", "made-to.json"))
	if err != nil {
		t.Fatal(err)
	}
	var suite struct {
		Cases []struct {
			Name   string          `json:"name"`
			Before agent.Fields    `json:"before"`
			After  agent.Fields    `json:"after"`
			Op     json.RawMessage `json:"op"`
			Now    agent.Fields    `json:"now"`
			Cursor *int            `json:"cursor"`
			Want   *agent.Fields   `json:"want"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(raw, &suite); err != nil {
		t.Fatal(err)
	}
	if len(suite.Cases) == 0 {
		t.Fatal("no cases")
	}
	for _, c := range suite.Cases {
		t.Run(c.Name, func(t *testing.T) {
			change := agent.EditorChange{Before: c.Before, After: c.After}
			// The op's type is the tool's own: it comes in as JSON, as from the model.
			if err := json.Unmarshal([]byte(`{"op":`+string(c.Op)+`}`), &change); err != nil {
				t.Fatal(err)
			}
			got, ok := change.MadeTo(c.Now, c.Cursor)
			switch {
			case c.Want == nil && ok:
				t.Fatalf("made %+v, want it not to fit", got)
			case c.Want != nil && !ok:
				t.Fatalf("didn't fit, want %+v", *c.Want)
			case c.Want != nil && got != *c.Want:
				t.Fatalf("got %+v, want %+v", got, *c.Want)
			}
		})
	}
}
