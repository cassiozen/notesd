import { useEffect, useSyncExternalStore } from 'react'
import { spaces as api } from '../api/spaces'
import type { Space } from '../api/types'

/** Every install has this space (architecture §7.7); it can be renamed, never deleted. */
export const DEFAULT_SPACE = 'default'

// One copy for the switcher, the note menu and Settings, fetched once per
// page load and refetched when the notes are (focus, Settings changes).
let current: Space[] | null = null
let loading: Promise<void> | null = null
// Each fetch's number: only the latest started may set the list, so a slow
// one from before a create can't put back the list without it.
let fetches = 0
const listeners = new Set<() => void>()

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

function set(next: Space[]) {
  current = next
  listeners.forEach((l) => l())
}

/** Fetches the list again; failures keep what was there. */
export function reloadSpaces(): Promise<void> {
  const n = ++fetches
  loading = api.list().then(
    ({ spaces }) => {
      if (n === fetches) set(spaces)
    },
    () => {
      if (n === fetches) loading = null
    },
  )
  return loading
}

/** The spaces, default first, or null until they've loaded. */
export function useSpaces(): Space[] | null {
  const value = useSyncExternalStore(subscribe, () => current)
  useEffect(() => {
    if (!loading) void reloadSpaces()
  }, [])
  return value
}

/** Calls listener with each new list. Returns the unsubscribe function. */
export function onSpacesChange(listener: (list: Space[]) => void): () => void {
  const l = () => {
    if (current) listener(current)
  }
  listeners.add(l)
  return () => listeners.delete(l)
}

/** The space's name, or "Notes" while the list loads. */
export function spaceName(list: Space[] | null, id: string): string {
  return list?.find((s) => s.id === id)?.name ?? 'Notes'
}

/** Creates a space (a new id) or renames one, keeping the list in step. Without icon, it stays as it is. */
export async function putSpace(id: string, name: string, icon?: string): Promise<Space> {
  const stored = await api.put(id, name, icon)
  await reloadSpaces()
  return stored
}

export async function deleteSpace(id: string): Promise<void> {
  await api.remove(id)
  await reloadSpaces()
}

/** For tests: forget what was loaded. */
export function resetSpaces() {
  current = null
  loading = null
  fetches = 0
}
