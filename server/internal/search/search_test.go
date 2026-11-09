package search

import (
	"context"
	"database/sql"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"notes/server/internal/database"
)

func TestPlainTextFixtures(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "shared", "markdown-fixtures", "plaintext.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f struct {
		Cases []struct {
			Name     string `json:"name"`
			Markdown string `json:"markdown"`
			Text     string `json:"text"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	if len(f.Cases) == 0 {
		t.Fatal("no fixtures")
	}
	for _, c := range f.Cases {
		t.Run(c.Name, func(t *testing.T) {
			if got := PlainText(c.Markdown); got != c.Text {
				t.Errorf("PlainText(%q)\n got %q\nwant %q", c.Markdown, got, c.Text)
			}
		})
	}
}

func TestBuildQuery(t *testing.T) {
	cases := map[string]string{
		"":                     "",
		"   ":                  "",
		"milk":                 `"milk"*`,
		"Oat milk":             `"Oat"* "milk"*`,
		`"unbalanced`:          `"unbalanced"*`,
		"a OR b NEAR(c)":       `"a"* "OR"* "b"* "NEAR"* "c"*`,
		"title:secret -x ^y *": `"title"* "secret"* "x"* "y"*`,
		"café naïve":           `"café"* "naïve"*`,
		"日本語":                  `"日本語"*`,
		"--- ***":              "",
	}
	for in, want := range cases {
		if got := BuildQuery(in); got != want {
			t.Errorf("BuildQuery(%q) = %q, want %q", in, got, want)
		}
	}
	long := strings.Repeat("w ", 100)
	if n := strings.Count(BuildQuery(long), `"*`); n != maxTerms {
		t.Errorf("terms = %d, want %d", n, maxTerms)
	}
}

func TestSearchRanksAndHighlights(t *testing.T) {
	ctx := context.Background()
	db, err := database.Open(ctx, filepath.Join(t.TempDir(), "notes.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	docs := []struct {
		id, title, content string
		deleted            bool
	}{
		{"a", "Groceries", "Buy **oat** milk and <b>bread</b>", false},
		{"b", "Random", "groceries mentioned once in passing", false},
		{"c", "Café plans", "Meet at the cafe", false},
		{"d", "Groceries old", "deleted note", true},
	}
	err = db.WriteTx(ctx, func(tx *sql.Tx) error {
		for i, d := range docs {
			var deleted any
			if d.deleted {
				deleted = 1
			}
			res, err := tx.Exec(`INSERT INTO notes (id, title, content, change_seq, created_at, updated_at, deleted_at)
				VALUES (?, ?, ?, ?, 0, ?, ?)`, d.id, d.title, d.content, i+1, i, deleted)
			if err != nil {
				return err
			}
			if !d.deleted {
				rowid, _ := res.LastInsertId()
				if err := IndexNote(ctx, tx, rowid, d.title, d.content); err != nil {
					return err
				}
			}
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}

	res, err := Search(ctx, db, "grocer", Filter{}, 10, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(res) != 2 || res[0].ID != "a" || res[1].ID != "b" {
		t.Fatalf("prefix search: got %+v, want a (title match) then b", res)
	}

	res, err = Search(ctx, db, "bread", Filter{}, 10, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(res) != 1 {
		t.Fatalf("got %d results", len(res))
	}
	// Markup in the note is escaped; only our <mark> tags are HTML.
	if want := "Buy oat milk and &lt;b&gt;<mark>bread</mark>&lt;/b&gt;"; res[0].Excerpt != want {
		t.Errorf("excerpt = %q, want %q", res[0].Excerpt, want)
	}

	// Accent-insensitive in both directions.
	for _, q := range []string{"cafe", "café", "CAFÉ"} {
		res, err := Search(ctx, db, q, Filter{}, 10, 0)
		if err != nil {
			t.Fatal(err)
		}
		if len(res) != 1 || res[0].ID != "c" {
			t.Errorf("Search(%q) = %+v, want note c", q, res)
		}
	}

	// Hostile input never errors.
	for _, q := range []string{`"`, `NEAR(`, `*`, `a AND`, `(`, `col:`, "\x00"} {
		if _, err := Search(ctx, db, q, Filter{}, 10, 0); err != nil {
			t.Errorf("Search(%q): %v", q, err)
		}
	}

	if n, err := Rebuild(ctx, db); err != nil || n != 3 {
		t.Fatalf("Rebuild = %d, %v; want 3", n, err)
	}
	res, _ = Search(ctx, db, "groceries", Filter{}, 10, 0)
	if len(res) != 2 {
		t.Errorf("after rebuild: %d results, want 2", len(res))
	}
}

func TestTagFixtures(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "shared", "markdown-fixtures", "tags.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f struct {
		Cases []struct {
			Name     string   `json:"name"`
			Title    string   `json:"title"`
			Markdown string   `json:"markdown"`
			Tags     []string `json:"tags"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	if len(f.Cases) == 0 {
		t.Fatal("no fixtures")
	}
	for _, c := range f.Cases {
		t.Run(c.Name, func(t *testing.T) {
			got := []string{}
			for _, tag := range Tags(c.Title, c.Markdown) {
				got = append(got, tag.Name)
				if tag.Key != strings.ToLower(tag.Name) {
					t.Errorf("key %q for %q", tag.Key, tag.Name)
				}
			}
			if strings.Join(got, "|") != strings.Join(c.Tags, "|") {
				t.Errorf("Tags(%q, %q)\n got %q\nwant %q", c.Title, c.Markdown, got, c.Tags)
			}
		})
	}
}

func TestTagKey(t *testing.T) {
	cases := map[string]string{
		"#Home/Repairs":      "home/repairs",
		"summer trip":        "summer trip",
		"#school / biology#": "school/biology",
		"#":                  "",
		"//":                 "",
	}
	for in, want := range cases {
		if got := TagKey(in); got != want {
			t.Errorf("TagKey(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestTagSpansGiveWhereEachTagIs(t *testing.T) {
	text := "Café #home/garden and #multi word# end"
	spans := TagSpans(text, true)
	if len(spans) != 2 {
		t.Fatalf("%+v", spans)
	}
	if text[spans[0].Start:spans[0].End] != "#home/garden" || spans[0].Name != "home/garden" {
		t.Errorf("%+v", spans[0])
	}
	if text[spans[1].Start:spans[1].End] != "#multi word#" || spans[1].Name != "multi word" {
		t.Errorf("%+v", spans[1])
	}
	if got := TagSpans("#mid", false); len(got) != 0 {
		t.Errorf("a run that doesn't start after a space can't open a tag at once: %+v", got)
	}
}
