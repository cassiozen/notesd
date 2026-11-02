package agent

import (
	"slices"
	"strings"
)

// DiffLine is one line of a diff: kept ("="), removed ("-") or added ("+");
// or a run of unchanged lines left out ("…"), with how many.
type DiffLine struct {
	Op    string `json:"op"`
	Text  string `json:"text,omitempty"`
	Count int    `json:"count,omitempty"`
}

// diffContext is how many unchanged lines are kept around each change.
const diffContext = 3

// maxDiffCells bounds the LCS table; past it (a long note rewritten, common
// ends already trimmed) the middle shows as all removed, then all added.
const maxDiffCells = 4_000_000

// Diff compares two texts line by line; empty when they have the same lines.
func Diff(before, after string) []DiffLine {
	a, b := splitLines(before), splitLines(after)
	pre := 0
	for pre < len(a) && pre < len(b) && a[pre] == b[pre] {
		pre++
	}
	suf := 0
	for suf < len(a)-pre && suf < len(b)-pre && a[len(a)-1-suf] == b[len(b)-1-suf] {
		suf++
	}
	var lines []DiffLine
	for _, l := range a[:pre] {
		lines = append(lines, DiffLine{Op: "=", Text: l})
	}
	lines = append(lines, middle(a[pre:len(a)-suf], b[pre:len(b)-suf])...)
	for _, l := range a[len(a)-suf:] {
		lines = append(lines, DiffLine{Op: "=", Text: l})
	}
	return collapse(lines)
}

func splitLines(s string) []string {
	if s == "" {
		return nil
	}
	return strings.Split(strings.TrimSuffix(s, "\n"), "\n")
}

// middle diffs the changed part by longest common subsequence.
func middle(a, b []string) []DiffLine {
	var out []DiffLine
	if len(a)*len(b) > maxDiffCells || len(a) == 0 || len(b) == 0 {
		for _, l := range a {
			out = append(out, DiffLine{Op: "-", Text: l})
		}
		for _, l := range b {
			out = append(out, DiffLine{Op: "+", Text: l})
		}
		return out
	}
	// lcs[i][j]: the longest common subsequence of a[i:] and b[j:].
	w := len(b) + 1
	lcs := make([]int32, (len(a)+1)*w)
	for i := len(a) - 1; i >= 0; i-- {
		for j := len(b) - 1; j >= 0; j-- {
			if a[i] == b[j] {
				lcs[i*w+j] = lcs[(i+1)*w+j+1] + 1
			} else {
				lcs[i*w+j] = max(lcs[(i+1)*w+j], lcs[i*w+j+1])
			}
		}
	}
	i, j := 0, 0
	for i < len(a) && j < len(b) {
		switch {
		case a[i] == b[j]:
			out = append(out, DiffLine{Op: "=", Text: a[i]})
			i, j = i+1, j+1
		case lcs[(i+1)*w+j] >= lcs[i*w+j+1]:
			out = append(out, DiffLine{Op: "-", Text: a[i]})
			i++
		default:
			out = append(out, DiffLine{Op: "+", Text: b[j]})
			j++
		}
	}
	for ; i < len(a); i++ {
		out = append(out, DiffLine{Op: "-", Text: a[i]})
	}
	for ; j < len(b); j++ {
		out = append(out, DiffLine{Op: "+", Text: b[j]})
	}
	return out
}

// collapse keeps diffContext unchanged lines around each change and folds
// the rest into "…" lines.
func collapse(lines []DiffLine) []DiffLine {
	if !slices.ContainsFunc(lines, func(l DiffLine) bool { return l.Op != "=" }) {
		return []DiffLine{}
	}
	keep := make([]bool, len(lines))
	for i, l := range lines {
		if l.Op == "=" {
			continue
		}
		for k := max(0, i-diffContext); k <= min(len(lines)-1, i+diffContext); k++ {
			keep[k] = true
		}
	}
	var out []DiffLine
	for i := 0; i < len(lines); {
		if keep[i] {
			out = append(out, lines[i])
			i++
			continue
		}
		n := 0
		for i < len(lines) && !keep[i] {
			n, i = n+1, i+1
		}
		out = append(out, DiffLine{Op: "…", Count: n})
	}
	return out
}
