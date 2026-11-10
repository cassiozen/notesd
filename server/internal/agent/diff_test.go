package agent_test

import (
	"fmt"
	"math/rand/v2"
	"slices"
	"strings"
	"testing"

	"notes/server/internal/agent"
)

func render(lines []agent.DiffLine) string {
	var b strings.Builder
	for _, l := range lines {
		if l.Op == "…" {
			fmt.Fprintf(&b, "… %d\n", l.Count)
		} else {
			fmt.Fprintf(&b, "%s %s\n", l.Op, l.Text)
		}
	}
	return b.String()
}

func TestDiff(t *testing.T) {
	long := func(n int, extra ...string) string {
		var ls []string
		for i := range n {
			ls = append(ls, fmt.Sprintf("line %d", i))
		}
		return strings.Join(append(ls, extra...), "\n") + "\n"
	}
	for _, tc := range []struct {
		name, before, after, want string
	}{
		{"same", "a\nb\n", "a\nb\n", ""},
		{"create", "", "# Digest\n\nThemes\n", "+ # Digest\n+ \n+ Themes\n"},
		{"tag added at the end", "Milk\nEggs", "Milk\nEggs\n\n#groceries\n", "= Milk\n= Eggs\n+ \n+ #groceries\n"},
		{"line replaced", "a\nb\nc\n", "a\nB\nc\n", "= a\n- b\n+ B\n= c\n"},
		{"line endings", "a\r\nb\r\n", "a\nb\nc\n", "= a\n= b\n+ c\n"},
		{"far from the change is folded", long(10, "end"), long(10, "END"),
			"… 7\n= line 7\n= line 8\n= line 9\n- end\n+ END\n"},
		{"two changes apart", long(20) + "x\n", "y\n" + long(20),
			"+ y\n= line 0\n= line 1\n= line 2\n… 14\n= line 17\n= line 18\n= line 19\n- x\n"},
	} {
		if got := render(agent.Diff(tc.before, tc.after)); got != tc.want {
			t.Errorf("%s:\n%s\nwant:\n%s", tc.name, got, tc.want)
		}
	}
}

func TestDiffOfALongRewrite(t *testing.T) {
	var a, b []string
	var want []agent.DiffLine
	for i := range 3000 {
		a, b = append(a, fmt.Sprint("a", i)), append(b, fmt.Sprint("b", i))
		want = append(want, agent.DiffLine{Op: "-", Text: a[i]})
	}
	for _, l := range b {
		want = append(want, agent.DiffLine{Op: "+", Text: l})
	}
	if got := agent.Diff(strings.Join(a, "\n"), strings.Join(b, "\n")); !slices.Equal(got, want) {
		t.Fatalf("got %d lines, starting %v", len(got), got[:min(len(got), 4)])
	}
}

func TestDiffOfTwoEditsFarApart(t *testing.T) {
	var a []string
	for i := range 5000 {
		a = append(a, fmt.Sprint("line ", i))
	}
	b := slices.Clone(a)
	b[0] = "# Title"
	b = append(b, "", "## Added")
	want := "- line 0\n+ # Title\n= line 1\n= line 2\n= line 3\n… 4993\n= line 4997\n= line 4998\n= line 4999\n+ \n+ ## Added\n"
	if got := render(agent.Diff(strings.Join(a, "\n"), strings.Join(b, "\n"))); got != want {
		t.Fatalf("got:\n%s", got)
	}
}

// Every diff, read one side at a time, gives back that text, and changes as
// few lines as an LCS would.
func TestDiffRebuildsBothSides(t *testing.T) {
	rng := rand.New(rand.NewPCG(1, 2))
	words := []string{"a", "b", "c", "d", ""}
	pick := func() []string {
		ls := make([]string, 1+rng.IntN(30))
		for i := range ls {
			ls[i] = words[rng.IntN(len(words))]
		}
		return ls
	}
	for range 3000 {
		a, b := pick(), pick()
		var gotA, gotB []string
		kept := 0
		for _, l := range agent.DiffLines(strings.Join(a, "\n")+"\n", strings.Join(b, "\n")+"\n") {
			if l.Op != "+" {
				gotA = append(gotA, l.Text)
			}
			if l.Op != "-" {
				gotB = append(gotB, l.Text)
			}
			if l.Op == "=" {
				kept++
			}
		}
		if !slices.Equal(gotA, a) || !slices.Equal(gotB, b) || kept != lcs(a, b) {
			t.Fatalf("%q -> %q: got %q, %q, kept %d of %d", a, b, gotA, gotB, kept, lcs(a, b))
		}
	}
}

func lcs(a, b []string) int {
	t := make([][]int, len(a)+1)
	for i := range t {
		t[i] = make([]int, len(b)+1)
	}
	for i := len(a) - 1; i >= 0; i-- {
		for j := len(b) - 1; j >= 0; j-- {
			if a[i] == b[j] {
				t[i][j] = t[i+1][j+1] + 1
			} else {
				t[i][j] = max(t[i+1][j], t[i][j+1])
			}
		}
	}
	return t[0][0]
}
