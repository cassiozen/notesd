import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ago, names, newsLine, useMinutes } from './sharing'

const now = new Date(2026, 10, 8, 12, 0).getTime()
const MIN = 60_000

describe('ago', () => {
  it('says how long ago in a few characters, then a date', () => {
    expect(ago(now - 10_000, now)).toBe('now')
    expect(ago(now - 5 * MIN, now)).toBe('5m')
    expect(ago(now - 2 * 60 * MIN, now)).toBe('2h')
    expect(ago(now - 3 * 24 * 60 * MIN, now)).toBe('3d')
    expect(ago(new Date(2026, 2, 4).getTime(), now)).toBe(new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' }).format(new Date(2026, 2, 4)))
    expect(ago(new Date(2025, 2, 4).getTime(), now)).toBe(new Intl.DateTimeFormat(undefined, { dateStyle: 'short' }).format(new Date(2025, 2, 4)))
    // A clock ahead of this one isn't the future.
    expect(ago(now + 5 * MIN, now)).toBe('now')
  })
})

describe('names and the switcher line', () => {
  it('names people as the traits do', () => {
    expect(names(['ana'])).toBe('ana')
    expect(names(['ana', 'ben'])).toBe('ana and ben')
    expect(names(['ana', 'ben', 'cy'])).toBe('ana, ben and cy')
    expect(names(['ana', 'ben', 'cy', 'dee'])).toBe('ana, ben and 2 others')
  })

  it('says who edited how many notes, how long ago', () => {
    expect(newsLine({ spaceId: 'w', count: 3, names: ['ben'], latestAt: now - 2 * 60 * MIN }, now)).toBe('ben edited 3 notes · 2h')
    expect(newsLine({ spaceId: 'w', count: 1, names: [], latestAt: now - 5 * MIN }, now)).toBe('Someone edited a note · 5m')
    // Only the first pages read: there were more.
    expect(newsLine({ spaceId: 'w', count: 200, atLeast: true, names: ['ben'], latestAt: now - 5 * MIN }, now)).toBe('ben edited 200+ notes · 5m')
  })
})

describe('useMinutes', () => {
  it('re-renders each minute while active, so an ago() label moves on', () => {
    vi.useFakeTimers()
    try {
      const start = Date.now()
      const { result, rerender } = renderHook(({ active }) => (useMinutes(active), ago(start)), { initialProps: { active: true } })
      expect(result.current).toBe('now')
      act(() => vi.advanceTimersByTime(2 * MIN))
      expect(result.current).toBe('2m')

      rerender({ active: false })
      act(() => vi.advanceTimersByTime(2 * MIN))
      expect(result.current).toBe('2m')
    } finally {
      vi.useRealTimers()
    }
  })
})
