import { flushSync } from 'react-dom'
import { DEFAULT_THEME, themeAttributes, themeById, themeFor, type Theme, type ThemeId } from './themes/themes'

export type ModeChoice = 'system' | 'light' | 'dark'

// Display preferences, not data: localStorage is fine and survives reloads. public/boot.js reads
// both before the first paint, so the keys and formats are shared with it.
const MODE_KEY = 'notes.mode'
/** The theme's attributes and title bar colors as JSON, so boot.js can set them without knowing any theme. */
const THEME_KEY = 'notes.theme'
const DARK = '(prefers-color-scheme: dark)'

let theme: Theme = themeFor(DEFAULT_THEME)
let mode: ModeChoice = 'system'
let system: MediaQueryList | undefined
let switches = 0

/** Before themes, notes.theme held the mode itself. */
function legacyMode(): ModeChoice | null {
  const v = localStorage.getItem(THEME_KEY)
  return v === 'light' || v === 'dark' ? v : null
}

export function readMode(): ModeChoice {
  try {
    const v = localStorage.getItem(MODE_KEY) ?? legacyMode()
    return v === 'light' || v === 'dark' ? v : 'system'
  } catch {
    return 'system'
  }
}

export function readTheme(): ThemeId {
  try {
    const attrs = JSON.parse(localStorage.getItem(THEME_KEY) ?? 'null') as Record<string, string> | null
    const picked = attrs && themeById(attrs['data-theme'] ?? '')
    return (picked?.id as ThemeId | undefined) ?? DEFAULT_THEME
  } catch {
    return DEFAULT_THEME
  }
}

function store(key: string, value: string | null) {
  try {
    if (value === null) localStorage.removeItem(key)
    else localStorage.setItem(key, value)
  } catch {
    // Storage blocked (private mode): the choice lasts for this page only.
  }
}

function resolvedMode(): 'light' | 'dark' {
  return mode === 'system' ? (window.matchMedia(DARK).matches ? 'dark' : 'light') : mode
}

function paint() {
  const root = document.documentElement
  for (const [name, value] of Object.entries(themeAttributes(theme))) root.setAttribute(name, value)
  root.dataset.mode = resolvedMode()
  // index.html's two metas pick light or dark by media query; a picked mode must win over the system's.
  for (const meta of document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]')) {
    const scheme = mode === 'system' ? (meta.media.includes('dark') ? 'dark' : 'light') : mode
    meta.content = theme.bar[scheme]
  }
}

export function applyMode(choice: ModeChoice) {
  mode = choice
  system?.removeEventListener('change', paint)
  system = undefined
  if (choice === 'system') {
    system = window.matchMedia(DARK)
    system.addEventListener('change', paint)
  }
  paint()
  try {
    if (legacyMode()) localStorage.removeItem(THEME_KEY)
  } catch {
    // As in store().
  }
  store(MODE_KEY, choice === 'system' ? null : choice)
}

export function applyTheme(id: ThemeId) {
  theme = themeFor(id)
  paint()
  // bar too, so boot.js colors the title bar before the bundle runs.
  store(THEME_KEY, theme.id === DEFAULT_THEME ? null : JSON.stringify({ ...themeAttributes(theme), bar: theme.bar }))
}

/**
 * Switches with a circle that grows from (x, y) and uncovers the new theme. update runs inside
 * the transition so React's part of the change (the picked radio) lands in the same frame.
 */
export function switchTheme(id: ThemeId, update: () => void, origin?: { x: number; y: number }) {
  const go = () => {
    applyTheme(id)
    update()
  }
  if (!origin || !document.startViewTransition || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    go()
    return
  }
  const root = document.documentElement
  root.style.setProperty('--wipe-x', `${origin.x}px`)
  root.style.setProperty('--wipe-y', `${origin.y}px`)
  // Picked again mid-wipe, the older transition mustn't end the newer one's.
  const token = String(++switches)
  root.dataset.switching = token
  const transition = document.startViewTransition(() => flushSync(go))
  void transition.finished.finally(() => {
    if (root.dataset.switching === token) delete root.dataset.switching
  })
}
