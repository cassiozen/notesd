package llm

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
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
	if _, err := validate(Input{BaseURL: "https://api.example.com/v1?x=1", APIKey: "k", Model: "m"}); err == nil {
		t.Fatal("a query string must be refused")
	}
}
