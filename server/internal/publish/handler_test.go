package publish

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestSearchAnswersBusyWhenItsSlotsAreTaken(t *testing.T) {
	s := New(nil, nil, nil, Config{DataDir: t.TempDir()})
	s.sites["h"] = Site{Handle: "h", Published: true}
	s.manifests["h"] = &manifest{Routes: map[string]string{}}
	for i := 0; i < cap(s.limits.busy); i++ {
		s.limits.busy <- struct{}{}
	}
	w := httptest.NewRecorder()
	s.ServeHTTP(w, httptest.NewRequest("GET", "/@h/search?q=milk", nil))
	if w.Code != http.StatusServiceUnavailable || w.Header().Get("Retry-After") == "" {
		t.Fatalf("busy: %d %v", w.Code, w.Header())
	}
}

func TestGzipOnlyWhenTheBrowserTakesIt(t *testing.T) {
	for enc, want := range map[string]bool{
		"gzip": true, "br, gzip;q=0.5": true, "GZIP; q=1": true,
		"": false, "br": false, "gzip;q=0": false, "gzip;q=0.0": false, "gzip; q=0.000": false, "gzip;q=x": false,
	} {
		r := httptest.NewRequest("GET", "/", nil)
		r.Header.Set("Accept-Encoding", enc)
		if got := acceptsGzip(r); got != want {
			t.Errorf("%q: %v", enc, got)
		}
	}
}
