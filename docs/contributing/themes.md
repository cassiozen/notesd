---
title: Themes
description: The theme format in shared/themes, how it reaches each app, and how to add a theme.
---

# Themes

A theme is a palette, a title font and a body font from the font library, one preset style,
and how the sidebar sits. It can't set anything else: what makes themes different has to be one
of these parts, and any palette works with any style and any fonts.

Themes are defined once, as JSON in `shared/themes`, and generated into each app's code. Theme
and mode (System, Light, Dark) are both kept per device.

## Layout

```text
shared/themes/
  themes.json        the themes people pick from, and the default
  palettes/<id>.json colors
  styles/<id>.json   the preset ways surfaces are made
  fonts.json         the font library: families (the files) and the title and body roles
  schema/            a JSON Schema for each; every file names its own, so editors check it
  generate.ts        the definitions → each app's code
  scripts/native_fonts.py  the font files the iOS and Android apps bundle
  test/              schemas, references, contrast, and that the generated code is current
```

The generated code is committed, so Xcode and Gradle never need Node:

| Output | Used by |
|--------|---------|
| `apps/web/src/themes/generated/themes.css` | the web app: palettes, styles and fonts as CSS, picked by attributes on `<html>` |
| `apps/web/src/themes/generated/themes.ts` | the web app's theme list (`src/themes/themes.ts` wraps it) |
| `apps/ios/Notes/UI/Themes.generated.swift` | iOS: `Themes.all`, every value resolved |
| `apps/android/app/src/main/kotlin/notes/app/ui/themes/Themes.generated.kt` | Android: `Themes.all`, every value resolved |
| `apps/android/app/src/main/res/font/theme_*.xml` | Android: each family as a font resource, every weight an instance, for Views (the editor) |

After changing anything in `shared/themes`, run `mise run themes`; after changing a font
family's files or faces in `fonts.json`, also `mise run themes:fonts`. `mise run test:themes`
(part of `mise run test`) fails while any generated code is out of date; the native tests fail if
a font file can't be loaded.

## Palettes

Colors only, each the same in light and dark or a `{ "light", "dark" }` pair. A color is
`#rrggbb`, `#rrggbbaa`, `rgb(r g b / a)` or `oklch(l c h / a)`. The web keeps it as written;
native code gets it as sRGB, converted by the generator.

Every palette sets every role the schema lists:

| Roles | What they're for |
|-------|------------------|
| `bg`, `paper`, `ink`, `ink-2`, `ink-3`, `scrim` | The window, the note's sheet, text at three strengths, and the veil behind a sheet or dialog |
| `surface`, `line`, `shade`, `blob-1` | What styles make materials from: panels, hairlines and fills, shadows, the backdrop glow |
| `accent`, `accent-deep`, `on-accent`, `tint`, `link`, `focus` | The main button and ticked tasks, icon buttons and the caret, links, focus rings |
| `solid`, `on-solid` | Inverted buttons |
| `danger`, `good`, `err-*`, `warn-*`, `status-*` | States and the save indicator |
| `code`, `code-ink`, `code-dim`, `syntax-*` | The code block, always dark, and its highlighting |

Two are optional: `check-ink` (the dot in a ticked box; `on-accent` otherwise) and `sidebar`
(for a theme whose sidebar is `tinted` or `dark`; `bg` otherwise).

A palette never says how see-through or how deep anything is. The test suite checks text
contrast in every palette and mode: 4.5:1 for text (WCAG AA), 3:1 for icons and code comments.

## Styles

A style is data, not CSS, so each platform can draw it its own way. It reads only palette roles.
The values:

- **A paint**: `{ "role": "line", "alpha": 0.09 }` (a palette role), `{ "color": "#ffffff",
  "alpha": 0.85 }` (white or black only: light and shadow, never a hue), `{ "token": "hair" }`
  (another of the style's colors), or `{ "light": …, "dark": … }` for one per mode. An alpha can
  differ per mode too.
- **A shadow**: a list of layers (`x`, `y`, `blur`, `spread`, `inset`, `color`), the first on top,
  empty for none. A layer can be another shadow's name, spliced in. Or `{ "light": [], "dark": [] }`.
- **A blur**: `{ "radius": 18, "saturate": 1.3 }`, or null.
- **A curve**: `{ "cubicBezier": [x1, y1, x2, y2] }`, or `{ "linear": [...] }`, evenly spaced points
  of a spring.

The groups, each required in full:

| Group | Tokens |
|-------|--------|
| `layout` (web only, for now) | `app-gap`, `app-padding` (around and between columns), `pane-radius`, `pane-border`, `pane-shadow` (the note's sheet), `column-rule` (between columns) |
| `shape` | `radius-scale`: multiplies every corner radius |
| `material` | `glass-hi` (floating panels), `blur`, `blur-pop`, `blur-dialog`, `blur-scrim`, `edge` (a panel's border), `inset` (its top highlight), `field`, `chip` |
| `lines` | `hair`, `hair-strong`, `hover`, `on` (a selected row) |
| `depth` | `shadow`, `shadow-lg`, `shadow-pop` (menus), `shadow-bar` (the find bar), `shadow-toast`, `shadow-thumb` (a picked segment) |
| `backdrop` | `wash`: a large blurred glow behind the window, or null |
| `motion` | `spring` (things that appear), `ease-out` |
| `details` | `focus-ring`, `check-radius`, `check-mark` (`dot` or `fill`), `tag-bg`, `tag-ink`, `tag-radius`, `tag-padding` (em), `selection` (null keeps the platform's), `done-decoration` (`none` or `line-through`) |

## Fonts

`fonts.json` has three parts:

- **`families`**: the files. Each has its CSS family name and fallback, the fontsource package and
  stylesheets the web loads (only the `subsets`' rules are kept: Latin and Latin Extended), and
  its native faces: file name on iOS, resource name on Android, PostScript name (what iOS loads
  it by), weight or weight range, style. A family with an opsz axis says
  `"opticalSize": "auto"`: browsers set it from the font size themselves; native code must.
- **`title`**: how a family is set as titles: weight, style, variation axes (not opsz), a size
  scale, tracking in em, and a lighter weight and style for quotes. The size rules were written
  for Instrument Serif, a narrow display face, so a wider face is scaled down.
- **`body`**: a family the whole UI is set in.

Native files are built by `mise run themes:fonts` (needs [uv](https://docs.astral.sh/uv/)) from
the pinned google/fonts commit: cut to the same subsets, variable axes kept except as a face's
`limits` pins or narrows them, written to both apps with their licenses. The build is
reproducible. A family without a `source` (Instrument) has hand-maintained files.

## Adding a theme

1. Pick a palette, or add `palettes/<id>.json` with every role.
2. Pick a style. A new one is a bigger decision: it's a way surfaces are made that every
   palette has to work with, in light and dark.
3. Pick a title and a body font, or add a family (below).
4. Add the theme to `themes.json`. Its id is stored on devices: never rename one.
5. `mise run themes`, then `mise run test:themes`.

Adding a font family: add it to `fonts.json` with a `source` and its faces (PostScript names as
`mise run themes:fonts` reports them), install its fontsource package in `apps/web`, run
`mise run themes:fonts`, and add its iOS files to `UIAppFonts` in `apps/ios/Config/Info.plist`.
The tests check that every face is bundled and registered.

## What each app does with it

**Web** (done): `src/theme.ts` sets a theme's attributes on `<html>` and stores them, and
`public/boot.js` applies them before the first paint. The Appearance settings show every theme
as a preview built from the same attributes.

**Android** (done): `settings/Appearance.kt` stores the theme id next to the mode and sets
`CurrentTheme`. `Tokens.of(theme, dark)` gives the colors the UI draws with; `Type` sets text in
the theme's faces (weight, the title's axes, opsz from the size, the title's scale and
tracking); `corners()` applies `radius-scale`; panels blur from Android 12 (opaque below it, and in a style
without a blur); the editor (`EditorStyle`) takes code, tag and
checkbox details from it. Fonts, shapes and paints are read as screens are built, so a new theme
recreates the activity, as a change of mode does. Amber draws what the app drew before themes;
`ThemesTest` holds it to the old values. Not used yet: shadows (Compose draws its own elevation),
`motion`, `layout` and the sidebar option.

**iOS** (done): `AppGraph` stores the theme id next to the mode and sets `CurrentTheme`.
`Palette`'s colors resolve against it as they're drawn; `Typeface` and the `Font` styles set text in
its faces (titles through `titleFont`, since SwiftUI's own fonts can't carry a face's axes);
`corner()` applies `radius-scale`; the editor (`EditorStyle`) takes code, tag and checkbox details
from it. The system's Liquid Glass stays on bars, sheets and buttons; the two pills the app draws
(the formatting bar, the undo toast) are glass in a style that blurs and its solid panel in one
that doesn't. A new theme rebuilds every screen, back on the list. Amber draws what the app drew
before themes; `ThemesTests` holds it to the old values. Not used yet: most shadows, `motion`,
`selection`, `layout` and the sidebar option.

On both phones the sidebar is a drawer, so the sidebar option doesn't apply: the web, too, gives
a docked sidebar only its own color.
