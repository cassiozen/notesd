import { useSyncExternalStore } from 'react'
import { flushSync } from 'react-dom'
import { isSettingsSection, type SettingsSection } from './settings/sections'
import { PHONE } from './ui/media'

/** settings without a section: the list of categories on a phone, Server on a wider screen. */
export type Route =
  | { name: 'notes' }
  | { name: 'note'; id: string }
  | { name: 'settings'; section?: SettingsSection }
  | { name: 'tags' }
  | { name: 'activity' }
  | { name: 'agent'; spaceId: string }

const listeners = new Set<() => void>()

function subscribe(listener: () => void) {
  listeners.add(listener)
  window.addEventListener('popstate', listener)
  return () => {
    listeners.delete(listener)
    window.removeEventListener('popstate', listener)
  }
}

/**
 * [animate]: false for moves the user didn't make, which shouldn't play a transition.
 * [state]: kept with the history entry, as `window.history.state`.
 */
export function navigate(
  path: string,
  { replace = false, animate = true, state = null }: { replace?: boolean; animate?: boolean; state?: unknown } = {},
) {
  if (path === window.location.pathname) return
  const go = () => {
    if (replace) window.history.replaceState(state, '', path)
    else window.history.pushState(state, '', path)
    for (const listener of listeners) listener()
  }
  const nav = replace || !animate ? null : transitionFor(window.location.pathname, path)
  if (!nav || !document.startViewTransition || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    go()
    return
  }
  const root = document.documentElement
  root.dataset.nav = nav
  // flushSync: the transition snapshots the DOM as soon as the callback returns.
  const transition = document.startViewTransition(() => flushSync(go))
  void transition.finished.finally(() => {
    if (root.dataset.nav === nav) delete root.dataset.nav
  })
}

/**
 * Settings, Activity and the Agent page open over a page, which closing them returns to: kept with the
 * history entry, so each of several open in turn returns to its own, and a reload still knows.
 */
type Over = { returnTo?: string; returnState?: unknown }

const over = () => window.history.state as Over | null

/** Opens [path] over the current page. [state]: the page's own, as `window.history.state`. */
export function openOver(path: string, state: Record<string, unknown> = {}) {
  navigate(path, { state: { ...state, returnTo: window.location.pathname, returnState: window.history.state } })
}

/** Moves within a page opened over another, keeping where it returns to. */
export function moveOver(
  path: string,
  { replace = false, state = {} }: { replace?: boolean; state?: Record<string, unknown> } = {},
) {
  navigate(path, { replace, state: { ...state, returnTo: over()?.returnTo, returnState: over()?.returnState } })
}

/** Closes a page opened over another: back to it, or to [fallback] when opened straight from its address. */
export function closeOver(fallback: string, { replace = false }: { replace?: boolean } = {}) {
  const s = over()
  navigate(s?.returnTo ?? fallback, { replace, state: s?.returnState ?? null })
}

/** Phones: the tags, the list and a note stack like screens; deeper pushes, shallower pops. */
const depth = (route: Route) => ({ tags: 0, notes: 1, note: 2, settings: null, activity: null, agent: null })[route.name]

/**
 * How a user-initiated move animates (see base.css), or null for none.
 * Never side by side: a view transition takes the whole page's pointer events for its length, so the list
 * stops responding to hover and clicks right after each one. The note pane fades in by itself instead.
 */
export function transitionFor(from: string, to: string): 'forward' | 'back' | null {
  if (!window.matchMedia(PHONE).matches) return null
  const a = parseRoute(from)
  const b = parseRoute(to)
  const da = depth(a)
  const db = depth(b)
  if (da === null || db === null || da === db) return null
  return db > da ? 'forward' : 'back'
}

/** Where a space's agent is set up. */
export const agentPath = (spaceId: string) => `/spaces/${encodeURIComponent(spaceId)}/agent`

export function parseRoute(pathname: string): Route {
  if (pathname === '/settings') return { name: 'settings' }
  const settings = /^\/settings\/([^/]+)$/.exec(pathname)?.[1]
  // An unknown section is Settings' first page, not the notes.
  if (settings !== undefined) {
    return isSettingsSection(settings) ? { name: 'settings', section: settings } : { name: 'settings' }
  }
  if (pathname === '/tags') return { name: 'tags' }
  if (pathname === '/activity') return { name: 'activity' }
  const agent = /^\/spaces\/([A-Za-z0-9_-]{1,64})\/agent$/.exec(pathname)?.[1]
  if (agent) return { name: 'agent', spaceId: agent }
  const match = /^\/notes\/([A-Za-z0-9_-]{1,64})$/.exec(pathname)
  if (match?.[1]) return { name: 'note', id: match[1] }
  return { name: 'notes' }
}

export function usePathname(): string {
  return useSyncExternalStore(subscribe, () => window.location.pathname)
}
