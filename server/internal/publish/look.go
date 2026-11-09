package publish

import (
	"encoding/json"
	"fmt"
	"html"
	"strings"
	"sync"
	"unicode"
)

// The notesd look every site gets with nothing set (§7.10): Instrument Serif for its titles, the face the apps
// title with, and a favicon in the space's accent.

const (
	latin    = "U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD"
	latinExt = "U+0100-02BA,U+02BD-02C5,U+02C7-02CC,U+02CE-02D7,U+02DD-02FF,U+0304,U+0308,U+0329,U+1D00-1DBF,U+1E00-1E9F,U+1EF2-1EFF,U+2020,U+20A0-20AB,U+20AD-20C0,U+2113,U+2C60-2C7F,U+A720-A7FF"
)

// A face's subsets, so a browser fetches only those a page's text needs.
var faces = []struct{ file, style, ranges string }{
	{"instrument-serif-latin-ext-400-normal", "normal", latinExt},
	{"instrument-serif-latin-400-normal", "normal", latin},
	{"instrument-serif-latin-ext-400-italic", "italic", latinExt},
	{"instrument-serif-latin-400-italic", "italic", latin},
}

type fontFile struct {
	name string // fonts/<file>.<hash>.woff2: named by its content, cached for good
	data []byte
}

// fonts are the site's font files and the @font-face rules naming them, the same for every site.
var fonts = sync.OnceValues(func() ([]fontFile, string) {
	var files []fontFile
	var css strings.Builder
	for _, f := range faces {
		data, err := assets.ReadFile("assets/fonts/" + f.file + ".woff2")
		if err != nil {
			panic(err) // embedded: missing only in a broken build
		}
		name := "fonts/" + f.file + "." + hash(data)[:10] + ".woff2"
		files = append(files, fontFile{name, data})
		fmt.Fprintf(&css, "@font-face {\n  font-family: 'Instrument Serif';\n  font-style: %s;\n  font-weight: 400;\n"+
			"  font-display: swap;\n  src: url(%s) format('woff2');\n  unicode-range: %s;\n}\n\n", f.style, name, f.ranges)
	}
	return files, css.String()
})

type mark struct{ Accent, Bg string }

// accents are each theme's accent and background in each mode, as theme.css has them (scripts/space_icons.py).
var accents = sync.OnceValue(func() map[string]map[string]mark {
	data, err := assets.ReadFile("assets/accents.json")
	if err != nil {
		panic(err)
	}
	var out map[string]map[string]mark
	if err := json.Unmarshal(data, &out); err != nil {
		panic(err)
	}
	return out
})

// favicon is the site's icon: the first letter of its name on its accent, in the browser's light or dark.
func favicon(name, accent string) []byte {
	modes, ok := accents()[accent]
	if !ok {
		modes = accents()[""]
	}
	letter := ""
	for _, r := range name {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			letter = html.EscapeString(strings.ToUpper(string(r)))
			break
		}
	}
	light, dark := modes["light"], modes["dark"]
	return fmt.Appendf(nil, `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">`+
		`<style>rect{fill:%s}text{fill:%s}@media (prefers-color-scheme:dark){rect{fill:%s}text{fill:%s}}</style>`+
		`<rect width="32" height="32" rx="8"/>`+
		`<text x="16" y="23.5" text-anchor="middle" font-family="Georgia,'Times New Roman',serif" font-size="22">%s</text>`+
		`</svg>`, light.Accent, light.Bg, dark.Accent, dark.Bg, letter)
}
