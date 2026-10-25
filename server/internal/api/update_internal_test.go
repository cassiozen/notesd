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
