package agent

import (
	"context"
	"errors"
	"net/url"
	"strings"

	"notes/server/internal/llm"
	"notes/server/internal/spaces"
)

// moments groups triggers as the apps' agent page does, for the trait's summary.
var moments = []struct {
	words    string
	triggers []Trigger
}{
	{"while you write", []Trigger{NoteCreated, EditStarted, Menu}},
	{"when you’re done", []Trigger{NewNoteDone, EditDone}},
	{"when notes come and go", []Trigger{NoteDeleted, MovedIn, MovedOut}},
}

// Trait is the agent trait (architecture §7.8, §7.11): on while a space's agent is on and has a rule.
// configs reads the agents of the spaces asked about, wherever their files are. The AI provider ai
// reports is who their notes are exposed to; none set up is the trait's problem.
func Trait(configs func(ctx context.Context, ids []string) ([]Config, error), ai func(context.Context) (llm.Settings, error)) spaces.TraitSource {
	return func(ctx context.Context, ids []string) (map[string]spaces.Trait, error) {
		configs, err := configs(ctx, ids)
		if err != nil {
			return nil, err
		}
		wanted := map[string]bool{}
		for _, id := range ids {
			wanted[id] = true
		}
		out := map[string]spaces.Trait{}
		var settings *llm.Settings
		for _, c := range configs {
			summary := summaryOf(c)
			if !wanted[c.SpaceID] || !c.Enabled || summary == "" {
				continue
			}
			if settings == nil {
				st, err := ai(ctx)
				if err != nil && !errors.Is(err, llm.ErrNotConfigured) {
					return nil, err
				}
				settings = &st
			}
			t := spaces.Trait{Label: "Agent", Summary: summary}
			if host := hostOf(settings.BaseURL); host == "" {
				t.Problem = "AI isn’t set up"
			} else {
				t.Exposure = "Sent to the AI at " + host
				t.Audience = []string{"ai:" + host}
			}
			out[c.SpaceID] = t
		}
		return out, nil
	}
}

// summaryOf names the moments the agent has rules for: "While you write · when you’re done".
func summaryOf(c Config) string {
	var parts []string
	for _, m := range moments {
		for _, t := range m.triggers {
			if c.Triggers[t] != "" {
				parts = append(parts, m.words)
				break
			}
		}
	}
	if len(parts) == 0 {
		return ""
	}
	s := strings.Join(parts, " · ")
	return strings.ToUpper(s[:1]) + s[1:]
}

func hostOf(baseURL string) string {
	u, err := url.Parse(baseURL)
	if err != nil {
		return ""
	}
	return u.Host
}
