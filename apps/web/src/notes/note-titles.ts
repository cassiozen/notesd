import { useEffect, useState } from 'react'
import { useSearch } from './search'
import { notesSource } from './source'
import { useNotes } from './store'

const MAX_MATCHES = 8

/** A title as the server keeps one the agent reads: trimmed, each run of spaces one space. */
export const titleOf = (t: string) => t.trim().split(/\s+/).join(' ')

/**
 * Titles of notes in the space holding what's typed, less those already taken. Not one the server
 * would change: the agent looks a title up exactly, so it would never find that note.
 */
export function useTitleMatches(spaceId: string, typed: string, taken: readonly string[]): string[] {
  const { version } = useNotes()
  const { results } = useSearch(typed, null, { space: spaceId }, version)
  const q = typed.trim().toLowerCase()
  if (!q) return []
  const titles = results.map((r) => r.title).filter((t) => t && t === titleOf(t) && t.toLowerCase().includes(q) && !taken.includes(t))
  return [...new Set(titles)].slice(0, MAX_MATCHES)
}

/** For each title, whether the space has a note titled exactly that (as the agent looks them up); unknown while looking or offline. */
export function useFoundTitles(spaceId: string, titles: readonly string[]): ReadonlyMap<string, boolean> {
  const { version } = useNotes()
  const [found, setFound] = useState<ReadonlyMap<string, boolean>>(new Map())
  const key = JSON.stringify([spaceId, titles, version])
  useEffect(() => {
    const [space, list] = JSON.parse(key) as [string, string[]]
    const controller = new AbortController()
    void Promise.all(
      list.map(async (t): Promise<[string, boolean] | null> => {
        try {
          const { results } = await notesSource().search(t, null, { space }, 0, controller.signal)
          return [t, results.some((r) => r.title === t)]
        } catch {
          return null
        }
      }),
    ).then((pairs) => {
      if (!controller.signal.aborted) setFound(new Map(pairs.filter((p) => p !== null)))
    })
    return () => controller.abort()
  }, [key])
  return found
}
