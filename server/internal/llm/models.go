package llm

import (
	"context"
	"encoding/json"
	"net/http"
	"slices"
	"strings"
)

// Model is one model a provider lists.
type Model struct {
	ID string `json:"id"`
}

// OpenRouter's list, with descriptions and prices for hundreds of models,
// runs to megabytes.
const maxModelsBytes = 16 << 20

// Models lists GET /models, sorted. Entries that list their parameters (OpenRouter)
// are dropped without tools; other providers don't say, so Test is the real check.
func (c *Client) Models(ctx context.Context, s Settings) ([]Model, error) {
	raw, status, err := c.send(ctx, s, http.MethodGet, "/models", nil, maxModelsBytes)
	if err != nil {
		return nil, err
	}
	var out struct {
		Data []struct {
			ID                  string   `json:"id"`
			SupportedParameters []string `json:"supported_parameters"`
		} `json:"data"`
	}
	if err := json.Unmarshal(raw, &out); err != nil || out.Data == nil {
		return nil, &ProviderError{Status: status, Message: "the answer isn't a list of models; check the address"}
	}
	models := make([]Model, 0, len(out.Data))
	seen := make(map[string]bool, len(out.Data))
	for _, m := range out.Data {
		id := strings.TrimSpace(m.ID)
		if id == "" || seen[id] {
			continue
		}
		if m.SupportedParameters != nil && !slices.Contains(m.SupportedParameters, "tools") {
			continue
		}
		seen[id] = true
		models = append(models, Model{ID: id})
	}
	slices.SortFunc(models, func(a, b Model) int { return strings.Compare(a.ID, b.ID) })
	return models, nil
}
