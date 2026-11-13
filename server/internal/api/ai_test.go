package api_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// fakeLLM is an OpenAI-compatible provider that answers with scripted
// replies, in order, and then repeats the last one. It records each request
// and the Authorization header it came with.
type fakeLLM struct {
	srv *httptest.Server

	mu       sync.Mutex
	replies  []fakeReply
	requests []map[string]any
	auth     []string
	models   fakeReply // GET /v1/models
}

type fakeReply struct {
	status int    // 0 = 200
	body   string // raw JSON
}

// toolCall is a reply that calls one tool.
func toolCall(name, args string) fakeReply {
	b, _ := json.Marshal(map[string]any{
		"model": "fake-model-1",
		"choices": []any{map[string]any{
			"finish_reason": "tool_calls",
			"message": map[string]any{"role": "assistant", "tool_calls": []any{map[string]any{
				"id": "call_1", "type": "function",
				"function": map[string]any{"name": name, "arguments": args},
			}}},
		}},
		"usage": map[string]any{"prompt_tokens": 50, "completion_tokens": 5},
	})
	return fakeReply{body: string(b)}
}

// final is a reply with text and no tool calls.
func final(text string) fakeReply {
	b, _ := json.Marshal(map[string]any{
		"model":   "fake-model-1",
		"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]any{"role": "assistant", "content": text}}},
		"usage":   map[string]any{"prompt_tokens": 50, "completion_tokens": 5},
	})
	return fakeReply{body: string(b)}
}

func newFakeLLM(t *testing.T, replies ...fakeReply) *fakeLLM {
	f := &fakeLLM{replies: replies, models: fakeReply{body: `{"object":"list","data":[{"id":"fake-model"}]}`}}
	f.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "GET" && r.URL.Path == "/v1/models" {
			f.mu.Lock()
			f.auth = append(f.auth, r.Header.Get("Authorization"))
			reply := f.models
			f.mu.Unlock()
			if reply.status != 0 {
				w.WriteHeader(reply.status)
			}
			_, _ = w.Write([]byte(reply.body))
			return
		}
		if r.Method != "POST" || r.URL.Path != "/v1/chat/completions" {
			http.NotFound(w, r)
			return
		}
		var m map[string]any
		_ = json.NewDecoder(r.Body).Decode(&m)
		f.mu.Lock()
		f.requests = append(f.requests, m)
		f.auth = append(f.auth, r.Header.Get("Authorization"))
		reply := final("ok")
		if len(f.replies) > 0 {
			reply = f.replies[0]
			if len(f.replies) > 1 {
				f.replies = f.replies[1:]
			}
		}
		f.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		if reply.status != 0 {
			w.WriteHeader(reply.status)
		}
		_, _ = w.Write([]byte(reply.body))
	}))
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeLLM) baseURL() string { return f.srv.URL + "/v1" }

// script replaces the replies still to come.
func (f *fakeLLM) script(replies ...fakeReply) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.replies = replies
}

func (f *fakeLLM) requestCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.requests)
}

func TestAISettings(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c, _ := e.deviceClient()

	r := c.do("GET", "/api/v1/settings/ai", nil)
	if r.status != 200 || r.body["configured"] != false || r.body["editable"] != true || r.body["dailyTokenCap"] != float64(1_000_000) {
		t.Fatalf("initial: %d %s", r.status, r.raw)
	}

	for _, tc := range []struct {
		body  map[string]any
		field string
	}{
		{map[string]any{"baseUrl": "https://api.example.com/v1", "model": "m"}, "apiKey"},
		{map[string]any{"baseUrl": "ftp://api.example.com", "apiKey": "sk-1234", "model": "m"}, "baseUrl"},
		{map[string]any{"baseUrl": "api.example.com", "apiKey": "sk-1234", "model": "m"}, "baseUrl"},
		{map[string]any{"baseUrl": "https://u:p@api.example.com", "apiKey": "sk-1234", "model": "m"}, "baseUrl"},
		{map[string]any{"baseUrl": "https://api.example.com/v1", "apiKey": "sk 1234", "model": "m"}, "apiKey"},
		{map[string]any{"baseUrl": "https://api.example.com/v1", "apiKey": "sk-1234", "model": " "}, "model"},
		{map[string]any{"baseUrl": "https://api.example.com/v1", "apiKey": "sk-1234", "model": "m", "dailyTokenCap": -1}, "dailyTokenCap"},
	} {
		if r := c.do("PUT", "/api/v1/settings/ai", tc.body); r.status != 400 || r.body["field"] != tc.field {
			t.Errorf("%v: want 400 on %s, got %d %s", tc.body, tc.field, r.status, r.raw)
		}
	}

	r = c.do("PUT", "/api/v1/settings/ai", map[string]any{
		"baseUrl": " https://api.example.com/v1/chat/completions ", "apiKey": "sk-secret-9876", "model": "gpt-test",
	})
	if r.status != 200 || r.body["configured"] != true || r.body["keyHint"] != "9876" ||
		r.body["baseUrl"] != "https://api.example.com/v1" || r.body["model"] != "gpt-test" || r.body["dailyTokenCap"] != float64(1_000_000) {
		t.Fatalf("save: %d %s", r.status, r.raw)
	}
	if strings.Contains(string(r.raw), "sk-secret-9876") {
		t.Fatalf("key returned: %s", r.raw)
	}

	// Changing the rest keeps the key.
	r = c.do("PUT", "/api/v1/settings/ai", map[string]any{"baseUrl": "http://localhost:11434/v1", "model": "llama", "dailyTokenCap": 5000})
	if r.status != 200 || r.body["keyHint"] != "9876" || r.body["model"] != "llama" || r.body["dailyTokenCap"] != float64(5000) {
		t.Fatalf("change model: %d %s", r.status, r.raw)
	}

	if r := c.do("DELETE", "/api/v1/settings/ai", nil); r.status != 204 {
		t.Fatalf("delete: %d %s", r.status, r.raw)
	}
	if r := c.do("GET", "/api/v1/settings/ai", nil); r.body["configured"] != false || r.body["keyHint"] != "" {
		t.Fatalf("after delete: %s", r.raw)
	}
	// A short key gets no hint: its last four would give much of it away.
	if r := c.do("PUT", "/api/v1/settings/ai", map[string]any{"baseUrl": "https://api.example.com/v1", "apiKey": "abcd", "model": "m"}); r.status != 200 || r.body["keyHint"] != "" {
		t.Fatalf("short key: %d %s", r.status, r.raw)
	}
	if r := c.do("DELETE", "/api/v1/settings/ai", nil); r.status != 204 {
		t.Fatalf("delete: %d %s", r.status, r.raw)
	}
	// The key went with it: saving again needs one.
	if r := c.do("PUT", "/api/v1/settings/ai", map[string]any{"baseUrl": "https://api.example.com/v1", "model": "m"}); r.status != 400 || r.body["field"] != "apiKey" {
		t.Fatalf("save after delete without key: %d %s", r.status, r.raw)
	}
}

func TestAITest(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c, _ := e.deviceClient()

	if r := c.do("POST", "/api/v1/settings/ai/test", nil); r.status != 409 || r.body["error"] != "ai_not_configured" {
		t.Fatalf("test before setup: %d %s", r.status, r.raw)
	}

	f := newFakeLLM(t, toolCall("ping", `{"ok":true}`))
	unsaved := map[string]any{"baseUrl": f.baseURL(), "apiKey": "sk-unsaved-1111", "model": "fake-model"}
	r := c.do("POST", "/api/v1/settings/ai/test", unsaved)
	if r.status != 200 || r.body["ok"] != true || r.body["model"] != "fake-model-1" {
		t.Fatalf("test unsaved: %d %s", r.status, r.raw)
	}
	if f.auth[0] != "Bearer sk-unsaved-1111" {
		t.Fatalf("auth header: %q", f.auth[0])
	}
	req := f.requests[0]
	tools, _ := req["tools"].([]any)
	if req["model"] != "fake-model" || len(tools) != 1 || req["tool_choice"] != nil {
		t.Fatalf("probe request: %v", req)
	}
	// Testing doesn't save.
	if r := c.do("GET", "/api/v1/settings/ai", nil); r.body["configured"] != false {
		t.Fatalf("test saved settings: %s", r.raw)
	}

	// Saved settings are tested with no body; an unsaved change without a key uses the saved one.
	if r := c.do("PUT", "/api/v1/settings/ai", unsaved); r.status != 200 {
		t.Fatalf("save: %d %s", r.status, r.raw)
	}
	if r := c.do("POST", "/api/v1/settings/ai/test", nil); r.status != 200 {
		t.Fatalf("test saved: %d %s", r.status, r.raw)
	}
	if r := c.do("POST", "/api/v1/settings/ai/test", map[string]any{"baseUrl": f.baseURL(), "model": "other"}); r.status != 200 || f.auth[2] != "Bearer sk-unsaved-1111" {
		t.Fatalf("test with saved key: %d %s", r.status, r.raw)
	}

	// A model that answers in text can't run agents.
	f.script(final("pong"))
	r = c.do("POST", "/api/v1/settings/ai/test", nil)
	if r.status != 502 || r.body["error"] != "ai_test_failed" || !strings.Contains(r.body["message"].(string), "tool calls") {
		t.Fatalf("no tool call: %d %s", r.status, r.raw)
	}

	// Arguments that aren't a JSON object would break every tool call.
	f.script(toolCall("ping", `not json`))
	r = c.do("POST", "/api/v1/settings/ai/test", nil)
	if r.status != 502 || r.body["error"] != "ai_test_failed" || !strings.Contains(r.body["message"].(string), "JSON object") {
		t.Fatalf("bad arguments: %d %s", r.status, r.raw)
	}

	// The provider's own explanation is passed on.
	f.script(fakeReply{status: 401, body: `{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}`})
	r = c.do("POST", "/api/v1/settings/ai/test", nil)
	if r.status != 502 || r.body["error"] != "ai_test_failed" || r.body["message"] != "Incorrect API key provided" {
		t.Fatalf("bad key: %d %s", r.status, r.raw)
	}

	// A wrong address says so.
	r = c.do("POST", "/api/v1/settings/ai/test", map[string]any{"baseUrl": f.srv.URL + "/nope", "model": "m"})
	if r.status != 502 || r.body["error"] != "ai_test_failed" {
		t.Fatalf("wrong path: %d %s", r.status, r.raw)
	}
}

func TestAIModels(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c, _ := e.deviceClient()
	f := newFakeLLM(t)
	f.models = fakeReply{body: `{"data":[{"id":"b-model","supported_parameters":["tools"]},{"id":"a-model"},{"id":"no-tools","supported_parameters":[]}]}`}

	// A model isn't needed to list them.
	r := c.do("POST", "/api/v1/settings/ai/models", map[string]any{"baseUrl": f.baseURL(), "apiKey": "sk-unsaved-1111"})
	if r.status != 200 || string(mustJSON(t, r.body["models"])) != `[{"id":"a-model"},{"id":"b-model"}]` {
		t.Fatalf("list: %d %s", r.status, r.raw)
	}
	if f.auth[0] != "Bearer sk-unsaved-1111" || f.requestCount() != 0 {
		t.Fatalf("auth %v, chat requests %d", f.auth, f.requestCount())
	}

	// Without a key it needs a saved one, and uses it.
	if r := c.do("POST", "/api/v1/settings/ai/models", map[string]any{"baseUrl": f.baseURL()}); r.status != 400 || r.body["field"] != "apiKey" {
		t.Fatalf("no key, none saved: %d %s", r.status, r.raw)
	}
	if r := c.do("PUT", "/api/v1/settings/ai", map[string]any{"baseUrl": f.baseURL(), "apiKey": "sk-saved-2222", "model": "a-model"}); r.status != 200 {
		t.Fatalf("save: %d %s", r.status, r.raw)
	}
	if r := c.do("POST", "/api/v1/settings/ai/models", map[string]any{"baseUrl": f.baseURL()}); r.status != 200 || f.auth[len(f.auth)-1] != "Bearer sk-saved-2222" {
		t.Fatalf("saved key: %d %s %v", r.status, r.raw, f.auth)
	}

	// The address is checked as when saving.
	if r := c.do("POST", "/api/v1/settings/ai/models", map[string]any{"baseUrl": "http://api.example.com/v1", "apiKey": "k"}); r.status != 400 || r.body["field"] != "baseUrl" {
		t.Fatalf("plain http outside: %d %s", r.status, r.raw)
	}

	// The provider's refusal is passed on.
	f.models = fakeReply{status: 401, body: `{"error":{"message":"Incorrect API key provided"}}`}
	r = c.do("POST", "/api/v1/settings/ai/models", map[string]any{"baseUrl": f.baseURL()})
	if r.status != 502 || r.body["error"] != "ai_failed" || r.body["message"] != "Incorrect API key provided" {
		t.Fatalf("refused: %d %s", r.status, r.raw)
	}
}

func mustJSON(t *testing.T, v any) []byte {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestOnlyAdminsChangeAISettings(t *testing.T) {
	f := newFakeLLM(t, toolCall("ping", `{"ok":true}`))
	e := newEnv(t)
	e.setupOwner()
	e.addKid()
	a := e.loginAs(owner, password)
	b := e.loginAs(kid, kidPassword)

	settings := map[string]any{"baseUrl": f.baseURL(), "apiKey": "sk-test-1234", "model": "m"}
	if r := b.do("PUT", "/api/v1/settings/ai", settings); r.status != 403 || r.body["error"] != "admin_only" {
		t.Fatalf("non-admin save: %d %s", r.status, r.raw)
	}
	if r := b.do("POST", "/api/v1/settings/ai/test", settings); r.status != 403 || f.requestCount() != 0 {
		t.Fatalf("non-admin test: %d %s", r.status, r.raw)
	}
	if r := b.do("POST", "/api/v1/settings/ai/models", settings); r.status != 403 || len(f.auth) != 0 {
		t.Fatalf("non-admin models: %d %s", r.status, r.raw)
	}
	if r := a.do("PUT", "/api/v1/settings/ai", settings); r.status != 200 {
		t.Fatalf("admin save: %d %s", r.status, r.raw)
	}
	r := b.do("GET", "/api/v1/settings/ai", nil)
	if r.status != 200 || r.body["configured"] != true || r.body["editable"] != false || r.body["keyHint"] != "1234" {
		t.Fatalf("non-admin sees the shared settings: %d %s", r.status, r.raw)
	}
	if r := b.do("DELETE", "/api/v1/settings/ai", nil); r.status != 403 {
		t.Fatalf("non-admin delete: %d", r.status)
	}
}
