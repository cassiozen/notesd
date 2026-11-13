package llm

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"slices"
	"testing"
)

func serveModels(t *testing.T, status int, body string) *httptest.Server {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/v1/models" || r.Header.Get("Authorization") != "Bearer k" {
			http.NotFound(w, r)
			return
		}
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(srv.Close)
	return srv
}

func modelIDs(t *testing.T, srv *httptest.Server) []string {
	t.Helper()
	models, err := NewClient().Models(context.Background(), Settings{BaseURL: srv.URL + "/v1", APIKey: "k"})
	if err != nil {
		t.Fatal(err)
	}
	ids := []string{}
	for _, m := range models {
		ids = append(ids, m.ID)
	}
	return ids
}

func TestModelsSortedAndDeduplicated(t *testing.T) {
	srv := serveModels(t, 200, `{"object":"list","data":[{"id":"gpt-b","object":"model"},{"id":"gpt-a"},{"id":"gpt-b"},{"id":" "}]}`)
	if got := modelIDs(t, srv); !slices.Equal(got, []string{"gpt-a", "gpt-b"}) {
		t.Fatalf("got %v", got)
	}
}

func TestModelsLeaveOutThoseListedWithoutTools(t *testing.T) {
	srv := serveModels(t, 200, `{"data":[
		{"id":"vendor/tools","supported_parameters":["temperature","tools"]},
		{"id":"vendor/plain","supported_parameters":["temperature"]},
		{"id":"vendor/unsaid"}
	]}`)
	if got := modelIDs(t, srv); !slices.Equal(got, []string{"vendor/tools", "vendor/unsaid"}) {
		t.Fatalf("got %v", got)
	}
}

func TestModelsEmptyList(t *testing.T) {
	srv := serveModels(t, 200, `{"data":[]}`)
	if got := modelIDs(t, srv); len(got) != 0 {
		t.Fatalf("got %v", got)
	}
}

func TestModelsNotAList(t *testing.T) {
	for _, body := range []string{`<html></html>`, `{"models":[]}`} {
		srv := serveModels(t, 200, body)
		_, err := NewClient().Models(context.Background(), Settings{BaseURL: srv.URL + "/v1", APIKey: "k"})
		var pe *ProviderError
		if !errors.As(err, &pe) || pe.Message != "the answer isn't a list of models; check the address" {
			t.Fatalf("%s: got %v", body, err)
		}
	}
}

func TestModelsProviderRefuses(t *testing.T) {
	srv := serveModels(t, 401, `{"error":{"message":"Incorrect API key provided"}}`)
	_, err := NewClient().Models(context.Background(), Settings{BaseURL: srv.URL + "/v1", APIKey: "k"})
	var pe *ProviderError
	if !errors.As(err, &pe) || pe.Status != 401 || pe.Message != "Incorrect API key provided" {
		t.Fatalf("got %v", err)
	}
}
