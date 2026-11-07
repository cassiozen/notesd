import { renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { Space } from '../api/types'
import { SPACE_ICONS, SPACE_THEME_BG } from './space-icons'
import { themeKey, useSpaceTheme } from './space-theme'
import { DEFAULT_SPACE } from './spaces'

const space = (id: string, icon: string): Space => ({ id, name: id, icon }) as Space
const html = document.documentElement

afterEach(() => {
  localStorage.clear()
  delete html.dataset.space
  document.head.querySelectorAll('meta[name="theme-color"]').forEach((m) => m.remove())
})

describe('space themes', () => {
  // The CSS is generated from the same list (scripts/space_icons.py).
  it('has a window colour for every icon', () => {
    for (const { key } of SPACE_ICONS) expect(SPACE_THEME_BG[key]).toBeDefined()
  })

  it("gives an icon this app doesn't have the app icon's theme", () => {
    expect(themeKey('dragon')).toBe('dragon')
    expect(themeKey('notepad')).toBe('')
    expect(themeKey('constructor')).toBe('')
  })

  it('colours the app for the space shown, and the window with it', () => {
    const meta = document.createElement('meta')
    meta.name = 'theme-color'
    meta.media = '(prefers-color-scheme: dark)'
    document.head.append(meta)
    const spaces = [space(DEFAULT_SPACE, ''), space('b', 'dragon'), space('c', 'notepad')]
    const { rerender, unmount } = renderHook(({ id }) => useSpaceTheme(id, spaces), { initialProps: { id: 'b' } })
    expect(html.dataset.space).toBe('dragon')
    expect(meta.content).toBe(SPACE_THEME_BG.dragon?.dark)

    rerender({ id: 'c' })
    expect(html.dataset.space).toBeUndefined()
    expect(meta.content).toBe(SPACE_THEME_BG[''].dark)

    rerender({ id: 'b' })
    unmount()
    expect(html.dataset.space).toBeUndefined()
  })

  it("remembers the default space's theme for the next visit's first frame", () => {
    const { rerender } = renderHook(({ list }) => useSpaceTheme(DEFAULT_SPACE, list), {
      initialProps: { list: null as Space[] | null },
    })
    expect(html.dataset.space).toBeUndefined()

    rerender({ list: [space(DEFAULT_SPACE, 'comet')] })
    expect(localStorage.getItem('notes.space-theme')).toBe('comet')

    // Next visit, before the spaces load.
    delete html.dataset.space
    renderHook(() => useSpaceTheme(DEFAULT_SPACE, null))
    expect(html.dataset.space).toBe('comet')
  })
})
