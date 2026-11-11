package clientip

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func req(remote string, hdr ...string) *http.Request {
	r := httptest.NewRequest("GET", "/", nil)
	r.RemoteAddr = remote
	for i := 0; i+1 < len(hdr); i += 2 {
		r.Header.Add(hdr[i], hdr[i+1])
	}
	return r
}

func TestResolve(t *testing.T) {
	const xff = "X-Forwarded-For"
	cases := []struct {
		name   string
		r      *http.Request
		header string
		want   string
	}{
		{"no proxy", req("203.0.113.5:4000"), "", "203.0.113.5"},
		{"from the internet the header is anyone's", req("203.0.113.5:4000", xff, "198.51.100.1"), "", "203.0.113.5"},
		{"a proxy on this machine", req("127.0.0.1:4000", xff, "198.51.100.1, 203.0.113.7"), "", "203.0.113.7"},
		{"chained private hops", req("172.17.0.1:4000", xff, "198.51.100.1, 203.0.113.7, 10.0.0.2"), "", "203.0.113.7"},
		{"header split over lines", req("[::1]:4000", xff, "203.0.113.7", xff, "10.0.0.2"), "", "203.0.113.7"},
		{"entry with a port", req("127.0.0.1:4000", xff, "[2001:db8::1]:5555"), "", "2001:db8::1"},
		{"IPv4-mapped peer", req("[::ffff:127.0.0.1]:4000", xff, "203.0.113.7"), "", "203.0.113.7"},
		{"proxy without the header", req("127.0.0.1:4000"), "", "127.0.0.1"},
		{"garbage stops at the last good hop", req("127.0.0.1:4000", xff, "203.0.113.7, nonsense, 10.0.0.2"), "", "10.0.0.2"},
		{"all hops private", req("127.0.0.1:4000", xff, "10.0.0.3, 10.0.0.2"), "", "10.0.0.3"},
		{"a LAN client", req("192.168.1.20:4000"), "", "192.168.1.20"},
		// A proxy that ends X-Forwarded-For with its own public address.
		{"the named header", req("172.16.13.2:4000", xff, "198.51.100.1, 203.0.113.7, 192.0.2.80", "X-Real-IP", "203.0.113.7"), "X-Real-IP", "203.0.113.7"},
		{"the named header, absent", req("172.16.13.2:4000", xff, "203.0.113.7"), "X-Real-IP", "203.0.113.7"},
		{"the named header, garbage", req("172.16.13.2:4000", xff, "203.0.113.7", "X-Real-IP", "x"), "X-Real-IP", "203.0.113.7"},
		{"the named header from the internet", req("203.0.113.5:4000", "X-Real-IP", "198.51.100.1"), "X-Real-IP", "203.0.113.5"},
	}
	for _, c := range cases {
		if got := Resolve(c.r, c.header); got != c.want {
			t.Errorf("%s: got %q, want %q", c.name, got, c.want)
		}
	}
}

func TestOf(t *testing.T) {
	var seen string
	h := Middleware("", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { seen = Of(r) }))
	h.ServeHTTP(httptest.NewRecorder(), req("127.0.0.1:4000", "X-Forwarded-For", "203.0.113.7"))
	if seen != "203.0.113.7" {
		t.Errorf("through the middleware: %q", seen)
	}
	if got := Of(req("203.0.113.5:4000", "X-Forwarded-For", "198.51.100.1")); got != "203.0.113.5" {
		t.Errorf("without it, the connection's: %q", got)
	}
}
