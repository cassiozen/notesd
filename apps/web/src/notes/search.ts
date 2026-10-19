import { useCallback, useEffect, useRef, useState } from 'react'
import { notes as api, SEARCH_PAGE } from '../api/notes'
import type { NoteFilter, SearchResult, SearchScope } from '../api/types'

export type SearchState = {
  status: 'idle' | 'loading' | 'ready' | 'error'
  results: SearchResult[]
  hasMore: boolean
  /** Searching one space: the matches in every other space (§7.7). */
  otherSpaces: number
}

type Answer = {
  q: string
  scope: string
  key: string
  ok: boolean
  results: SearchResult[]
  hasMore: boolean
  otherSpaces: number
}

type Scope = { filter: NoteFilter; in: SearchScope }

const DEBOUNCE_MS = 150

/**
 * Search-as-you-type within filter, in one space or in every space but one.
 * Each new query (or a change to the notes, `version`) aborts the request
 * before it, so a slow stale response can't overwrite a newer one. Results
 * carry the key they answer; anything else is loading. An empty query (or
 * `enabled` false) searches nothing.
 */
export function useSearch(query: string, filter: NoteFilter, where: SearchScope, version: number, enabled = true) {
  const q = enabled ? query.trim() : ''
  const scope = JSON.stringify({ filter, in: where } satisfies Scope)
  const key = `${version}\u0000${scope}\u0000${q}`
  const [answer, setAnswer] = useState<Answer | null>(null)
  const more = useRef<AbortController | null>(null)

  useEffect(() => {
    more.current?.abort()
    more.current = null
    if (!q) return
    const controller = new AbortController()
    const timer = setTimeout(() => {
      const s = JSON.parse(scope) as Scope
      api.search(q, s.filter, s.in, 0, controller.signal).then(
        ({ results, otherSpaces = 0 }) =>
          setAnswer({ q, scope, key, ok: true, results, hasMore: results.length === SEARCH_PAGE, otherSpaces }),
        () => {
          if (!controller.signal.aborted) setAnswer({ q, scope, key, ok: false, results: [], hasMore: false, otherSpaces: 0 })
        },
      )
    }, DEBOUNCE_MS)
    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [q, scope, key])

  const loadMore = useCallback(() => {
    if (more.current || !answer) return
    const controller = new AbortController()
    more.current = controller
    const have = answer.results
    const s = JSON.parse(answer.scope) as Scope
    api
      .search(answer.q, s.filter, s.in, have.length, controller.signal)
      .then(
        ({ results }) => {
          if (controller.signal.aborted) return
          setAnswer((a) =>
            a && a.key === answer.key
              ? {
                  ...a,
                  results: [...a.results, ...results.filter((r) => !a.results.some((x) => x.id === r.id))],
                  hasMore: results.length === SEARCH_PAGE,
                }
              : a,
          )
        },
        () => undefined,
      )
      .finally(() => {
        if (more.current === controller) more.current = null
      })
  }, [answer])

  let state: SearchState
  if (!q) state = { status: 'idle', results: [], hasMore: false, otherSpaces: 0 }
  else if (answer?.key === key) {
    state = { status: answer.ok ? 'ready' : 'error', results: answer.results, hasMore: answer.hasMore, otherSpaces: answer.otherSpaces }
  } else if (answer?.q === q && answer.scope === scope && answer.ok) {
    // Same words, notes changed: keep showing the old results while refreshing.
    state = { status: 'ready', results: answer.results, hasMore: answer.hasMore, otherSpaces: answer.otherSpaces }
  } else state = { status: 'loading', results: [], hasMore: false, otherSpaces: 0 }

  return { ...state, loadMore }
}
