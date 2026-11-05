package mdimport

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"

	"go.yaml.in/yaml/v3"
)

// frontMatter holds the fields an import uses from a note's YAML header.
type frontMatter struct {
	title            string
	created, updated time.Time
	tags             []string
	// Problems reading fields that were there, for the report.
	problems []string
}

// Field names apps write, compared ignoring case. Obsidian has no standard
// for dates; these are the common spellings, Joplin's and Hugo's included.
var (
	createdKeys = []string{"created", "date", "created_at", "createdat", "creation_date", "date_created", "created_on"}
	updatedKeys = []string{"updated", "modified", "lastmod", "updated_at", "updatedat", "last_modified", "date_modified", "modified_at"}
	tagKeys     = []string{"tags", "tag"}
)

// splitFrontMatter separates a leading `---` YAML mapping from the body.
// Without one, ok is false and body is the whole text.
func splitFrontMatter(text string) (fm frontMatter, body string, ok bool, err error) {
	rest, found := strings.CutPrefix(text, "---\n")
	if !found {
		return fm, text, false, nil
	}
	end, after := -1, 0
	for i := 0; i <= len(rest); {
		j := strings.IndexByte(rest[i:], '\n')
		line := rest[i:]
		next := len(rest) + 1
		if j >= 0 {
			line, next = rest[i:i+j], i+j+1
		}
		if t := strings.TrimRight(line, " \t"); t == "---" || t == "..." {
			end, after = i, next
			break
		}
		if j < 0 {
			break
		}
		i = next
	}
	if end < 0 {
		return fm, text, false, nil
	}
	var fields map[string]any
	if err := yaml.Unmarshal([]byte(rest[:end]), &fields); err != nil {
		// A note may open with a horizontal rule, not front matter: only
		// what looks like YAML is worth reporting.
		if yamlish.MatchString(rest[:end]) {
			err = fmt.Errorf("front matter not read: %w", err)
		} else {
			err = nil
		}
		return fm, text, false, err
	}
	if fields == nil && strings.TrimSpace(rest[:end]) != "" {
		return fm, text, false, nil
	}
	body = ""
	if after <= len(rest) {
		body = rest[after:]
	}
	fm.read(fields)
	return fm, body, true, nil
}

func (fm *frontMatter) read(fields map[string]any) {
	lower := map[string]any{}
	for k, v := range fields {
		lower[strings.ToLower(k)] = v
	}
	if v, ok := lower["title"]; ok && v != nil {
		fm.title = strings.TrimSpace(scalar(v))
	}
	fm.created = fm.date(lower, createdKeys)
	fm.updated = fm.date(lower, updatedKeys)
	for _, k := range tagKeys {
		if v, ok := lower[k]; ok {
			fm.tags = append(fm.tags, tagList(v)...)
		}
	}
}

func (fm *frontMatter) date(fields map[string]any, keys []string) time.Time {
	for _, k := range keys {
		v, ok := fields[k]
		if !ok || v == nil {
			continue
		}
		if t, ok := parseDate(v); ok {
			return t
		}
		fm.problems = append(fm.problems, fmt.Sprintf("%s: %q isn't a date it can read", k, scalar(v)))
	}
	return time.Time{}
}

var yamlish = regexp.MustCompile(`^\s*[\w-]+:(\s|$)`)

var dateLayouts = []string{
	time.RFC3339Nano,
	"2006-01-02T15:04:05Z0700",
	"2006-01-02T15:04:05",
	"2006-01-02T15:04",
	"2006-01-02 15:04:05Z07:00",
	"2006-01-02 15:04:05 Z07:00",
	"2006-01-02 15:04:05 -0700",
	"2006-01-02 15:04:05",
	"2006-01-02 15:04",
	"2006-01-02",
	"2006/01/02 15:04:05",
	"2006/01/02",
}

// parseDate reads a date as YAML decoded it, or as text. One without a time
// zone is local time, as the person who wrote it meant it.
func parseDate(v any) (time.Time, bool) {
	switch d := v.(type) {
	case time.Time:
		return d, true
	case int:
		return epoch(int64(d))
	case int64:
		return epoch(d)
	case float64:
		return epoch(int64(d))
	case string:
		s := strings.TrimSpace(d)
		if n, err := strconv.ParseInt(s, 10, 64); err == nil {
			return epoch(n)
		}
		for _, layout := range dateLayouts {
			if t, err := time.ParseInLocation(layout, s, time.Local); err == nil {
				return t, true
			}
		}
	}
	return time.Time{}, false
}

// epoch reads Unix seconds or milliseconds, whichever gives a plausible date.
// Below 2001 in seconds is more likely a year or 20240115 than a timestamp.
func epoch(n int64) (time.Time, bool) {
	switch {
	case n < 1e9:
		return time.Time{}, false
	case n < 1e11:
		return time.Unix(n, 0), true
	case n < 1e14:
		return time.UnixMilli(n), true
	}
	return time.Time{}, false
}

// tagList reads a tags field: a list, or one string of names separated by
// commas or, without commas, spaces (Obsidian accepts both).
func tagList(v any) []string {
	var raw []string
	switch t := v.(type) {
	case []any:
		for _, item := range t {
			if item != nil {
				raw = append(raw, scalar(item))
			}
		}
	case nil:
	default:
		s := scalar(t)
		if strings.Contains(s, ",") {
			raw = strings.Split(s, ",")
		} else {
			raw = strings.Fields(s)
		}
	}
	var out []string
	for _, r := range raw {
		r = strings.TrimSpace(r)
		if t, ok := strings.CutPrefix(r, "#"); ok {
			r = strings.TrimSuffix(t, "#") // `#multi word#`; C# keeps its #
		}
		if r != "" {
			out = append(out, r)
		}
	}
	return out
}

func scalar(v any) string {
	switch t := v.(type) {
	case string:
		return t
	case time.Time:
		return t.Format(time.RFC3339)
	default:
		return fmt.Sprint(t)
	}
}
