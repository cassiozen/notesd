import { useEffect, useState } from 'react'

const DAY = 86_400_000

function sameDay(a: number, b: number): boolean {
  const x = new Date(a)
  const y = new Date(b)
  return x.getDate() === y.getDate() && x.getMonth() === y.getMonth() && x.getFullYear() === y.getFullYear()
}

type Formats = { offset: number; clock: Intl.DateTimeFormat; weekday: Intl.DateTimeFormat; shortDate: Intl.DateTimeFormat }
let formats: Formats | undefined

/**
 * Kept between calls: toLocale*String builds a new formatter every time, which shows when a long list re-renders.
 * A formatter keeps the time zone it was made in, so they're remade when the offset changes (the computer moved
 * zones, or DST began), or a long-lived tab would show times in the old zone.
 */
function currentFormats(): Formats {
  const offset = new Date().getTimezoneOffset()
  if (formats?.offset !== offset) {
    formats = {
      offset,
      clock: new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }),
      weekday: new Intl.DateTimeFormat(undefined, { weekday: 'long' }),
      shortDate: new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'numeric', year: '2-digit' }),
    }
  }
  return formats
}

/** Apple Notes-style row time: a clock time today, then Yesterday, a weekday, a short date. */
export function listTime(ts: number, now = Date.now()): string {
  const f = currentFormats()
  if (sameDay(ts, now)) return f.clock.format(ts)
  if (sameDay(ts, now - DAY)) return 'Yesterday'
  if (now - ts < 7 * DAY) return f.weekday.format(ts)
  return f.shortDate.format(ts)
}

/** The local start of the day after [now]'s: when Today, Yesterday and the rest move on. */
export function nextMidnight(now = Date.now()): number {
  const d = new Date(now)
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1).getTime()
}

/** The coming local midnight, moving on as each one passes: for labels relative to today. */
export function useMidnight(): number {
  const [midnight, setMidnight] = useState(() => nextMidnight())
  useEffect(() => {
    // From the later of now and this midnight, so it always moves on, even if the timer fires early or (after
    // sleep) hours late.
    const t = setTimeout(() => setMidnight(nextMidnight(Math.max(Date.now(), midnight))), midnight - Date.now())
    return () => clearTimeout(t)
  }, [midnight])
  return midnight
}

export type Group<T> = { label: string; items: T[] }

const buckets: [string, (ts: number, now: number) => boolean][] = [
  ['Today', (ts, now) => sameDay(ts, now)],
  ['Previous 7 days', (ts, now) => now - ts < 7 * DAY],
  ['Previous 30 days', (ts, now) => now - ts < 30 * DAY],
  ['Earlier', () => true],
]

/** Splits notes (newest first) into the list's date sections, skipping empty ones. */
export function groupByDate<T extends { updatedAt: number }>(items: T[], now = Date.now()): Group<T>[] {
  const groups: Group<T>[] = []
  for (const item of items) {
    const label = buckets.find(([, test]) => test(item.updatedAt, now))?.[0] ?? 'Earlier'
    const last = groups.at(-1)
    if (last?.label === label) last.items.push(item)
    else groups.push({ label, items: [item] })
  }
  return groups
}
