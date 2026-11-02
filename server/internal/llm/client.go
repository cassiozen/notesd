package llm

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Message is one chat message. Content is omitted rather than empty on an
// assistant message that only calls tools, which some providers require.
type Message struct {
	Role       string     `json:"role"` // system, user, assistant, tool
	Content    string     `json:"content,omitempty"`
	ToolCalls  []ToolCall `json:"tool_calls,omitempty"`
	ToolCallID string     `json:"tool_call_id,omitempty"`
}

// Tool is a function the model may call. Parameters is a JSON Schema object.
type Tool struct {
	Type     string       `json:"type"` // always "function"
	Function ToolFunction `json:"function"`
}

// ToolFunction describes a Tool.
type ToolFunction struct {
	Name        string          `json:"name"`
	Description string          `json:"description,omitempty"`
	Parameters  json.RawMessage `json:"parameters"`
}

// ToolCall is the model asking to call a tool. Arguments is a JSON object as
// a string, as the model wrote it: it may not be valid JSON.
type ToolCall struct {
	ID       string           `json:"id"`
	Type     string           `json:"type"`
	Function ToolCallFunction `json:"function"`
}

// ToolCallFunction names the tool and holds its arguments.
type ToolCallFunction struct {
	Name      string `json:"name"`
	Arguments string `json:"arguments"`
}

// ChatRequest is one call to /chat/completions. Model is filled in from the
// settings when empty.
type ChatRequest struct {
	Model    string    `json:"model"`
	Messages []Message `json:"messages"`
	Tools    []Tool    `json:"tools,omitempty"`
}

// Usage counts a call's tokens.
type Usage struct {
	PromptTokens     int64 `json:"prompt_tokens"`
	CompletionTokens int64 `json:"completion_tokens"`
}

// ChatResponse is the provider's answer, reduced to its first choice.
type ChatResponse struct {
	Model        string  // the model that answered, as the provider names it
	Message      Message // role assistant: text, tool calls, or both
	FinishReason string
	Usage        Usage
}

// ProviderError is the provider refusing or failing a call, or not being
// reachable. Message is meant for the admin: the provider's own explanation
// when it gave one.
type ProviderError struct {
	Status  int // 0 when there was no HTTP answer
	Message string
}

func (e *ProviderError) Error() string {
	if e.Status == 0 {
		return "ai provider: " + e.Message
	}
	return fmt.Sprintf("ai provider: %d %s", e.Status, e.Message)
}

// maxResponseBytes bounds what is read of an answer; a chat completion is a
// few kilobytes.
const maxResponseBytes = 4 << 20

// Client calls an OpenAI-compatible API. The address and key come with each
// call, from Settings, since an admin can change them at any time.
type Client struct {
	HTTP *http.Client
}

// NewClient returns a client with a timeout long enough for a slow model's
// answer.
func NewClient() *Client {
	return &Client{HTTP: &http.Client{
		Timeout: 2 * time.Minute,
		// A redirect would turn the POST into a GET; say where it pointed instead.
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}}
}

// Chat sends req with s and returns the first choice.
func (c *Client) Chat(ctx context.Context, s Settings, req ChatRequest) (*ChatResponse, error) {
	if req.Model == "" {
		req.Model = s.Model
	}
	body, err := json.Marshal(req)
	if err != nil {
		return nil, err
	}
	hr, err := http.NewRequestWithContext(ctx, http.MethodPost, s.BaseURL+"/chat/completions", bytes.NewReader(body))
	if err != nil {
		return nil, &ProviderError{Message: "the address isn't usable: " + err.Error()}
	}
	hr.Header.Set("Authorization", "Bearer "+s.APIKey)
	hr.Header.Set("Content-Type", "application/json")
	hr.Header.Set("Accept", "application/json")
	res, err := c.HTTP.Do(hr)
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, &ProviderError{Message: "couldn't reach " + hr.URL.Host + ": " + unwrapURLError(err)}
	}
	defer res.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(res.Body, maxResponseBytes))
	if err != nil {
		return nil, &ProviderError{Status: res.StatusCode, Message: "reading the answer: " + err.Error()}
	}
	if res.StatusCode/100 == 3 {
		return nil, &ProviderError{Status: res.StatusCode, Message: "the address redirects to " + res.Header.Get("Location") + "; use that address instead"}
	}
	if res.StatusCode/100 != 2 {
		return nil, &ProviderError{Status: res.StatusCode, Message: errorMessage(res.StatusCode, raw)}
	}
	var out struct {
		Model   string `json:"model"`
		Choices []struct {
			Message      Message `json:"message"`
			FinishReason string  `json:"finish_reason"`
		} `json:"choices"`
		Usage Usage `json:"usage"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, &ProviderError{Status: res.StatusCode, Message: "the answer isn't a chat completion; check the address"}
	}
	if len(out.Choices) == 0 {
		return nil, &ProviderError{Status: res.StatusCode, Message: "the answer has no choices"}
	}
	model := out.Model
	if model == "" {
		model = req.Model
	}
	return &ChatResponse{
		Model:        model,
		Message:      out.Choices[0].Message,
		FinishReason: out.Choices[0].FinishReason,
		Usage:        out.Usage,
	}, nil
}

// errorMessage extracts the explanation from an error answer: OpenAI's
// {"error": {"message": …}}, the {"error": "…"} some servers send instead,
// or the start of whatever else came back.
func errorMessage(status int, raw []byte) string {
	var obj struct {
		Error json.RawMessage `json:"error"`
	}
	if json.Unmarshal(raw, &obj) == nil && len(obj.Error) > 0 {
		var nested struct {
			Message string `json:"message"`
		}
		if json.Unmarshal(obj.Error, &nested) == nil && nested.Message != "" {
			return nested.Message
		}
		var flat string
		if json.Unmarshal(obj.Error, &flat) == nil && flat != "" {
			return flat
		}
	}
	text := strings.TrimSpace(string(raw))
	if text == "" || strings.HasPrefix(text, "<") {
		return http.StatusText(status)
	}
	if len(text) > 300 {
		text = text[:300] + "…"
	}
	return text
}

// unwrapURLError drops url.Error's "Post <url>:" prefix; the host is
// already in the message.
func unwrapURLError(err error) string {
	var ue *url.Error
	if errors.As(err, &ue) {
		return ue.Err.Error()
	}
	return err.Error()
}
