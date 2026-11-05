import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { groupByDate, listTime, nextMidnight, useMidnight } from './list-time'

const now = new Date(2026, 9, 13, 15, 0).getTime()
const at = (daysAgo: number, hour = 9) => new Date(2026, 9, 13 - daysAgo, hour, 30).getTime()

describe('groupByDate', () => {
  it('sections notes by age, newest first, without empty sections', () => {
    const items = [0, 1, 6, 8, 29, 31, 400].map((d) => ({ updatedAt: at(d) }))
    expect(groupByDate(items, now).map((g) => [g.label, g.items.length])).toEqual([
      ['Today', 1],
      ['Previous 7 days', 2],
      ['Previous 30 days', 2],
      ['Earlier', 2],
    ])
  })

  it('counts calendar days for Today, not 24 hours', () => {
    const lateYesterday = new Date(2026, 9, 12, 23, 50).getTime()
    expect(groupByDate([{ updatedAt: lateYesterday }], now)[0]?.label).toBe('Previous 7 days')
  })
})

describe('listTime', () => {
  it('shows Yesterday, then weekdays, then dates', () => {
    expect(listTime(at(1), now)).toBe('Yesterday')
    expect(listTime(at(3), now)).toBe(new Date(at(3)).toLocaleDateString(undefined, { weekday: 'long' }))
    expect(listTime(at(10), now)).toBe(
      new Date(at(10)).toLocaleDateString(undefined, { day: 'numeric', month: 'numeric', year: '2-digit' }),
    )
  })
})

describe('listTime across a time zone change', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('shows the clock time in the zone the computer is in now', () => {
    vi.stubEnv('TZ', 'UTC')
    const ts = Date.UTC(2026, 9, 13, 12, 0)
    const inUtc = listTime(ts, ts)
    vi.stubEnv('TZ', 'Asia/Tokyo')
    expect(listTime(ts, ts)).toBe(new Date(ts).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' }))
    expect(listTime(ts, ts)).not.toBe(inUtc)
  })
})

describe('useMidnight', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('is the coming local midnight', () => {
    expect(nextMidnight(new Date(2026, 9, 13, 15, 0).getTime())).toBe(new Date(2026, 9, 14).getTime())
    expect(nextMidnight(new Date(2026, 11, 31, 23, 59).getTime())).toBe(new Date(2027, 0, 1).getTime())
  })

  it('moves on as each midnight passes', () => {
    vi.useFakeTimers({ now: new Date(2026, 9, 13, 23, 59, 58) })
    const { result } = renderHook(() => useMidnight())
    expect(result.current).toBe(new Date(2026, 9, 14).getTime())

    act(() => vi.advanceTimersByTime(3000))
    expect(result.current).toBe(new Date(2026, 9, 15).getTime())

    act(() => vi.advanceTimersByTime(24 * 60 * 60_000))
    expect(result.current).toBe(new Date(2026, 9, 16).getTime())
  })
})
