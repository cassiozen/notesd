import { readFileSync } from 'node:fs'
import { relative, resolve } from 'node:path'
import { hex, parse, rgb, round, withAlpha } from './color.ts'
import {
  ROOT,
  expandLayers,
  layersIn,
  paintIn,
  roleColor,
  type Blur,
  type Border,
  type Curve,
  type Layer,
  type Library,
  type Paint,
  type Shadow,
  type SimplePaint,
  type Style,
} from './model.ts'

export const WEB_CSS = 'apps/web/src/themes/generated/themes.css'
export const WEB_TS = 'apps/web/src/themes/generated/themes.ts'
const WEB_MODULES = resolve(ROOT, 'apps/web/node_modules')

const HEADER = 'Generated from shared/themes by shared/themes/generate.ts. Change the JSON there and run `mise run themes`.'

const px = (n: number) => (n === 0 ? '0' : `${round(n)}px`)
/** Always with its unit: layout lengths go into calc(), where a bare 0 is invalid. */
const length = (n: number) => `${round(n)}px`
const pct = (a: number) => `${round(a * 100)}%`
const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)

function simple(p: SimplePaint): string {
  if ('token' in p) return `var(--${p.token})`
  if ('role' in p) return p.alpha === 1 ? `var(--${p.role})` : `color-mix(in srgb, var(--${p.role}) ${pct(p.alpha)}, transparent)`
  return p.alpha === 1 ? p.color : rgb(withAlpha(parse(p.color), p.alpha))
}

/** One value, or light-dark() where the modes differ. Palettes are var()s, so any palette fits. */
function paint(p: Paint): string {
  const [light, dark] = [paintIn(p, 'light'), paintIn(p, 'dark')]
  return same(light, dark) ? simple(light) : `light-dark(${simple(light)}, ${simple(dark)})`
}

function geometry(l: Layer): string {
  const parts = [px(l.x ?? 0), px(l.y ?? 0), px(l.blur ?? 0), ...(l.spread ? [px(l.spread)] : [])]
  return `${l.inset ? 'inset ' : ''}${parts.join(' ')}`
}

/**
 * A layer list for box-shadow. Where light and dark have the same layers, each layer's color is
 * light-dark(); where they don't, both sets are drawn, each transparent in the other mode.
 */
function shadow(style: Style, s: Shadow): string {
  const [light, dark] = [layersIn(s, 'light'), layersIn(s, 'dark')]
  const shape = (items: typeof light) => items.map((i) => (typeof i === 'string' ? i : { ...i, color: undefined }))
  const ref = (name: string) => `var(--${name})`
  let parts: string[]
  if (same(shape(light), shape(dark)))
    parts = light.map((item, n) => {
      if (typeof item === 'string') return ref(item)
      const other = dark[n] as Layer
      const [l, d] = [paintIn(item.color, 'light'), paintIn(other.color, 'dark')]
      return `${geometry(item)} ${same(l, d) ? simple(l) : `light-dark(${simple(l)}, ${simple(d)})`}`
    })
  // A named shadow here would draw in both modes: its layers go in instead, each to its own mode.
  else
    parts = [
      ...expandLayers(style, s, 'light').map((i) => `${geometry(i)} light-dark(${simple(paintIn(i.color, 'light'))}, transparent)`),
      ...expandLayers(style, s, 'dark').map((i) => `${geometry(i)} light-dark(transparent, ${simple(paintIn(i.color, 'dark'))})`),
    ]
  return parts.length ? parts.join(',\n    ') : '0 0 transparent'
}

const blur = (b: Blur) => (b ? `blur(${px(b.radius)})${b.saturate === undefined ? '' : ` saturate(${round(b.saturate)})`}` : 'none')
const border = (b: Border) => (b ? `${px(b.width)} solid ${paint(b.color)}` : '0 solid transparent')
const curve = (c: Curve) => ('linear' in c ? `linear(${c.linear.map(round).join(', ')})` : `cubic-bezier(${c.cubicBezier.map(round).join(', ')})`)

function styleBlock(s: Style): string {
  const { layout, shape, material, lines, depth, backdrop, motion, details } = s
  const decls: [string, string][] = [
    ['radius-scale', round(shape['radius-scale'])],
    ['app-gap', length(layout['app-gap'])],
    ['app-padding', length(layout['app-padding'])],
    ['pane-radius', length(layout['pane-radius'])],
    ['pane-border', border(layout['pane-border'])],
    ['pane-shadow', shadow(s, layout['pane-shadow'])],
    ['column-rule', border(layout['column-rule'])],
    ['glass-hi', paint(material['glass-hi'])],
    ['blur', blur(material.blur)],
    ['blur-pop', blur(material['blur-pop'])],
    ['blur-dialog', blur(material['blur-dialog'])],
    ['blur-scrim', blur(material['blur-scrim'])],
    ['edge', paint(material.edge)],
    ['inset', shadow(s, material.inset)],
    ['field', paint(material.field)],
    ['chip', paint(material.chip)],
    ['hair', paint(lines.hair)],
    ['hair-strong', paint(lines['hair-strong'])],
    ['hover', paint(lines.hover)],
    ['on', paint(lines.on)],
    ...Object.entries(depth).map(([name, layers]): [string, string] => [name, shadow(s, layers)]),
    ['wash', backdrop.wash ? paint(backdrop.wash) : 'transparent'],
    ['spring', curve(motion.spring)],
    ['ease-out', curve(motion['ease-out'])],
    ['focus-ring', shadow(s, details['focus-ring'])],
    ['check-radius', details['check-radius'] === 'circle' ? '50%' : px(details['check-radius'])],
    ['check-mark', details['check-mark'] === 'dot' ? 'inset 0 0 0 3.5px var(--accent), inset 0 0 0 8px var(--check-ink)' : '0 0 transparent'],
    ['tag-bg', details['tag-bg'] ? paint(details['tag-bg']) : 'transparent'],
    ['tag-ink', details['tag-ink'] === 'currentColor' ? 'currentColor' : paint(details['tag-ink'])],
    ['tag-radius', px(details['tag-radius'])],
    ['tag-padding', details['tag-padding'].every((n) => n === 0) ? '0' : details['tag-padding'].map((n) => `${round(n)}em`).join(' ')],
    ['done-decoration', details['done-decoration']],
  ]
  if (details.selection) decls.push(['selection', paint(details.selection)])
  const rule = `/* ${s.description} */\n[data-style='${s.id}'] {\n${decls.map(([k, v]) => `  --${k}: ${v};`).join('\n')}\n}`
  // A style without a selection color keeps the platform's own, which no color value reproduces.
  return details.selection ? `${rule}\n\n:root[data-style='${s.id}'] ::selection {\n  background: var(--selection);\n}` : rule
}

function paletteBlock(lib: Library, id: string): string {
  const p = lib.palettes.get(id)
  if (!p) throw new Error(`no palette ${id}`)
  const decls = Object.entries(p.colors).map(([role, v]) => `  --${role}: ${typeof v === 'string' ? v : `light-dark(${v.light}, ${v.dark})`};`)
  return `${p.description ? `/* ${p.description} */\n` : ''}[data-palette='${id}'] {\n${decls.join('\n')}\n}`
}

/** The subsets' @font-face rules from a fontsource stylesheet, their files found from here. */
function fontFaces(pkg: string, file: string, subsets: string[]): string[] {
  const source = readFileSync(resolve(WEB_MODULES, pkg, file), 'utf8')
  const from = relative(resolve(ROOT, WEB_CSS, '..'), resolve(WEB_MODULES, pkg))
  const keep = new RegExp(`-(${subsets.map((s) => s.replace('-', '\\-')).join('|')})-`)
  const faces: string[] = []
  for (const m of source.matchAll(/\/\*\s*([^*]+?)\s*\*\/\s*(@font-face\s*\{[^}]*\})/g)) {
    const [, name = '', rule = ''] = m
    // latin-ext is checked first in the alternation, so plain latin doesn't swallow it.
    const subset = /-(latin-ext|latin)-/.exec(name)?.[1]
    if (subset && keep.test(`-${subset}-`)) faces.push(`/* ${name} */\n${rule.replace(/url\(\.\/files\//g, `url(${from}/files/`)}`)
  }
  if (!faces.length) throw new Error(`${pkg}/${file}: no @font-face for ${subsets.join(', ')}`)
  return faces
}

function fontBlocks(lib: Library): string[] {
  const { fonts } = lib
  const faces = [...new Set(Object.values(fonts.families).flatMap((f) => f.web.css.flatMap((css) => fontFaces(f.web.package, css, fonts.subsets))))]
  const stack = (familyId: string) => {
    const f = fonts.families[familyId]
    if (!f) throw new Error(`no family ${familyId}`)
    return `'${f.css}', ${f.fallback}`
  }
  const titles = Object.entries(fonts.title).map(([id, t]) => {
    const axes = Object.entries(t.axes)
    return [
      ...(t.description ? [`/* ${t.description} */`] : []),
      `[data-title-font='${id}'] {`,
      `  --font-title: ${stack(t.family)};`,
      `  --title-style: ${t.style};`,
      `  --title-weight: ${t.weight};`,
      `  --quote-weight: ${t.quote.weight};`,
      `  --quote-style: ${t.quote.style};`,
      `  --title-axes: ${axes.length ? axes.map(([k, v]) => `'${k}' ${round(v)}`).join(', ') : 'normal'};`,
      `  --title-scale: ${round(t.scale)};`,
      `  --title-tracking: ${round(t.tracking)}em;`,
      '}',
    ].join('\n')
  })
  const bodies = Object.entries(fonts.body).map(([id, b]) =>
    [...(b.description ? [`/* ${b.description} */`] : []), `[data-body-font='${id}'] {`, `  --font-body: ${stack(b.family)};`, '}'].join('\n'),
  )
  return [...faces, ...titles, ...bodies]
}

export function webCss(lib: Library): string {
  const palettes = [...new Set(lib.themes.map((t) => t.palette))]
  const styles = [...new Set(lib.themes.map((t) => t.style))]
  return [
    `/* ${HEADER} */`,
    ...fontBlocks(lib),
    ...palettes.map((id) => paletteBlock(lib, id)),
    ...styles.map((id) => {
      const s = lib.styles.get(id)
      if (!s) throw new Error(`no style ${id}`)
      return styleBlock(s)
    }),
  ].join('\n\n') + '\n'
}

export function webTs(lib: Library): string {
  const themes = lib.themes.map((t) => {
    const palette = lib.palettes.get(t.palette)
    if (!palette) throw new Error(`no palette ${t.palette}`)
    const bar = { light: hex(roleColor(palette, 'bg', 'light')), dark: hex(roleColor(palette, 'bg', 'dark')) }
    return `  ${JSON.stringify({ ...t, bar })},`
  })
  return [
    `// ${HEADER}`,
    '',
    '/** bar: --bg as hex, for the browser\'s title bar (<meta name="theme-color">), which takes no light-dark(). */',
    'export const THEMES = [',
    ...themes,
    '] as const',
    '',
    `export const DEFAULT_THEME = ${JSON.stringify(lib.default)}`,
    '',
  ].join('\n')
}
