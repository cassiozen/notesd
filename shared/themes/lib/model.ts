import { readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { Ajv2020 } from 'ajv/dist/2020.js'
import { parse, withAlpha, type RGBA } from './color.ts'

/** shared/themes, where the definitions are. */
export const DIR = resolve(import.meta.dirname, '..')
/** The repository root, where the outputs go. */
export const ROOT = resolve(DIR, '../..')

export type Mode = 'light' | 'dark'
export const MODES: Mode[] = ['light', 'dark']

type PerMode<T> = { light: T; dark: T }
export type ColorValue = string | PerMode<string>
export type Alpha = number | PerMode<number>
export type Paint =
  | { role: string; alpha?: Alpha }
  | { color: string; alpha?: Alpha }
  | { token: string }
  | { light: Paint; dark: Paint }
export type Layer = { x?: number; y?: number; blur?: number; spread?: number; inset?: boolean; color: Paint }
export type Layers = (Layer | string)[]
export type Shadow = Layers | PerMode<Layers>
export type Blur = { radius: number; saturate?: number } | null
export type Border = { width: number; color: Paint } | null
export type Curve = { cubicBezier: number[] } | { linear: number[] }

export type Palette = { id: string; description?: string; colors: Record<string, ColorValue> }

export type Style = {
  id: string
  description: string
  layout: {
    'app-gap': number
    'app-padding': number
    'pane-radius': number
    'pane-border': Border
    'pane-shadow': Shadow
    'column-rule': Border
  }
  shape: { 'radius-scale': number }
  material: {
    'glass-hi': Paint
    blur: Blur
    'blur-pop': Blur
    'blur-dialog': Blur
    'blur-scrim': Blur
    edge: Paint
    inset: Shadow
    field: Paint
    chip: Paint
  }
  lines: { hair: Paint; 'hair-strong': Paint; hover: Paint; on: Paint }
  depth: Record<'shadow' | 'shadow-lg' | 'shadow-pop' | 'shadow-bar' | 'shadow-toast' | 'shadow-thumb', Shadow>
  backdrop: { wash: Paint | null }
  motion: { spring: Curve; 'ease-out': Curve }
  details: {
    'focus-ring': Shadow
    'check-radius': 'circle' | number
    'check-mark': 'dot' | 'fill'
    'tag-bg': Paint | null
    'tag-ink': 'currentColor' | Paint
    'tag-radius': number
    'tag-padding': [number, number]
    selection: Paint | null
    'done-decoration': 'none' | 'line-through'
  }
}

export type Face = {
  file?: string
  ios: string
  android: string
  postscript: string
  weight: number | [number, number]
  style: 'normal' | 'italic'
  limits?: Record<string, number | [number, number]>
}
export type Family = {
  name: string
  css: string
  fallback: string
  opticalSize?: 'auto'
  web: { package: string; css: string[] }
  native: { license: string; source?: string; faces: Face[] }
}
export type TitleFont = {
  family: string
  description?: string
  weight: number
  style: 'normal' | 'italic'
  axes: Record<string, number>
  scale: number
  tracking: number
  quote: { weight: number; style: 'normal' | 'italic' }
}
export type BodyFont = { family: string; description?: string }
export type Fonts = {
  sources: { repository: string; commit: string }
  subsets: string[]
  families: Record<string, Family>
  title: Record<string, TitleFont>
  body: Record<string, BodyFont>
}

export type Theme = {
  id: string
  name: string
  mood: string
  palette: string
  style: string
  fonts: { title: string; body: string }
  sidebar: 'theme' | 'tinted' | 'dark'
}

export type Library = {
  default: string
  themes: Theme[]
  palettes: Map<string, Palette>
  styles: Map<string, Style>
  fonts: Fonts
}

function json(path: string): unknown {
  return JSON.parse(readFileSync(resolve(DIR, path), 'utf8'))
}

/** Each file in a directory of definitions, its id from the file name. */
function each(dir: string): [string, unknown][] {
  return readdirSync(resolve(DIR, dir))
    .filter((f) => f.endsWith('.json'))
    .toSorted()
    .map((f) => [f.slice(0, -'.json'.length), json(`${dir}/${f}`)])
}

/** Palette roles that may be left out, and what stands in for them. */
export const OPTIONAL_ROLES: Record<string, string> = { 'check-ink': 'on-accent', sidebar: 'bg' }

/** The roles every palette sets, from the schema, so the two can't disagree. */
export function requiredRoles(): string[] {
  const schema = json('schema/palette.schema.json') as { properties: { colors: { required: string[] } } }
  return schema.properties.colors.required
}

/** Reads and validates every definition; throws with every problem found. */
export function load(): Library {
  const ajv = new Ajv2020({ allErrors: true, strict: true })
  const validators = Object.fromEntries(
    ['palette', 'style', 'fonts', 'themes'].map((kind) => [kind, ajv.compile(json(`schema/${kind}.schema.json`) as object)]),
  )
  const problems: string[] = []
  const check = (kind: string, file: string, data: unknown) => {
    const validate = validators[kind]
    if (validate && !validate(data))
      for (const e of validate.errors ?? []) problems.push(`${file}${e.instancePath}: ${e.message}`)
  }

  const registry = json('themes.json') as { default: string; themes: Theme[] }
  check('themes', 'themes.json', registry)
  const fonts = json('fonts.json') as Fonts
  check('fonts', 'fonts.json', fonts)
  const palettes = new Map<string, Palette>()
  for (const [id, data] of each('palettes')) {
    check('palette', `palettes/${id}.json`, data)
    palettes.set(id, { id, ...(data as Omit<Palette, 'id'>) })
  }
  const styles = new Map<string, Style>()
  for (const [id, data] of each('styles')) {
    check('style', `styles/${id}.json`, data)
    styles.set(id, { id, ...(data as Omit<Style, 'id'>) })
  }
  if (problems.length) throw new Error(`Invalid theme definitions:\n${problems.join('\n')}`)

  const ids = new Set<string>()
  for (const t of registry.themes) {
    if (ids.has(t.id)) problems.push(`themes.json: ${t.id} twice`)
    ids.add(t.id)
    if (!palettes.has(t.palette)) problems.push(`themes.json: ${t.id}: no palettes/${t.palette}.json`)
    if (!styles.has(t.style)) problems.push(`themes.json: ${t.id}: no styles/${t.style}.json`)
    if (!fonts.title[t.fonts.title]) problems.push(`themes.json: ${t.id}: no title font ${t.fonts.title}`)
    if (!fonts.body[t.fonts.body]) problems.push(`themes.json: ${t.id}: no body font ${t.fonts.body}`)
  }
  if (!ids.has(registry.default)) problems.push(`themes.json: the default, ${registry.default}, isn't a theme`)
  for (const [kind, roles] of [
    ['title', fonts.title],
    ['body', fonts.body],
  ] as const)
    for (const [id, font] of Object.entries(roles))
      if (!fonts.families[font.family]) problems.push(`fonts.json: ${kind} ${id}: no family ${font.family}`)
  if (problems.length) throw new Error(`Invalid theme definitions:\n${problems.join('\n')}`)
  return { default: registry.default, themes: registry.themes, palettes, styles, fonts }
}

/** A role's color in one mode, an optional role falling back to the one standing in for it. */
export function roleColor(palette: Palette, role: string, mode: Mode): RGBA {
  const value = palette.colors[role] ?? palette.colors[OPTIONAL_ROLES[role] ?? '']
  if (value === undefined) throw new Error(`${palette.id} has no ${role}`)
  return parse(typeof value === 'string' ? value : value[mode])
}

const isPerMode = <T>(v: unknown): v is PerMode<T> => typeof v === 'object' && v !== null && 'light' in v && 'dark' in v

export function alphaIn(alpha: Alpha | undefined, mode: Mode): number {
  if (alpha === undefined) return 1
  return typeof alpha === 'number' ? alpha : alpha[mode]
}

/** A paint without per-mode parts, as it is in one mode. */
export type SimplePaint = { role: string; alpha: number } | { color: string; alpha: number } | { token: string }

export function paintIn(paint: Paint, mode: Mode): SimplePaint {
  if (isPerMode<Paint>(paint)) return paintIn(paint[mode], mode)
  if ('token' in paint) return paint
  return 'role' in paint ? { role: paint.role, alpha: alphaIn(paint.alpha, mode) } : { color: paint.color, alpha: alphaIn(paint.alpha, mode) }
}

/** The style tokens a { token } paint can name. */
export function colorToken(style: Style, token: string): Paint {
  const all: Record<string, Paint> = { ...style.lines, edge: style.material.edge, field: style.material.field, chip: style.material.chip, 'glass-hi': style.material['glass-hi'] }
  const paint = all[token]
  if (!paint) throw new Error(`${style.id}: no color token ${token}`)
  return paint
}

/** What a paint comes to on a theme's palette, in one mode: what native code is given. */
export function resolvePaint(style: Style, palette: Palette, paint: Paint, mode: Mode): RGBA {
  const p = paintIn(paint, mode)
  if ('token' in p) return resolvePaint(style, palette, colorToken(style, p.token), mode)
  return withAlpha('role' in p ? roleColor(palette, p.role, mode) : parse(p.color), p.alpha)
}

/** The shadow tokens a layer list can name in place of a layer. */
export function shadowToken(style: Style, token: string): Shadow {
  const all: Record<string, Shadow> = { ...style.depth, inset: style.material.inset, 'focus-ring': style.details['focus-ring'] }
  const shadow = all[token]
  if (!shadow) throw new Error(`${style.id}: no shadow ${token}`)
  return shadow
}

export function layersIn(shadow: Shadow, mode: Mode): Layers {
  return isPerMode<Layers>(shadow) ? shadow[mode] : shadow
}

/** A shadow's layers in one mode, other shadows it names spliced in. */
export function expandLayers(style: Style, shadow: Shadow, mode: Mode): Layer[] {
  return layersIn(shadow, mode).flatMap((item) => (typeof item === 'string' ? expandLayers(style, shadowToken(style, item), mode) : [item]))
}
