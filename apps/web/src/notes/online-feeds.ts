import { notes } from '../api/notes'
import type { Space, SpaceNews } from '../api/types'

// Nothing kept here: what others changed in a shared space (§7.9) is read from its list, newest
// first, back to when this browser last showed it.

/** Pages read per space at most: past that, the count says "at least". */
const MAX_PAGES = 4

const VISITED_KEY = 'notes.visited'

function visits(): Record<string, number> {
  try {
    return (JSON.parse(localStorage.getItem(VISITED_KEY) ?? '{}') as Record<string, number> | null) ?? {}
  } catch {
    return {}
  }
}

/** When this browser last showed [space] to account [me]; 0 if never, so everything others did is new. */
function visitedAt(me: number, space: string): number {
  return visits()[`${me}:${space}`] ?? 0
}

export function markVisited(me: number, space: string, at = Date.now()) {
  try {
    localStorage.setItem(VISITED_KEY, JSON.stringify({ ...visits(), [`${me}:${space}`]: at }))
  } catch {
    // Not remembered: the same changes show as new again next time.
  }
}

/** The engine's news() (§7.9), from each shared space's list: notes others changed since it was last shown. */
export async function feedNews(spaces: readonly Space[], me: number): Promise<SpaceNews[]> {
  const all = await Promise.all(
    spaces
      .filter((s) => s.shared)
      .map(async (space): Promise<SpaceNews | null> => {
        const since = visitedAt(me, space.id)
        const changed: { by: number; at: number }[] = []
        let cursor: string | undefined
        let atLeast = false
        for (let page = 1; ; page++) {
          // Sequential on purpose: each page starts where the last ended, and the first old one ends it.
          // oxlint-disable-next-line no-await-in-loop
          const list = await notes.list(null, space.id, cursor).catch(() => null)
          if (!list) return null
          const recent = list.notes.filter((n) => n.updatedAt > since)
          for (const n of recent) if (n.updatedBy !== undefined && n.updatedBy !== me) changed.push({ by: n.updatedBy, at: n.updatedAt })
          if (recent.length < list.notes.length || !list.hasMore) break
          if (page === MAX_PAGES) {
            atLeast = true
            break
          }
          cursor = list.nextCursor
        }
        const [latest] = changed
        if (!latest) return null
        const names = new Map(space.shared?.members.map((m) => [m.id, m.username]))
        const usernames = [...new Set(changed.map((c) => names.get(c.by)).filter((n) => n !== undefined))]
        const news: SpaceNews = { spaceId: space.id, count: changed.length, usernames, latestAt: latest.at }
        if (atLeast) news.atLeast = true
        return news
      }),
  )
  return all.filter((n) => n !== null)
}
