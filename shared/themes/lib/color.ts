/** sRGB, each channel 0–1. */
export type RGBA = { r: number; g: number; b: number; a: number }

/** OKLCH to sRGB, clipped to the gamut as a browser shows it. */
function oklch(l: number, c: number, h: number): [number, number, number] {
  const a = c * Math.cos((h * Math.PI) / 180)
  const b = c * Math.sin((h * Math.PI) / 180)
  const l_ = (l + 0.3963377774 * a + 0.2158037573 * b) ** 3
  const m_ = (l - 0.1055613458 * a - 0.0638541728 * b) ** 3
  const s_ = (l - 0.0894841775 * a - 1.291485548 * b) ** 3
  const linear = [
    4.0767416621 * l_ - 3.3077115913 * m_ + 0.2309699292 * s_,
    -1.2684380046 * l_ + 2.6097574011 * m_ - 0.3413193965 * s_,
    -0.0041960863 * l_ - 0.7034186147 * m_ + 1.707614701 * s_,
  ]
  const gamma = (x: number) => (x <= 0.0031308 ? 12.92 * x : 1.055 * x ** (1 / 2.4) - 0.055)
  const [r, g, bl] = linear.map((x) => Math.min(1, Math.max(0, gamma(x))))
  return [r ?? 0, g ?? 0, bl ?? 0]
}

/** The forms palettes.schema.json allows: #rrggbb, #rrggbbaa, rgb(r g b / a), oklch(l c h / a). */
export function parse(color: string): RGBA {
  const hex = /^#([0-9a-f]{6})([0-9a-f]{2})?$/i.exec(color)
  if (hex) {
    const n = parseInt(hex[1] ?? '', 16)
    const a = hex[2] ? parseInt(hex[2], 16) / 255 : 1
    return { r: (n >> 16) / 255, g: ((n >> 8) & 255) / 255, b: (n & 255) / 255, a }
  }
  const fn = /^(rgb|oklch)\(([^/)]+)(?:\/\s*([\d.]+))?\)$/.exec(color)
  if (!fn) throw new Error(`Not a color: ${color}`)
  const [x = 0, y = 0, z = 0] = (fn[2] ?? '').trim().split(/\s+/).map(Number)
  const a = fn[3] ? Number(fn[3]) : 1
  const clamp = (v: number) => Math.min(1, Math.max(0, v))
  if (fn[1] === 'rgb') return { r: clamp(x / 255), g: clamp(y / 255), b: clamp(z / 255), a: clamp(a) }
  const [r, g, b] = oklch(x, y, z)
  return { r, g, b, a }
}

export function withAlpha(c: RGBA, alpha: number): RGBA {
  return { ...c, a: c.a * alpha }
}

const byte = (x: number) => Math.round(Math.min(1, Math.max(0, x)) * 255)

/** 0xAARRGGBB, as native code writes a color. */
export function argb(c: RGBA): string {
  return [c.a, c.r, c.g, c.b].map((x) => byte(x).toString(16).padStart(2, '0').toUpperCase()).join('')
}

/** #RRGGBB, alpha dropped. */
export function hex(c: RGBA): string {
  return `#${[c.r, c.g, c.b].map((x) => byte(x).toString(16).padStart(2, '0').toUpperCase()).join('')}`
}

/** rgb(r g b / a), as CSS writes a literal color with alpha. */
export function rgb(c: RGBA): string {
  return `rgb(${byte(c.r)} ${byte(c.g)} ${byte(c.b)} / ${round(c.a)})`
}

/** top laid over an opaque bottom. */
export function over(top: RGBA, bottom: RGBA): RGBA {
  const a = top.a
  return { r: top.r * a + bottom.r * (1 - a), g: top.g * a + bottom.g * (1 - a), b: top.b * a + bottom.b * (1 - a), a: 1 }
}

export function contrast(x: RGBA, y: RGBA): number {
  const lin = (v: number) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4)
  const lum = (c: RGBA) => 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b)
  const [hi, lo] = [lum(x), lum(y)].sort((p, q) => q - p)
  return ((hi ?? 0) + 0.05) / ((lo ?? 0) + 0.05)
}

/** Without float noise: 0.1 + 0.2 prints as 0.3. */
export function round(n: number): string {
  return String(Number(n.toFixed(4)))
}
