package llm

import (
	"context"
	"encoding/json"
	"errors"
	"time"
)

// TestError is the probe failing: the provider refused, or the model
// answered without calling the tool. Message is for the admin.
type TestError struct{ Message string }

func (e *TestError) Error() string { return "ai test: " + e.Message }

// TestResult is a passed probe.
type TestResult struct {
	Model   string // as the provider named it in its answer
	Latency time.Duration
}

var pingTool = Tool{Type: "function", Function: ToolFunction{
	Name:        "ping",
	Description: "Confirms the connection works.",
	Parameters:  json.RawMessage(`{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"],"additionalProperties":false}`),
}}

// Test checks s end to end with one small call: the address answers, the key
// is accepted, the model exists, and it calls tools, which agents can't work
// without. tool_choice is left to the model, as in real runs, so a model that
// only calls tools when forced fails here rather than in every run.
func (c *Client) Test(ctx context.Context, s Settings) (TestResult, error) {
	start := time.Now()
	res, err := c.Chat(ctx, s, ChatRequest{
		Messages: []Message{
			{Role: "system", Content: "You are checking that tool calls work. Reply only by calling the ping tool."},
			{Role: "user", Content: "Call ping with ok set to true."},
		},
		Tools: []Tool{pingTool},
	})
	if err != nil {
		var pe *ProviderError
		if errors.As(err, &pe) {
			return TestResult{}, &TestError{Message: pe.Message}
		}
		return TestResult{}, err
	}
	for _, tc := range res.Message.ToolCalls {
		var args map[string]any
		if tc.Function.Name == pingTool.Function.Name && json.Unmarshal([]byte(tc.Function.Arguments), &args) == nil && args != nil {
			return TestResult{Model: res.Model, Latency: time.Since(start)}, nil
		}
	}
	if len(res.Message.ToolCalls) > 0 {
		return TestResult{}, &TestError{Message: res.Model + " called a tool with arguments that aren't a JSON object. Agents need a model that supports tool calls."}
	}
	return TestResult{}, &TestError{Message: res.Model + " answered without calling a tool. Agents need a model that supports tool calls."}
}
