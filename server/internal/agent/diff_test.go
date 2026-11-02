package agent_test

import (
	"fmt"
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
	for i := range 3000 {
		a, b = append(a, fmt.Sprint("a", i)), append(b, fmt.Sprint("b", i))
	}
	lines := agent.Diff(strings.Join(a, "\n"), strings.Join(b, "\n"))
	ops := map[string]int{}
	for _, l := range lines {
		ops[l.Op]++
	}
	if ops["-"] != 3000 || ops["+"] != 3000 || !slices.ContainsFunc(lines, func(l agent.DiffLine) bool { return l.Op == "-" }) {
		t.Fatalf("ops: %v", ops)
	}
}
