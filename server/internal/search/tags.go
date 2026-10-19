package search

import (
	"context"
	"sort"
	"strings"
	"unicode"

	"notes/server/internal/database"
	"notes/server/internal/locks"
)

// Tag is a #tag found in a note (§7.5). Key identifies it: Name lowercased.
type Tag struct {
	Key  string
	Name string
}

// Tags returns a note's tags, first spelling of each key, in order. Pinned by
// shared/markdown-fixtures/tags.json, shared with the web and Android.
func Tags(title, md string) []Tag {
	var out []Tag
	seen := map[string]bool{}
	scan := func(line string) {
		for _, name := range scanTags([]rune(line)) {
			key := strings.ToLower(name)
			if !seen[key] {
				seen[key] = true
				out = append(out, Tag{Key: key, Name: name})
			}
		}
	}
	scan(title)
	eachLine(md, func(text string, code bool) {
		if !code {
			scan(inline(text, true))
		}
	})
	return out
}

// TagKey is the key for a tag name given with or without its # marks, or "".
func TagKey(name string) string {
	name = strings.TrimSuffix(strings.TrimPrefix(name, "#"), "#")
	return strings.ToLower(normalizeTag([]rune(name)))
}

const object = '\uFFFC'

// scanTags finds the tag names in one line of visible text, where U+FFFC
// stands for code, links and anything else tags can't be part of.
func scanTags(r []rune) []string {
	var names []string
	for i := 0; i < len(r); i++ {
		if r[i] != '#' || i > 0 && !opensTag(r[i-1]) {
			continue
		}
		if i+1 >= len(r) || unicode.IsSpace(r[i+1]) || r[i+1] == '#' || r[i+1] == object {
			continue
		}
		if end := closingHash(r, i); end > 0 {
			if name := normalizeTag(r[i+1 : end]); name != "" {
				names = append(names, name)
			}
			i = end
			continue
		}
		end := i + 1
		for end < len(r) && inWord(r[end]) {
			end++
		}
		if name := normalizeTag(r[i+1 : end]); name != "" && !numeric(name) {
			names = append(names, name)
		}
		i = end - 1
	}
	return names
}

// closingHash finds the # closing a multi-word tag opened at r[open]: the
// next # on the line, if it follows a non-space and ends a word.
func closingHash(r []rune, open int) int {
	for j := open + 2; j < len(r); j++ {
		switch r[j] {
		case object:
			return 0
		case '#':
			if unicode.IsSpace(r[j-1]) || j+1 < len(r) && inWord(r[j+1]) {
				return 0
			}
			return j
		}
	}
	return 0
}

func opensTag(c rune) bool {
	return unicode.IsSpace(c) || c == '(' || c == '[' || c == '{'
}

// inWord reports characters a one-word tag can hold: anything but spaces,
// U+FFFC and punctuation, except - _ and the / that separates nested tags.
func inWord(c rune) bool {
	switch {
	case c == '-' || c == '_' || c == '/':
		return true
	case c == object || unicode.IsSpace(c):
		return false
	case c < 0x80:
		return unicode.IsLetter(c) || unicode.IsDigit(c)
	default:
		return !unicode.IsPunct(c)
	}
}

// normalizeTag trims each /-separated level, collapses inner spaces and
// drops empty levels.
func normalizeTag(r []rune) string {
	var levels []string
	for _, level := range strings.Split(string(r), "/") {
		if level = strings.Join(strings.Fields(level), " "); level != "" {
			levels = append(levels, level)
		}
	}
	return strings.Join(levels, "/")
}

// numeric reports names like 1 or 2024/10, which are tags only when closed
// with # so that "#1 priority" stays text.
func numeric(name string) bool {
	return strings.Trim(name, "0123456789-_/") == ""
}

// Filter narrows a listing or search to one tag, with its nested tags, to
// notes without tags, or to locked notes; and to one space, or to every
// space but one (§7.7). The zero value matches every note.
type Filter struct {
	Tag         string // a key, see TagKey
	Untagged    bool
	Locked      bool
	Space       string
	ExceptSpace string
}

// SQL is a condition on the notes row aliased n, with its arguments.
func (f Filter) SQL() (string, []any) {
	cond, args := f.kind()
	switch {
	case f.Space != "":
		return `n.space_id = ? AND ` + cond, append([]any{f.Space}, args...)
	case f.ExceptSpace != "":
		return `n.space_id != ? AND ` + cond, append([]any{f.ExceptSpace}, args...)
	}
	return cond, args
}

func (f Filter) kind() (string, []any) {
	switch {
	case f.Untagged:
		return `NOT EXISTS (SELECT 1 FROM note_tags t WHERE t.note_seq = n.seq)`, nil
	case f.Locked:
		return `substr(n.content, 1, ?) = ?`, []any{len(locks.Prefix), locks.Prefix}
	case f.Tag != "":
		// '0' sorts right after '/', so the range holds exactly the nested keys.
		return `EXISTS (SELECT 1 FROM note_tags t WHERE t.note_seq = n.seq
		          AND (t.key = ? OR (t.key > ? AND t.key < ?)))`,
			[]any{f.Tag, f.Tag + "/", f.Tag + "0"}
	default:
		return `1`, nil
	}
}

// TagCount is a tag with the number of live notes carrying it or one of its
// nested tags. Parents of nested tags are listed even if never written alone.
type TagCount struct {
	Key   string `json:"key"`
	Name  string `json:"name"`
	Count int    `json:"count"`
}

// ListTags returns every tag in live notes, in one space unless space is "",
// sorted by key. A tag's name is its spelling in the most recently updated note.
func ListTags(ctx context.Context, db *database.DB, space string) ([]TagCount, error) {
	rows, err := db.R.QueryContext(ctx, `
		SELECT t.key, t.name, t.note_seq
		FROM note_tags t
		JOIN notes n ON n.seq = t.note_seq
		WHERE n.deleted_at IS NULL AND (? = '' OR n.space_id = ?)
		ORDER BY n.updated_at DESC, n.id DESC`, space, space)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	names := map[string]string{}
	notes := map[string]map[int64]bool{}
	for rows.Next() {
		var key, name string
		var seq int64
		if err := rows.Scan(&key, &name, &seq); err != nil {
			return nil, err
		}
		keys, levels := strings.Split(key, "/"), strings.Split(name, "/")
		for i := range keys {
			k := strings.Join(keys[:i+1], "/")
			if _, ok := names[k]; !ok {
				names[k] = strings.Join(levels[:i+1], "/")
				notes[k] = map[int64]bool{}
			}
			notes[k][seq] = true
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := make([]TagCount, 0, len(names))
	for k, name := range names {
		out = append(out, TagCount{Key: k, Name: name, Count: len(notes[k])})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Key < out[j].Key })
	return out, nil
}
