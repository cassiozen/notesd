// Package clientip finds who sent a request behind reverse proxies, whose
// own address every visitor would otherwise share for per-visitor limits.
package clientip

import (
	"context"
	"net"
	"net/http"
	"net/netip"
	"strings"
)

// Where proxies connect from (this machine, LAN, containers, platforms). Cost: a
// LAN device reaching notesd directly can fake its address to dodge limits.
var trusted = []netip.Prefix{
	netip.MustParsePrefix("127.0.0.0/8"),
	netip.MustParsePrefix("::1/128"),
	netip.MustParsePrefix("10.0.0.0/8"),
	netip.MustParsePrefix("172.16.0.0/12"),
	netip.MustParsePrefix("192.168.0.0/16"),
	netip.MustParsePrefix("169.254.0.0/16"),
	netip.MustParsePrefix("fc00::/7"),
	netip.MustParsePrefix("fe80::/10"),
}

func isTrusted(a netip.Addr) bool {
	for _, p := range trusted {
		if p.Contains(a) {
			return true
		}
	}
	return false
}

// Resolve returns the client's address: from a trusted peer, header's (one the
// proxy always overwrites) if present, else X-Forwarded-For's.
func Resolve(r *http.Request, header string) string {
	peer, ok := addrOf(r.RemoteAddr)
	if !ok {
		return r.RemoteAddr
	}
	if !isTrusted(peer) {
		return peer.String()
	}
	if header != "" {
		if a, ok := addrOf(strings.TrimSpace(r.Header.Get(header))); ok {
			return a.String()
		}
	}
	// Each trusted hop appended who it got the request from, so the rightmost
	// untrusted entry is the client; anything left of it the client wrote.
	hops := strings.Split(strings.Join(r.Header.Values("X-Forwarded-For"), ","), ",")
	client := peer.String()
	for i := len(hops) - 1; i >= 0; i-- {
		a, ok := addrOf(strings.TrimSpace(hops[i]))
		if !ok {
			break
		}
		client = a.String()
		if !isTrusted(a) {
			break
		}
	}
	return client
}

// addrOf parses an address with or without a port, as X-Forwarded-For
// entries come both ways.
func addrOf(s string) (netip.Addr, bool) {
	if host, _, err := net.SplitHostPort(s); err == nil {
		s = host
	}
	a, err := netip.ParseAddr(strings.Trim(s, "[]"))
	if err != nil {
		return netip.Addr{}, false
	}
	return a.Unmap(), true
}

type ctxKey struct{}

// Middleware resolves each request's client once, for Of.
func Middleware(header string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ctx := context.WithValue(r.Context(), ctxKey{}, Resolve(r, header))
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// Of returns the client's address as Middleware resolved it, or the
// connection's for a request that didn't pass through it.
func Of(r *http.Request) string {
	if c, ok := r.Context().Value(ctxKey{}).(string); ok {
		return c
	}
	if a, ok := addrOf(r.RemoteAddr); ok {
		return a.String()
	}
	return r.RemoteAddr
}
