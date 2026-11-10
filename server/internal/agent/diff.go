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

// maxDiffEdits bounds the search: past that many changed lines (a
// rewrite), the middle shows as all removed, then all added.
const maxDiffEdits = 1000

// Diff compares two texts line by line; empty when they have the same lines.
func Diff(before, after string) []DiffLine {
	return collapse(diffLines(before, after))
}

// diffLines is every line of the diff, none folded.
func diffLines(before, after string) []DiffLine {
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
	return lines
}

func splitLines(s string) []string {
	if s == "" {
		return nil
	}
	// The editor reads CRLF as LF, so an agent writing LF into an imported
	// CRLF note changes no line the user would see.
	s = strings.ReplaceAll(strings.ReplaceAll(s, "\r\n", "\n"), "\r", "\n")
	return strings.Split(strings.TrimSuffix(s, "\n"), "\n")
}

// middle diffs the changed part: the fewest lines removed and added (Myers),
// each change shown as its removed lines, then its added ones.
func middle(a, b []string) []DiffLine {
	var out []DiffLine
	trace := myers(a, b)
	if trace == nil {
		for _, l := range a {
			out = append(out, DiffLine{Op: "-", Text: l})
		}
		for _, l := range b {
			out = append(out, DiffLine{Op: "+", Text: l})
		}
		return out
	}
	// Walk back from the end; trace[d][k+d] is how far along a the best
	// path with d changes got on diagonal k (x-y).
	x, y := len(a), len(b)
	for d := len(trace) - 1; d > 0; d-- {
		prev := trace[d-1]
		at := func(k int) int { return int(prev[k+d-1]) }
		k := x - y
		down := k == -d || (k != d && at(k-1) < at(k+1))
		pk := k - 1
		if down {
			pk = k + 1
		}
		px := at(pk)
		py := px - pk
		sx := px + 1
		if down {
			sx = px
		}
		for x > sx {
			out = append(out, DiffLine{Op: "=", Text: a[x-1]})
			x, y = x-1, y-1
		}
		if down {
			out = append(out, DiffLine{Op: "+", Text: b[py]})
		} else {
			out = append(out, DiffLine{Op: "-", Text: a[px]})
		}
		x, y = px, py
	}
	for ; x > 0; x, y = x-1, y-1 {
		out = append(out, DiffLine{Op: "=", Text: a[x-1]})
	}
	slices.Reverse(out)
	// Within each change, removed lines first.
	for i := 0; i < len(out); {
		j := i
		for j < len(out) && out[j].Op != "=" {
			j++
		}
		slices.SortStableFunc(out[i:j], func(p, q DiffLine) int { return strings.Compare(q.Op, p.Op) })
		i = j + 1
	}
	return out
}

// myers finds the shortest edit script from a to b, returning each step's
// furthest reach per diagonal; nil when it takes more than maxDiffEdits.
func myers(a, b []string) [][]int32 {
	n, m := len(a), len(b)
	if n == 0 || m == 0 {
		return nil
	}
	maxD := min(n+m, maxDiffEdits)
	off := maxD + 1
	v := make([]int32, 2*maxD+3)
	var trace [][]int32
	for d := 0; d <= maxD; d++ {
		for k := -d; k <= d; k += 2 {
			var x int
			if k == -d || (k != d && v[off+k-1] < v[off+k+1]) {
				x = int(v[off+k+1])
			} else {
				x = int(v[off+k-1]) + 1
			}
			y := x - k
			for x < n && y < m && a[x] == b[y] {
				x, y = x+1, y+1
			}
			v[off+k] = int32(x)
			if x >= n && y >= m {
				return append(trace, slices.Clone(v[off-d:off+d+1]))
			}
		}
		trace = append(trace, slices.Clone(v[off-d:off+d+1]))
	}
	return nil
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
