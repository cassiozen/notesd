package mdexport

import (
	"strings"
	"testing"
)

func TestUniqueNameIgnoresCaseAndComposition(t *testing.T) {
	taken := map[string]bool{}
	first, second := UniqueName("Café", taken), UniqueName("CAFE\u0301", taken)
	if first != "Café.md" || second != "CAFE\u0301 (2).md" {
		t.Fatalf("got %q then %q; the second is the same file on macOS", first, second)
	}
}

func TestFileName(t *testing.T) {
	for title, want := range map[string]string{
		"Plain":                       "Plain",
		"":                            "Untitled",
		"...":                         "Untitled",
		".hidden":                     "hidden",
		"trailing. ":                  "trailing",
		"tab\tand\nnewline":           "tab and newline",
		`<all>|"bad"*chars?`:          "all bad chars",
		strings.Repeat("é", 60):       strings.Repeat("é", 50),
		strings.Repeat("x", 99) + "é": strings.Repeat("x", 99),
		"CON":                         "_CON",
		"lpt1.notes":                  "_lpt1.notes",
		"Console":                     "Console",
		"Cafe\u0301":                  "Café",
	} {
		if got := FileName(title); got != want {
			t.Errorf("FileName(%q) = %q, want %q", title, got, want)
		}
	}
}
