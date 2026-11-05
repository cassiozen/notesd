const DAY = 86_400_000

function sameDay(a: number, b: number): boolean {
  const x = new Date(a)
  const y = new Date(b)
  return x.getDate() === y.getDate() && x.getMonth() === y.getMonth() && x.getFullYear() === y.getFullYear()
}

// Made once: toLocale*String builds a new formatter on every call, which shows when a long list re-renders.
const clock = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' })
const weekday = new Intl.DateTimeFormat(undefined, { weekday: 'long' })
const shortDate = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'numeric', year: '2-digit' })

/** Apple Notes-style row time: a clock time today, then Yesterday, a weekday, a short date. */
export function listTime(ts: number, now = Date.now()): string {
  if (sameDay(ts, now)) return clock.format(ts)
  if (sameDay(ts, now - DAY)) return 'Yesterday'
  if (now - ts < 7 * DAY) return weekday.format(ts)
  return shortDate.format(ts)
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
