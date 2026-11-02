package api_test

import (
	"net/http"
	"testing"

	"notes/server/internal/api"
)

func TestServerVersionHandshake(t *testing.T) {
	e := newEnv(t)
	c := e.client()
	r := c.do("GET", "/api/v1/auth/status", nil)
	if r.body["serverVersion"] != "v1.2.3" || r.body["apiVersion"] != 1.0 || r.body["setupRequired"] != true {
		t.Fatalf("status: %s", r.raw)
	}
	e.setupOwner()
	for _, p := range []string{"/api/v1/auth/status", "/api/v1/notes", "/api/v1/nope", "/healthz", "/"} {
		if r := c.do("GET", p, nil); r.header.Get("X-Notesd-Server") != "v1.2.3" {
			t.Errorf("GET %s: X-Notesd-Server %q", p, r.header.Get("X-Notesd-Server"))
		}
	}
	r = c.do("POST", "/api/v1/auth/login", map[string]string{"username": owner, "password": password})
	if r.body["serverVersion"] != "v1.2.3" {
		t.Fatalf("web login: %s", r.raw)
	}
	r = c.do("POST", "/api/v1/auth/login", map[string]string{"username": owner, "password": password, "client": "device"})
	if r.body["serverVersion"] != "v1.2.3" {
		t.Fatalf("device login: %s", r.raw)
	}
	r = c.do("POST", "/api/v1/auth/refresh", map[string]string{"refreshToken": r.body["refreshToken"].(string)})
	if r.status != 200 || r.body["serverVersion"] != "v1.2.3" {
		t.Fatalf("refresh: %d %s", r.status, r.raw)
	}
}

func TestDevServerVersion(t *testing.T) {
	e := newEnvWith(t, api.Config{})
	r := e.client().do("GET", "/api/v1/auth/status", nil)
	if r.body["serverVersion"] != "dev" || r.header.Get("X-Notesd-Server") != "dev" {
		t.Fatalf("status: %v %s", r.header, r.raw)
	}
}

func TestMinClient(t *testing.T) {
	e := newEnvWith(t, api.Config{Version: "v1.2.3", MinClient: map[string]string{"android": "1.2.0", "ios": "v1.1.0", "web": "nonsense"}})
	e.setupOwner()
	c, _ := e.deviceClient()
	for _, tc := range []struct {
		name       string
		hdr        []string
		want       int
		minVersion string // on 426: the platform's own minimum
	}{
		{"dev build", []string{"X-Notesd-Client", "android/0.0.0-dev"}, 200, ""},
		{"release candidate", []string{"X-Notesd-Client", "android/1.0.0-rc1"}, 200, ""},
		{"older android", []string{"X-Notesd-Client", "android/1.1.9"}, 426, "1.2.0"},
		{"older android, v prefix", []string{"X-Notesd-Client", "android/v1.1.9"}, 426, "1.2.0"},
		{"older android by user agent", []string{"User-Agent", "notesd-android/1.0.0 okhttp/4.12"}, 426, "1.2.0"},
		{"header wins over user agent", []string{"X-Notesd-Client", "android/1.2.0", "User-Agent", "notesd-android/1.0.0"}, 200, ""},
		{"minimum android", []string{"X-Notesd-Client", "android/1.2.0"}, 200, ""},
		{"newer android", []string{"X-Notesd-Client", "android/1.10.0"}, 200, ""},
		{"between releases", []string{"X-Notesd-Client", "android/1.2.0-4-g51d93ed"}, 200, ""},
		{"older between releases", []string{"X-Notesd-Client", "android/1.1.0-4-g51d93ed"}, 426, "1.2.0"},
		{"minimum with v prefix", []string{"X-Notesd-Client", "ios/1.0.9"}, 426, "1.1.0"},
		{"newer ios", []string{"X-Notesd-Client", "ios/1.1.0"}, 200, ""},
		{"unparseable minimum", []string{"X-Notesd-Client", "web/0.0.1"}, 200, ""},
		{"unparseable app", []string{"X-Notesd-Client", "android/dev"}, 200, ""},
		{"unknown platform", []string{"X-Notesd-Client", "desktop/0.0.1"}, 200, ""},
		{"malformed header", []string{"X-Notesd-Client", "android"}, 200, ""},
		{"no header", nil, 200, ""},
		{"other user agent", []string{"User-Agent", "Mozilla/5.0"}, 200, ""},
	} {
		r := c.do("GET", "/api/v1/notes", nil, tc.hdr...)
		if r.status != tc.want {
			t.Errorf("%s: %d %s", tc.name, r.status, r.raw)
			continue
		}
		if tc.want == 426 && (r.body["error"] != "upgrade_required" || r.body["message"] == "" || r.body["minVersion"] != tc.minVersion) {
			t.Errorf("%s: %s", tc.name, r.raw)
		}
	}
	// Status stays open, so an app can still tell what it's talking to.
	if r := c.do("GET", "/api/v1/auth/status", nil, "X-Notesd-Client", "android/0.1.0"); r.status != 200 {
		t.Fatalf("status: %d %s", r.status, r.raw)
	}
	if r := e.client().do("POST", "/api/v1/auth/login", map[string]string{"username": owner, "password": password},
		"X-Notesd-Client", "android/0.1.0"); r.status != http.StatusUpgradeRequired {
		t.Fatalf("login: %d %s", r.status, r.raw)
	}
}
