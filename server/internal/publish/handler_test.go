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

func TestVisitorsBehindAProxyAreToldApart(t *testing.T) {
	r := httptest.NewRequest("GET", "/@h/search?q=x", nil)
	r.RemoteAddr = "127.0.0.1:5000"
	r.Header.Set("X-Forwarded-For", "198.51.100.1, 203.0.113.7")
	if got := visitorKey(r); got != "203.0.113.7" {
		t.Fatalf("the address the proxy added: %q", got)
	}
	// From the internet, the header is anyone's to write.
	r.RemoteAddr = "203.0.113.50:5000"
	if got := visitorKey(r); got != "203.0.113.50" {
		t.Fatalf("the connection's: %q", got)
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
