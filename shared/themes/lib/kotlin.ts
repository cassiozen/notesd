import { argb, round } from './color.ts'
import {
  MODES,
  OPTIONAL_ROLES,
  expandLayers,
  requiredRoles,
  resolvePaint,
  type Blur,
  type Curve,
  type Family,
  type Library,
  type Mode,
  type Paint,
  type Palette,
  type Shadow,
  type Style,
  type Theme,
} from './model.ts'
import { camel } from './swift.ts'

export const KOTLIN = 'apps/android/app/src/main/kotlin/notes/app/ui/themes/Themes.generated.kt'

const f = (n: number) => `${round(n)}f`
const str = (s: string) => JSON.stringify(s)
const long = (h: string) => `0x${h}`

function call(name: string, args: [string, string][], depth: number): string {
  const pad = '    '.repeat(depth + 1)
  return `${name}(\n${args.map(([k, v]) => `${pad}${k} = ${v}`).join(',\n')}\n${'    '.repeat(depth)})`
}

function list(items: string[], depth: number): string {
  if (!items.length) return 'emptyList()'
  const pad = '    '.repeat(depth + 1)
  return `listOf(\n${items.map((i) => `${pad}${i}`).join(',\n')}\n${'    '.repeat(depth)})`
}

function color(style: Style, palette: Palette, paint: Paint): string {
  const [l, d] = MODES.map((m) => argb(resolvePaint(style, palette, paint, m)))
  return `ThemeColor(${long(l ?? '')}, ${long(d ?? '')})`
}

function shadow(style: Style, palette: Palette, s: Shadow, depth: number): string {
  const layers = (mode: Mode) =>
    list(
      expandLayers(style, s, mode).map(
        (l) =>
          `ThemeShadowLayer(${f(l.x ?? 0)}, ${f(l.y ?? 0)}, ${f(l.blur ?? 0)}, ${f(l.spread ?? 0)}, ` +
          `${long(argb(resolvePaint(style, palette, l.color, mode)))}, inset = ${l.inset ? 'true' : 'false'})`,
      ),
      depth + 1,
    )
  return call('ThemeShadow', [['light', layers('light')], ['dark', layers('dark')]], depth)
}

const blur = (b: Blur) => (b ? `ThemeBlur(${f(b.radius)}, ${f(b.saturate ?? 1)})` : 'null')
function curve(c: Curve, depth: number): string {
  if ('cubicBezier' in c) return `ThemeCurve.CubicBezier(${c.cubicBezier.map(f).join(', ')})`
  const rows: string[] = []
  for (let i = 0; i < c.linear.length; i += 9) rows.push(c.linear.slice(i, i + 9).map(f).join(', '))
  const [outer, inner, item] = [depth, depth + 1, depth + 2].map((d) => '    '.repeat(d))
  return `ThemeCurve.Linear(\n${inner}listOf(\n${rows.map((r) => `${item}${r}`).join(',\n')}\n${inner})\n${outer})`
}

function typeface(family: Family, depth: number): string {
  const faces = family.native.faces.map((face) => {
    const [lo, hi] = typeof face.weight === 'number' ? [face.weight, face.weight] : face.weight
    return `ThemeFontFace(R.font.${face.android}, ${f(lo)}..${f(hi)}, italic = ${face.style === 'italic'})`
  })
  return call(
    'ThemeTypeface',
    [
      ['name', str(family.name)],
      ['faces', list(faces, depth + 1)],
      ['opticalSizing', String(family.opticalSize === 'auto')],
    ],
    depth,
  )
}

function theme(lib: Library, t: Theme): string {
  const palette = lib.palettes.get(t.palette)
  const style = lib.styles.get(t.style)
  const title = lib.fonts.title[t.fonts.title]
  const body = lib.fonts.body[t.fonts.body]
  if (!palette || !style || !title || !body) throw new Error(`${t.id}: missing a part`)
  const titleFamily = lib.fonts.families[title.family]
  const bodyFamily = lib.fonts.families[body.family]
  if (!titleFamily || !bodyFamily) throw new Error(`${t.id}: missing a font family`)
  const { material, lines, depth, backdrop, motion, details, shape } = style
  const roles = [...requiredRoles(), ...Object.keys(OPTIONAL_ROLES)]
  const c = (p: Paint) => color(style, palette, p)
  const colors: [string, string][] = [
    ...roles.map((r): [string, string] => [camel(r), c({ role: r })]),
    ['glassHi', c(material['glass-hi'])],
    ['edge', c(material.edge)],
    ['field', c(material.field)],
    ['chip', c(material.chip)],
    ['hair', c(lines.hair)],
    ['hairStrong', c(lines['hair-strong'])],
    ['hover', c(lines.hover)],
    ['on', c(lines.on)],
    ['wash', backdrop.wash ? c(backdrop.wash) : 'null'],
    ['tagBackground', details['tag-bg'] ? c(details['tag-bg']) : 'null'],
    ['tagInk', details['tag-ink'] === 'currentColor' ? 'null' : c(details['tag-ink'])],
    ['selection', details.selection ? c(details.selection) : 'null'],
  ]
  const shadows: [string, string][] = [
    ['inset', shadow(style, palette, material.inset, 3)],
    ...Object.entries(depth).map(([k, s]): [string, string] => [camel(k), shadow(style, palette, s, 3)]),
    ['focusRing', shadow(style, palette, details['focus-ring'], 3)],
  ]
  const corner = details['check-radius'] === 'circle' ? 'ThemeCorner.Circle' : `ThemeCorner.Radius(${f(details['check-radius'])})`
  const axes = Object.entries(title.axes)
  return call(
    'NotesTheme',
    [
      ['id', str(t.id)],
      ['name', str(t.name)],
      ['mood', str(t.mood)],
      ['colors', call('ThemeColors', colors, 2)],
      ['shadows', call('ThemeShadows', shadows, 2)],
      [
        'blurs',
        call(
          'ThemeBlurs',
          [
            ['panel', blur(material.blur)],
            ['pop', blur(material['blur-pop'])],
            ['dialog', blur(material['blur-dialog'])],
            ['scrim', blur(material['blur-scrim'])],
          ],
          2,
        ),
      ],
      [
        'shape',
        call(
          'ThemeShape',
          [
            ['radiusScale', f(shape['radius-scale'])],
            ['checkCorner', corner],
            ['checkMark', details['check-mark'] === 'dot' ? 'ThemeCheckMark.Dot' : 'ThemeCheckMark.Fill'],
            ['tagRadius', f(details['tag-radius'])],
            ['tagPadding', `ThemeInsets(${f(details['tag-padding'][0])}, ${f(details['tag-padding'][1])})`],
            ['doneStrikethrough', String(details['done-decoration'] === 'line-through')],
          ],
          2,
        ),
      ],
      ['motion', call('ThemeMotion', [['spring', curve(motion.spring, 3)], ['easeOut', curve(motion['ease-out'], 3)]], 2)],
      [
        'title',
        call(
          'ThemeTitleFont',
          [
            ['typeface', typeface(titleFamily, 3)],
            ['weight', f(title.weight)],
            ['italic', String(title.style === 'italic')],
            ['quoteWeight', f(title.quote.weight)],
            ['quoteItalic', String(title.quote.style === 'italic')],
            ['axes', axes.length ? `mapOf(${axes.map(([k, v]) => `${str(k)} to ${f(v)}`).join(', ')})` : 'emptyMap()'],
            ['scale', f(title.scale)],
            ['tracking', f(title.tracking)],
          ],
          2,
        ),
      ],
      ['body', typeface(bodyFamily, 2)],
    ],
    1,
  )
}

const TYPES = `/** A color in light and dark, each 0xAARRGGBB. */
data class ThemeColor(val light: Long, val dark: Long) {
    fun resolve(dark: Boolean): Color = Color(if (dark) this.dark else light)
}

/** One layer of a shadow, as CSS's box-shadow describes it. color is 0xAARRGGBB. */
data class ThemeShadowLayer(
    val x: Float,
    val y: Float,
    val blur: Float,
    val spread: Float,
    val color: Long,
    val inset: Boolean
)

/** A shadow's layers in each mode, the first on top; none is an empty list. */
data class ThemeShadow(val light: List<ThemeShadowLayer>, val dark: List<ThemeShadowLayer>)

/** A see-through panel's blur. Below Android 12 there's no blur: the panel is drawn opaque. */
data class ThemeBlur(val radius: Float, val saturation: Float)

sealed interface ThemeCurve {
    data class CubicBezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : ThemeCurve

    /** Evenly spaced points of a curve: a spring. */
    data class Linear(val points: List<Float>) : ThemeCurve
}

sealed interface ThemeCorner {
    object Circle : ThemeCorner

    data class Radius(val radius: Float) : ThemeCorner
}

/** A ticked box: a dot inside an accent ring, or filled. */
enum class ThemeCheckMark { Dot, Fill }

/** In em. */
data class ThemeInsets(val vertical: Float, val horizontal: Float)

/** weights: one weight, or the range a variable font covers. */
data class ThemeFontFace(val resource: Int, val weights: ClosedFloatingPointRange<Float>, val italic: Boolean)

/** opticalSizing: has an opsz axis; set it to the text size, as browsers do by themselves. */
data class ThemeTypeface(val name: String, val faces: List<ThemeFontFace>, val opticalSizing: Boolean)

/**
 * The title face and how titles are set in it; quotes use it too, lighter. axes: variation axes
 * besides weight and optical size. scale multiplies title sizes, which were written for
 * Instrument Serif, a narrow face. tracking is added letter spacing, in em.
 */
data class ThemeTitleFont(
    val typeface: ThemeTypeface,
    val weight: Float,
    val italic: Boolean,
    val quoteWeight: Float,
    val quoteItalic: Boolean,
    val axes: Map<String, Float>,
    val scale: Float,
    val tracking: Float
)

/**
 * Palette roles, then the style's materials resolved on that palette. on: a selected row.
 * wash: the glow behind the window. tagInk: null for the text around a tag. selection: null for
 * the system's own.
 */
data class ThemeColors(
__COLOR_FIELDS__
)

data class ThemeShadows(
__SHADOW_FIELDS__
)

data class ThemeBlurs(val panel: ThemeBlur?, val pop: ThemeBlur?, val dialog: ThemeBlur?, val scrim: ThemeBlur?)

/** radiusScale multiplies every corner radius. */
data class ThemeShape(
    val radiusScale: Float,
    val checkCorner: ThemeCorner,
    val checkMark: ThemeCheckMark,
    val tagRadius: Float,
    val tagPadding: ThemeInsets,
    val doneStrikethrough: Boolean
)

/** spring: things that appear. */
data class ThemeMotion(val spring: ThemeCurve, val easeOut: ThemeCurve)

/** body: everything that isn't a title. */
data class NotesTheme(
    val id: String,
    val name: String,
    val mood: String,
    val colors: ThemeColors,
    val shadows: ThemeShadows,
    val blurs: ThemeBlurs,
    val shape: ThemeShape,
    val motion: ThemeMotion,
    val title: ThemeTitleFont,
    val body: ThemeTypeface
)`

export function kotlin(lib: Library): string {
  const roles = [...requiredRoles(), ...Object.keys(OPTIONAL_ROLES)]
  const nullable = new Set(['wash', 'tagBackground', 'tagInk', 'selection'])
  const colorNames = [...roles.map(camel), 'glassHi', 'edge', 'field', 'chip', 'hair', 'hairStrong', 'hover', 'on', ...nullable]
  const anyStyle = lib.styles.values().next().value
  if (!anyStyle) throw new Error('no styles')
  const shadowNames = ['inset', ...Object.keys(anyStyle.depth), 'focus-ring'].map(camel)
  const types = TYPES.replace(
    '__COLOR_FIELDS__',
    colorNames.map((n) => `    val ${n}: ThemeColor${nullable.has(n) ? '?' : ''}`).join(',\n'),
  ).replace('__SHADOW_FIELDS__', shadowNames.map((n) => `    val ${n}: ThemeShadow`).join(',\n'))
  const themes = lib.themes.map((t) => `    val ${camel(t.id)} = ${theme(lib, t)}`)
  return [
    '// Generated from shared/themes by shared/themes/generate.ts. Change the JSON there and run `mise run themes`.',
    '// The format and what each value means: docs/contributing/themes.md.',
    '',
    'package notes.app.ui.themes',
    '',
    'import androidx.compose.ui.graphics.Color',
    'import notes.app.R',
    '',
    types,
    '',
    'object Themes {',
    themes.join('\n\n'),
    '',
    `    val all = listOf(${lib.themes.map((t) => camel(t.id)).join(', ')})`,
    `    val fallback = ${camel(lib.default)}`,
    '',
    '    /** fallback for an id no theme has (a stored one from a build that had it). */',
    '    fun theme(id: String): NotesTheme = all.firstOrNull { it.id == id } ?: fallback',
    '}',
    '',
  ].join('\n')
}
