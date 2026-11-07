package llm

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestErrorMessage(t *testing.T) {
	for _, tc := range []struct {
		status int
		body   string
		want   string
	}{
		{401, `{"error":{"message":"Incorrect API key provided"}}`, "Incorrect API key provided"},
		{404, `{"error":"model 'llama9' not found"}`, "model 'llama9' not found"},
		{502, `<html><body>Bad Gateway</body></html>`, "Bad Gateway"},
		{500, ``, "Internal Server Error"},
		{400, `plain words`, "plain words"},
		{400, strings.Repeat("a", 299) + "é", strings.Repeat("a", 299) + "…"},
	} {
		if got := errorMessage(tc.status, []byte(tc.body)); got != tc.want {
			t.Errorf("%d %s: got %q, want %q", tc.status, tc.body, got, tc.want)
		}
	}
}

func TestChatRedirectIsReported(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "https://elsewhere.example/v1/chat/completions", http.StatusMovedPermanently)
	}))
	defer srv.Close()
	_, err := NewClient().Chat(context.Background(), Settings{BaseURL: srv.URL, APIKey: "k", Model: "m"}, ChatRequest{})
	var pe *ProviderError
	if !errors.As(err, &pe) || pe.Status != 301 {
		t.Fatalf("got %v", err)
	}
}

func TestChatCanceledWhileReadingIsNotAProviderError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"model":`))
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}))
	defer srv.Close()
	ctx, cancel := context.WithCancel(context.Background())
	c := NewClient()
	c.HTTP.Transport = cancelAfterHeaders{cancel}
	_, err := c.Chat(ctx, Settings{BaseURL: srv.URL, APIKey: "k", Model: "m"}, ChatRequest{})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("got %v", err)
	}
}

// Cancels once the response's headers are in, so the body read is what fails.
type cancelAfterHeaders struct{ cancel context.CancelFunc }

func (c cancelAfterHeaders) RoundTrip(r *http.Request) (*http.Response, error) {
	res, err := http.DefaultTransport.RoundTrip(r)
	c.cancel()
	return res, err
}

func TestChatUnreachable(t *testing.T) {
	srv := httptest.NewServer(http.NotFoundHandler())
	url := srv.URL
	srv.Close()
	_, err := NewClient().Chat(context.Background(), Settings{BaseURL: url, APIKey: "k", Model: "m"}, ChatRequest{})
	var pe *ProviderError
	if !errors.As(err, &pe) || pe.Status != 0 {
		t.Fatalf("got %v", err)
	}
}

func TestValidate(t *testing.T) {
	s, err := validate(Input{BaseURL: "https://api.example.com/v1/", APIKey: " k ", Model: " m "})
	if err != nil || s.BaseURL != "https://api.example.com/v1" || s.APIKey != "k" || s.Model != "m" || s.DailyTokenCap != DefaultDailyTokenCap {
		t.Fatalf("got %+v %v", s, err)
	}
	for _, url := range []string{"https://api.example.com/v1?x=1", "https://api.example.com/v1?", "https://api.example.com/v1#", "https://api.example.com/v1#x"} {
		if _, err := validate(Input{BaseURL: url, APIKey: "k", Model: "m"}); err == nil {
			t.Fatalf("%s: a query or fragment must be refused", url)
		}
	}
	for url, ok := range map[string]bool{
		"http://localhost:11434/v1":  true,
		"http://127.0.0.1:8080/v1":   true,
		"http://192.168.1.20/v1":     true,
		"http://[fd00::1]/v1":        true,
		"http://gpu.local/v1":        true,
		"http://ollama:11434/v1":     true,
		"http://api.example.com/v1":  false,
		"http://8.8.8.8/v1":          false,
		"https://api.example.com/v1": true,
	} {
		if _, err := validate(Input{BaseURL: url, APIKey: "k", Model: "m"}); (err == nil) != ok {
			t.Errorf("%s: got %v, want ok=%v", url, err, ok)
		}
	}
}

func TestChatErrorInSuccess(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"error":{"message":"Quota exceeded"}}`))
	}))
	defer srv.Close()
	_, err := NewClient().Chat(context.Background(), Settings{BaseURL: srv.URL, APIKey: "k", Model: "m"}, ChatRequest{})
	var pe *ProviderError
	if !errors.As(err, &pe) || pe.Message != "Quota exceeded" {
		t.Fatalf("got %v", err)
	}
}
