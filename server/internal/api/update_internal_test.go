package api

import "testing"

func TestNewerVersion(t *testing.T) {
	for _, c := range []struct {
		latest, current string
		want            bool
	}{
		{"v0.11.0", "v0.10.0", true},
		{"v0.10.0", "v0.9.9", true},
		{"v1.0.0", "v0.99.0", true},
		{"v0.10.0", "v0.10.0", false},
		{"v0.10.0", "v0.11.0", false},
		{"v0.11.0", "v0.10.0-4-g51d93ed", false},
		{"v0.11.0", "v0.11.0-rc1", false},
		{"v0.11.0", "dev", false},
		{"", "v0.10.0", false},
	} {
		if got := newerVersion(c.latest, c.current); got != c.want {
			t.Errorf("newerVersion(%q, %q) = %v", c.latest, c.current, got)
		}
	}
}

func TestAppVersion(t *testing.T) {
	for in, want := range map[string]bool{
		"1.2.3": true, "v1.2.3": true, "1.2.3-4-g51d93ed": true, "1.2.3-dirty": true, "1.2.3-4-g51d93ed-dirty": true,
		"1.2.3-rc1": false, "1.2.3+build": false, "0.0.0-dev": false, "0.0.0": false, "1.2.3-dev": false,
		"dev": false, "": false, "1.2": false, "1.2.3.4": false, "v": false, "1.x.3": false, "-1.2.3": false,
	} {
		if _, ok := appVersion(in); ok != want {
			t.Errorf("appVersion(%q) ok = %v", in, ok)
		}
	}
}

func TestPickReleases(t *testing.T) {
	tags := []string{"v2.1.0", "v2.0.0", "v1.10.0", "v1.9.2", "v1.10.0-rc1", "v0.12.0", "nightly", "v3.0.0"}
	for _, c := range []struct{ current, latest, major string }{
		{"v1.2.3", "v1.10.0", "v3.0.0"},
		{"v1.2.0-4-g51d93ed", "v1.10.0", "v3.0.0"},
		{"v0.11.0", "v0.12.0", "v3.0.0"},
		{"v3.0.0", "v3.0.0", ""},
		{"v4.0.0", "", ""},
		{"dev", "v3.0.0", ""},
	} {
		latest, major := pickReleases(tags, c.current)
		if latest != c.latest || major != c.major {
			t.Errorf("pickReleases(%s) = %q, %q; want %q, %q", c.current, latest, major, c.latest, c.major)
		}
	}
}

func TestNextLink(t *testing.T) {
	for h, want := range map[string]string{
		`<https://api.github.com/x?page=2>; rel="next", <https://api.github.com/x?page=5>; rel="last"`:  "https://api.github.com/x?page=2",
		`<https://api.github.com/x?page=1>; rel="prev", <https://api.github.com/x?page=1>; rel="first"`: "",
		"": "",
	} {
		if got := nextLink(h); got != want {
			t.Errorf("nextLink(%q) = %q", h, got)
		}
	}
}
