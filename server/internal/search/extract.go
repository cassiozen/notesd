// Package search extracts indexable plain text from Markdown, builds safe
// FTS5 queries from user input and runs searches.
package search

import (
	"strings"
	"unicode"
	"unicode/utf8"
)

// PlainText converts a note's Markdown into the plain text stored in the
// search index (§8.1). It understands the restricted dialect of §7.1 —
// headings, emphasis, strikethrough, links, lists, task lists, blockquotes —
// plus code spans and fences, and degrades to keeping the raw characters for
// anything else. Block boundaries become newlines so words never fuse.
//
// The rules are pinned by shared/markdown-fixtures/plaintext.json, which the
// iOS and Android extractors must also pass.
func PlainText(md string) string {
	return PlainTextWith(md, func(line string) string { return line })
}

// PlainTextWith is PlainText with prose applied to each line of text outside code fences.
func PlainTextWith(md string, prose func(line string) string) string {
	var out []string
	eachLine(md, func(text string, code bool) {
		if code {
			out = append(out, strings.TrimRight(text, " \t"))
		} else {
			out = append(out, prose(strings.TrimSpace(inline(text, false))))
		}
	})
	return collapseBlankLines(out)
}

// eachLine calls fn for every line of md: code lines inside fences as they
// are, other lines with their block markers removed and their inline syntax
// left in. Fence delimiters are skipped and thematic breaks become "".
func eachLine(md string, fn func(text string, code bool)) {
	md = strings.ReplaceAll(md, "\r\n", "\n")
	md = strings.ReplaceAll(md, "\r", "\n")

	fence := "" // the opening fence while inside a fenced code block
	for _, line := range strings.Split(md, "\n") {
		trimmed := strings.TrimLeft(line, " \t")

		if fence != "" {
			if strings.HasPrefix(trimmed, fence) && strings.Trim(trimmed, fence[:1]) == "" {
				fence = ""
				continue
			}
			fn(line, true)
			continue
		}
		if f := fenceOpener(trimmed); f != "" {
			fence = f
			continue
		}
		if isRule(trimmed) {
			fn("", false)
			continue
		}
		fn(stripBlockMarkers(trimmed), false)
	}
}

func fenceOpener(s string) string {
	for _, c := range []string{"```", "~~~"} {
		if strings.HasPrefix(s, c) {
			n := len(s) - len(strings.TrimLeft(s, c[:1]))
			return strings.Repeat(c[:1], n)
		}
	}
	return ""
}

// isRule reports thematic breaks and setext underlines: a line made only of
// one of -, *, _ or = (at least three of them, or two for =), ignoring spaces.
func isRule(s string) bool {
	compact := strings.ReplaceAll(s, " ", "")
	if len(compact) < 2 {
		return false
	}
	c := compact[0]
	if c != '-' && c != '*' && c != '_' && c != '=' {
		return false
	}
	if strings.Trim(compact, string(c)) != "" {
		return false
	}
	return c == '=' || len(compact) >= 3
}

// stripBlockMarkers removes, repeatedly, blockquote markers, list markers,
// task-list boxes and ATX heading markers from the start of a line.
func stripBlockMarkers(s string) string {
	for {
		before := s
		switch {
		case strings.HasPrefix(s, ">"):
			s = strings.TrimLeft(s[1:], " \t")
		case len(s) >= 2 && (s[0] == '-' || s[0] == '*' || s[0] == '+') && (s[1] == ' ' || s[1] == '\t'):
			s = strings.TrimLeft(s[2:], " \t")
		case (s == "-" || s == "*" || s == "+"):
			s = ""
		default:
			if n := orderedMarker(s); n > 0 {
				s = strings.TrimLeft(s[n:], " \t")
			} else if n := taskBox(s); n > 0 {
				s = strings.TrimLeft(s[n:], " \t")
			} else if n := headingMarker(s); n > 0 {
				s = strings.TrimLeft(s[n:], " \t")
				s = trimClosingHashes(s)
			}
		}
		if s == before {
			return s
		}
	}
}

// orderedMarker returns the length of "12. " or "3) " at the start of s.
func orderedMarker(s string) int {
	i := 0
	for i < len(s) && i < 9 && s[i] >= '0' && s[i] <= '9' {
		i++
	}
	if i == 0 || i >= len(s) || (s[i] != '.' && s[i] != ')') {
		return 0
	}
	if i+1 == len(s) {
		return i + 1
	}
	if s[i+1] == ' ' || s[i+1] == '\t' {
		return i + 2
	}
	return 0
}

func taskBox(s string) int {
	if len(s) >= 3 && s[0] == '[' && s[2] == ']' && (s[1] == ' ' || s[1] == 'x' || s[1] == 'X') {
		if len(s) == 3 || s[3] == ' ' || s[3] == '\t' {
			return 3
		}
	}
	return 0
}

func headingMarker(s string) int {
	n := 0
	for n < len(s) && s[n] == '#' {
		n++
	}
	if n == 0 || n > 6 {
		return 0
	}
	if n == len(s) || s[n] == ' ' || s[n] == '\t' {
		return n
	}
	return 0
}

func trimClosingHashes(s string) string {
	t := strings.TrimRight(s, " \t")
	u := strings.TrimRight(t, "#")
	if u == t {
		return s
	}
	if u == "" || strings.HasSuffix(u, " ") || strings.HasSuffix(u, "\t") {
		return strings.TrimRight(u, " \t")
	}
	return s
}

// inline removes inline syntax: emphasis and strikethrough delimiters, code
// span backticks, link and image destinations, autolink brackets and
// backslash escapes. With opaque, code spans, links, images and autolinks
// become a single U+FFFC instead of their text, for scanners that must not
// look inside them.
func inline(s string, opaque bool) string {
	var b strings.Builder
	b.Grow(len(s))
	for i := 0; i < len(s); {
		c := s[i]
		switch {
		case c == '\\' && i+1 < len(s) && isASCIIPunct(s[i+1]):
			b.WriteByte(s[i+1])
			i += 2

		case c == '`':
			n := runLen(s, i, '`')
			if end := strings.Index(s[i+n:], s[i:i+n]); end >= 0 {
				b.WriteString(opaqueOr(opaque, strings.TrimSpace(s[i+n:i+n+end])))
				i += n + end + n
			} else {
				b.WriteString(s[i : i+n])
				i += n
			}

		case c == '!' && i+1 < len(s) && s[i+1] == '[':
			if text, n, ok := link(s[i+1:]); ok {
				b.WriteString(opaqueOr(opaque, inline(text, false)))
				i += 1 + n
			} else {
				b.WriteByte(c)
				i++
			}

		case c == '[':
			if text, n, ok := link(s[i:]); ok {
				b.WriteString(opaqueOr(opaque, inline(text, false)))
				i += n
			} else {
				b.WriteByte(c)
				i++
			}

		case c == '<':
			if end := strings.IndexByte(s[i:], '>'); end > 1 && isAutolink(s[i+1:i+end]) {
				b.WriteString(opaqueOr(opaque, s[i+1:i+end]))
				i += end + 1
			} else {
				b.WriteByte(c)
				i++
			}

		case c == '*' || c == '_' || c == '~':
			n := runLen(s, i, c)
			if c == '~' && n < 2 || !isDelimiter(s, i, n, c) {
				b.WriteString(s[i : i+n])
			}
			i += n

		default:
			b.WriteByte(c)
			i++
		}
	}
	return b.String()
}

func opaqueOr(opaque bool, text string) string {
	if opaque {
		return string(object)
	}
	return text
}

// link parses "[text](destination)" at the start of s and returns the text
// and the number of bytes consumed. Nested brackets in text are balanced.
func link(s string) (text string, n int, ok bool) {
	depth := 0
	for i := 0; i < len(s); i++ {
		switch s[i] {
		case '\\':
			i++
		case '[':
			depth++
		case ']':
			depth--
			if depth == 0 {
				if i+1 >= len(s) || s[i+1] != '(' {
					return "", 0, false
				}
				pd := 0
				for j := i + 1; j < len(s); j++ {
					switch s[j] {
					case '\\':
						j++
					case '(':
						pd++
					case ')':
						pd--
						if pd == 0 {
							return s[1:i], j + 1, true
						}
					}
				}
				return "", 0, false
			}
		}
	}
	return "", 0, false
}

func isAutolink(s string) bool {
	if strings.ContainsAny(s, " \t<") {
		return false
	}
	if scheme, _, ok := strings.Cut(s, ":"); ok && len(scheme) >= 2 {
		for _, r := range scheme {
			if !(r < utf8.RuneSelf && (unicode.IsLetter(r) || unicode.IsDigit(r) || r == '+' || r == '.' || r == '-')) {
				return false
			}
		}
		return true
	}
	at := strings.IndexByte(s, '@')
	return at > 0 && strings.Contains(s[at:], ".")
}

// isDelimiter decides whether a run of *, _ or ~ is emphasis syntax rather
// than a literal character, approximating CommonMark's flanking rules: the
// run must touch non-space text on at least one side, and an underscore run
// between two alphanumerics (snake_case) is literal.
func isDelimiter(s string, i, n int, c byte) bool {
	before, _ := utf8.DecodeLastRuneInString(s[:i])
	after, _ := utf8.DecodeRuneInString(s[i+n:])
	if i == 0 {
		before = ' '
	}
	if i+n >= len(s) {
		after = ' '
	}
	spaceBefore, spaceAfter := unicode.IsSpace(before), unicode.IsSpace(after)
	if spaceBefore && spaceAfter {
		return false
	}
	if c == '_' && isAlnum(before) && isAlnum(after) {
		return false
	}
	return true
}

func isAlnum(r rune) bool { return unicode.IsLetter(r) || unicode.IsDigit(r) }

func runLen(s string, i int, c byte) int {
	n := 0
	for i+n < len(s) && s[i+n] == c {
		n++
	}
	return n
}

func isASCIIPunct(c byte) bool {
	return strings.IndexByte("!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~", c) >= 0
}

func collapseBlankLines(lines []string) string {
	var b strings.Builder
	blank := true // suppress leading blank lines
	for _, l := range lines {
		if l == "" {
			blank = true
			continue
		}
		if b.Len() > 0 {
			b.WriteByte('\n')
			if blank {
				b.WriteByte('\n')
			}
		}
		b.WriteString(l)
		blank = false
	}
	return b.String()
}
