import { useEffect, useRef } from 'react'
import type { Space } from '../api/types'
import { paintWindowColor } from '../theme'
import { DEFAULT_SPACE } from './default-space'
import { SPACE_ICONS } from './space-icons'

// The default space's theme, for public/boot.js: the app always opens on that space.
const KEY = 'notes.space-theme'
const FADE_MS = 300

const known = new Set(SPACE_ICONS.map((i) => i.key))

/**
 * The theme for a space's icon (styles/space-themes.css): its own, or the app
 * icon's ('') for a key this app doesn't have.
 */
export function themeKey(icon: string): string {
  return known.has(icon) ? icon : ''
}

function readDefault(): string {
  try {
    return themeKey(localStorage.getItem(KEY) ?? '')
  } catch {
    return ''
  }
}

function saveDefault(key: string) {
  try {
    if (key) localStorage.setItem(KEY, key)
    else localStorage.removeItem(KEY)
  } catch {
    // The first frame shows the app icon's colours until the spaces load.
  }
}

let fade: ReturnType<typeof setTimeout> | undefined

function apply(key: string, animate: boolean) {
  const html = document.documentElement
  if ((html.dataset.space ?? '') === key) return
  if (animate) {
    // base.css eases colours while this is set; only briefly, as it overrides elements' own transitions.
    html.dataset.spaceFade = ''
    clearTimeout(fade)
    fade = setTimeout(() => delete html.dataset.spaceFade, FADE_MS)
  }
  if (key) html.dataset.space = key
  else delete html.dataset.space
  paintWindowColor()
}

/** Colours the whole app for the space shown; the app icon's again once unmounted (signed out). */
export function useSpaceTheme(space: string, spaces: Space[] | null) {
  // Before the list loads only the default space's is known, from the last visit.
  const key = spaces
    ? themeKey(spaces.find((s) => s.id === space)?.icon ?? '')
    : space === DEFAULT_SPACE
      ? readDefault()
      : null
  const defaultKey = spaces && themeKey(spaces.find((s) => s.id === DEFAULT_SPACE)?.icon ?? '')
  const shown = useRef(false)

  useEffect(() => {
    if (key === null) return
    apply(key, shown.current)
    shown.current = true
  }, [key])

  useEffect(() => {
    if (defaultKey !== null) saveDefault(defaultKey)
  }, [defaultKey])

  useEffect(() => () => apply('', false), [])
}
