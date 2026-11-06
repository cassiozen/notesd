import { DEFAULT_THEME as DEFAULT, THEMES as ALL } from './generated/themes'

/** A theme is a palette, two fonts and one style; the definitions are in shared/themes. */
export type Theme = (typeof ALL)[number]
export type ThemeId = Theme['id']

export const THEMES: readonly Theme[] = ALL
export const DEFAULT_THEME: ThemeId = DEFAULT

/** undefined for an id no theme has (a stored one from a build that had it). */
export function themeById(id: string): Theme | undefined {
  return THEMES.find((t) => t.id === id)
}

export function themeFor(id: ThemeId): Theme {
  return themeById(id) ?? ALL[0]
}

/** The attributes that put a theme on an element: <html>, or a preview anywhere in the page. */
export function themeAttributes(theme: Theme) {
  return {
    'data-theme': theme.id,
    'data-palette': theme.palette,
    'data-style': theme.style,
    'data-title-font': theme.fonts.title,
    'data-body-font': theme.fonts.body,
    'data-sidebar': theme.sidebar,
  }
}
