import { argb, round } from './color.ts'
import {
  MODES,
  expandLayers,
  resolvePaint,
  type Blur,
  type Curve,
  type Family,
  type Library,
  type Mode,
  type Paint,
  type Shadow,
  type Style,
  type Theme,
  requiredRoles,
  OPTIONAL_ROLES,
} from './model.ts'

export const SWIFT = 'apps/ios/Notes/UI/Themes.generated.swift'

/** ink-2 → ink2, accent-deep → accentDeep. */
export const camel = (name: string) => name.replace(/-([a-z0-9])/g, (_, c: string) => c.toUpperCase())

const num = (n: number) => round(n)
const str = (s: string) => JSON.stringify(s)
/** swift-format groups hex digits in fours. */
const hexLit = (h: string) => `0x${h.slice(0, 4)}_${h.slice(4)}`

/** Writes a call with one argument per line, nested calls indented under it. */
function call(name: string, args: [string, string][], depth: number): string {
  const pad = '    '.repeat(depth + 1)
  return `${name}(\n${args.map(([k, v]) => `${pad}${k}: ${v}`).join(',\n')}\n${'    '.repeat(depth)})`
}

/** One item on the line it starts (swift-format wants no trailing comma then); more, one per line. */
function list(items: string[], depth: number): string {
  if (!items.length) return '[]'
  if (items.length === 1) return `[${items[0]}]`
  const pad = '    '.repeat(depth + 1)
  return `[\n${items.map((i) => `${pad}${i},`).join('\n')}\n${'    '.repeat(depth)}]`
}

function color(style: Style, theme: Ctx, paint: Paint): string {
  const [l, d] = MODES.map((m) => argb(resolvePaint(style, theme.palette, paint, m)))
  return `ThemeColor(light: ${hexLit(l ?? '')}, dark: ${hexLit(d ?? '')})`
}

type Ctx = { palette: NonNullable<ReturnType<Library['palettes']['get']>> }

function shadow(style: Style, ctx: Ctx, s: Shadow, depth: number): string {
  const layers = (mode: Mode) =>
    list(
      expandLayers(style, s, mode).map(
        (l) =>
          `ThemeShadowLayer(x: ${num(l.x ?? 0)}, y: ${num(l.y ?? 0)}, blur: ${num(l.blur ?? 0)}, spread: ${num(l.spread ?? 0)}, ` +
          `color: ${hexLit(argb(resolvePaint(style, ctx.palette, l.color, mode)))}, inset: ${l.inset ? 'true' : 'false'})`,
      ),
      depth + 1,
    )
  return call('ThemeShadow', [['light', layers('light')], ['dark', layers('dark')]], depth)
}

const blur = (b: Blur) => (b ? `ThemeBlur(radius: ${num(b.radius)}, saturation: ${num(b.saturate ?? 1)})` : 'nil')
function curve(c: Curve, depth: number): string {
  if ('cubicBezier' in c) return `.cubicBezier(${c.cubicBezier.map(num).join(', ')})`
  const rows: string[] = []
  for (let i = 0; i < c.linear.length; i += 9) rows.push(c.linear.slice(i, i + 9).map(num).join(', '))
  const pad = '    '.repeat(depth + 1)
  return `.linear([\n${rows.map((r) => `${pad}${r},`).join('\n')}\n${'    '.repeat(depth)}])`
}

function typeface(family: Family, depth: number): string {
  const faces = family.native.faces.map((f) => {
    const [lo, hi] = typeof f.weight === 'number' ? [f.weight, f.weight] : f.weight
    return `ThemeFontFace(postScriptName: ${str(f.postscript)}, weights: ${num(lo)}...${num(hi)}, italic: ${f.style === 'italic'})`
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
  const ctx = { palette }
  const titleFamily = lib.fonts.families[title.family]
  const bodyFamily = lib.fonts.families[body.family]
  if (!titleFamily || !bodyFamily) throw new Error(`${t.id}: missing a font family`)
  const { material, lines, depth, backdrop, motion, details, shape } = style
  const roles = [...requiredRoles(), ...Object.keys(OPTIONAL_ROLES)]
  const colors: [string, string][] = [
    ...roles.map((r): [string, string] => [camel(r), color(style, ctx, { role: r })]),
    ['glassHi', color(style, ctx, material['glass-hi'])],
    ['edge', color(style, ctx, material.edge)],
    ['field', color(style, ctx, material.field)],
    ['chip', color(style, ctx, material.chip)],
    ['hair', color(style, ctx, lines.hair)],
    ['hairStrong', color(style, ctx, lines['hair-strong'])],
    ['hover', color(style, ctx, lines.hover)],
    ['on', color(style, ctx, lines.on)],
    ['wash', backdrop.wash ? color(style, ctx, backdrop.wash) : 'nil'],
    ['tagBackground', details['tag-bg'] ? color(style, ctx, details['tag-bg']) : 'nil'],
    ['tagInk', details['tag-ink'] === 'currentColor' ? 'nil' : color(style, ctx, details['tag-ink'])],
    ['selection', details.selection ? color(style, ctx, details.selection) : 'nil'],
  ]
  const shadows: [string, string][] = [
    ['inset', shadow(style, ctx, material.inset, 3)],
    ...Object.entries(depth).map(([k, s]): [string, string] => [camel(k), shadow(style, ctx, s, 3)]),
    ['focusRing', shadow(style, ctx, details['focus-ring'], 3)],
  ]
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
            ['radiusScale', num(shape['radius-scale'])],
            ['checkCorner', details['check-radius'] === 'circle' ? '.circle' : `.radius(${num(details['check-radius'])})`],
            ['checkMark', `.${details['check-mark']}`],
            ['tagRadius', num(details['tag-radius'])],
            ['tagPadding', `ThemeInsets(vertical: ${num(details['tag-padding'][0])}, horizontal: ${num(details['tag-padding'][1])})`],
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
            ['weight', num(title.weight)],
            ['italic', String(title.style === 'italic')],
            ['quoteWeight', num(title.quote.weight)],
            ['quoteItalic', String(title.quote.style === 'italic')],
            ['axes', Object.keys(title.axes).length ? `[${Object.entries(title.axes).map(([k, v]) => `${str(k)}: ${num(v)}`).join(', ')}]` : '[:]'],
            ['scale', num(title.scale)],
            ['tracking', num(title.tracking)],
          ],
          2,
        ),
      ],
      ['body', typeface(bodyFamily, 2)],
    ],
    1,
  )
}

const TYPES = `/// A color in light and dark appearance, each 0xAARRGGBB.
struct ThemeColor: Sendable, Equatable {
    let light: UInt32
    let dark: UInt32

    /// Follows the trait collection it's drawn in.
    var uiColor: UIColor {
        UIColor { $0.userInterfaceStyle == .dark ? Self.make(dark) : Self.make(light) }
    }

    private static func make(_ argb: UInt32) -> UIColor {
        UIColor(
            red: CGFloat((argb >> 16) & 0xFF) / 255, green: CGFloat((argb >> 8) & 0xFF) / 255,
            blue: CGFloat(argb & 0xFF) / 255, alpha: CGFloat((argb >> 24) & 0xFF) / 255)
    }
}

/// One layer of a shadow, as CSS's box-shadow describes it. color is 0xAARRGGBB.
struct ThemeShadowLayer: Sendable, Equatable {
    let x: CGFloat
    let y: CGFloat
    let blur: CGFloat
    let spread: CGFloat
    let color: UInt32
    let inset: Bool
}

/// A shadow's layers in each appearance, the first on top; none is an empty list.
struct ThemeShadow: Sendable, Equatable {
    let light: [ThemeShadowLayer]
    let dark: [ThemeShadowLayer]
}

/// A see-through panel's blur: the closest system material stands in for it.
struct ThemeBlur: Sendable, Equatable {
    let radius: CGFloat
    let saturation: CGFloat
}

enum ThemeCurve: Sendable, Equatable {
    case cubicBezier(CGFloat, CGFloat, CGFloat, CGFloat)
    /// Evenly spaced points of a curve: a spring.
    case linear([CGFloat])
}

enum ThemeCorner: Sendable, Equatable {
    case circle
    case radius(CGFloat)
}

/// A ticked box: a dot inside an accent ring, or filled.
enum ThemeCheckMark: Sendable, Equatable {
    case dot
    case fill
}

/// In em.
struct ThemeInsets: Sendable, Equatable {
    let vertical: CGFloat
    let horizontal: CGFloat
}

struct ThemeFontFace: Sendable, Equatable {
    let postScriptName: String
    /// One weight, or the range a variable font covers.
    let weights: ClosedRange<CGFloat>
    let italic: Bool
}

struct ThemeTypeface: Sendable, Equatable {
    let name: String
    let faces: [ThemeFontFace]
    /// Has an opsz axis: set it to the point size, as browsers do by themselves.
    let opticalSizing: Bool
}

/// The title face and how titles are set in it. Quotes use it too, lighter.
struct ThemeTitleFont: Sendable, Equatable {
    let typeface: ThemeTypeface
    let weight: CGFloat
    let italic: Bool
    let quoteWeight: CGFloat
    let quoteItalic: Bool
    /// Variation axes for titles, besides weight and optical size.
    let axes: [String: CGFloat]
    /// Multiplies title sizes, which were written for Instrument Serif, a narrow face.
    let scale: CGFloat
    /// Added letter spacing, in em.
    let tracking: CGFloat
}

/// Palette roles, then the style's materials resolved on that palette.
struct ThemeColors: Sendable, Equatable {
__COLOR_FIELDS__
}

struct ThemeShadows: Sendable, Equatable {
__SHADOW_FIELDS__
}

struct ThemeBlurs: Sendable, Equatable {
    let panel: ThemeBlur?
    let pop: ThemeBlur?
    let dialog: ThemeBlur?
    let scrim: ThemeBlur?
}

struct ThemeShape: Sendable, Equatable {
    /// Multiplies every corner radius.
    let radiusScale: CGFloat
    let checkCorner: ThemeCorner
    let checkMark: ThemeCheckMark
    let tagRadius: CGFloat
    let tagPadding: ThemeInsets
    let doneStrikethrough: Bool
}

struct ThemeMotion: Sendable, Equatable {
    /// Things that appear.
    let spring: ThemeCurve
    let easeOut: ThemeCurve
}

struct NotesTheme: Sendable, Equatable, Identifiable {
    let id: String
    let name: String
    let mood: String
    let colors: ThemeColors
    let shadows: ThemeShadows
    let blurs: ThemeBlurs
    let shape: ThemeShape
    let motion: ThemeMotion
    let title: ThemeTitleFont
    /// Everything that isn't a title.
    let body: ThemeTypeface
}`

export function swift(lib: Library): string {
  const roles = [...requiredRoles(), ...Object.keys(OPTIONAL_ROLES)]
  const colorFields = [
    ...roles.map((r) => `    let ${camel(r)}: ThemeColor`),
    '    let glassHi: ThemeColor',
    '    let edge: ThemeColor',
    '    let field: ThemeColor',
    '    let chip: ThemeColor',
    '    let hair: ThemeColor',
    '    let hairStrong: ThemeColor',
    '    let hover: ThemeColor',
    '    /// A selected row.',
    '    let on: ThemeColor',
    '    /// The glow behind the window; nil for none.',
    '    let wash: ThemeColor?',
    '    /// Behind #tags; nil for none.',
    '    let tagBackground: ThemeColor?',
    "    /// #tags' text; nil for the text around them.",
    '    let tagInk: ThemeColor?',
    "    /// Selected text; nil for the system's own.",
    '    let selection: ThemeColor?',
  ]
  const anyStyle = lib.styles.values().next().value
  if (!anyStyle) throw new Error('no styles')
  const shadowNames = ['inset', ...Object.keys(anyStyle.depth), 'focus-ring']
  const types = TYPES.replace('__COLOR_FIELDS__', colorFields.join('\n')).replace(
    '__SHADOW_FIELDS__',
    shadowNames.map((n) => `    let ${camel(n)}: ThemeShadow`).join('\n'),
  )
  const themes = lib.themes.map((t) => `    static let ${camel(t.id)} = ${theme(lib, t)}`)
  return [
    '// Generated from shared/themes by shared/themes/generate.ts. Change the JSON there and run `mise run themes`.',
    '// The format and what each value means: docs/contributing/themes.md.',
    '',
    'import UIKit',
    '',
    types,
    '',
    'enum Themes {',
    `    static let all: [NotesTheme] = [${lib.themes.map((t) => camel(t.id)).join(', ')}]`,
    `    static let fallback = ${camel(lib.default)}`,
    '',
    '    /// fallback for an id no theme has (a stored one from a build that had it).',
    '    static func theme(id: String) -> NotesTheme {',
    '        all.first { $0.id == id } ?? fallback',
    '    }',
    '',
    themes.join('\n\n'),
    '}',
    '',
  ].join('\n')
}
