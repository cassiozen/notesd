import { SPACE_THEME_BG } from './notes/space-icons'

export type ThemeChoice = 'system' | 'light' | 'dark'

const KEY = 'notes.theme'

// A display preference, not data: localStorage is fine and survives reloads.
export function readTheme(): ThemeChoice {
  try {
    const v = localStorage.getItem(KEY)
    return v === 'light' || v === 'dark' ? v : 'system'
  } catch {
    return 'system'
  }
}

/**
 * The window's title bar in Safari and installed web apps: the space theme's --bg
 * (data-space on <html>, see notes/space-theme.ts) in the theme picked, or each
 * of index.html's light and dark tags its own when the system's is followed.
 */
export function paintWindowColor() {
  const html = document.documentElement
  const bg = SPACE_THEME_BG[html.dataset.space ?? ''] ?? SPACE_THEME_BG['']
  const picked = html.dataset.theme
  for (const meta of document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]')) {
    const system = meta.media.includes('dark') ? 'dark' : 'light'
    meta.content = bg[picked === 'light' || picked === 'dark' ? picked : system]
  }
}

export function applyTheme(choice: ThemeChoice) {
  if (choice === 'system') document.documentElement.removeAttribute('data-theme')
  else document.documentElement.dataset.theme = choice
  paintWindowColor()
  try {
    if (choice === 'system') localStorage.removeItem(KEY)
    else localStorage.setItem(KEY, choice)
  } catch {
    // Storage blocked (private mode): the choice lasts for this page only.
  }
}
