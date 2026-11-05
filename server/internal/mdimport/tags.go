package mdimport

import (
	"strings"

	"notes/server/internal/search"
)

// formatTag writes name as `#name`, else `#name#` (spaces, `#2024#`): the
// first the extractor reads back as name, or "" when neither is.
func formatTag(name string) string {
	// Tags end at #, so C# can only be #C.
	name = strings.Join(strings.Fields(strings.ReplaceAll(name, "#", " ")), " ")
	key := search.TagKey(name)
	if key == "" {
		return ""
	}
	for _, tag := range []string{"#" + name, "#" + name + "#"} {
		if got := search.Tags("", tag); len(got) == 1 && got[0].Key == key {
			return tag
		}
	}
	return ""
}

// folderTag is the nested tag for a note's folder: `a/b/note.md` is `a/b`.
func folderTag(p string) string {
	i := strings.LastIndexByte(p, '/')
	if i < 0 {
		return ""
	}
	return p[:i]
}
