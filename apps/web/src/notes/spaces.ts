import { useEffect, useSyncExternalStore } from 'react'
import type { Space, SpaceTrait } from '../api/types'
import { notesSource, onNotesSourceChange } from './source'

export { DEFAULT_SPACE } from './default-space'

// One copy for the switcher, the note menu and Settings, fetched once per
// source and refetched when the notes are (focus, Settings changes).
let fetched: Space[] | null = null
// fetched as shown: with what this app turned on or off and the list doesn't say yet.
let current: Space[] | null = null
// Published or not, by space id, as this app just asked: shown at once, until the list agrees or the call fails.
// Each with the call that asked: a call that fails clears only its own, not a newer one's.
const expected = new Map<string, { on: boolean; call: number }>()
let calls = 0
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

function set(next: Space[] | null) {
  fetched = next
  for (const [id, { on }] of expected) {
    const space = next?.find((s) => s.id === id)
    if (space && space.traits.some((t) => t.key === 'published') === on) expected.delete(id)
  }
  current = next && expected.size > 0 ? next.map(shown) : next
  listeners.forEach((l) => l())
}

/** Until the server says, a published space has no address: only that it's public. */
const PUBLISHING: SpaceTrait = {
  key: 'published',
  label: 'Published',
  summary: 'Publishing…',
  exposure: 'Public',
  audience: ['public'],
}

function shown(space: Space): Space {
  const on = expected.get(space.id)?.on
  if (on === undefined) return space
  const published = space.traits.find((t) => t.key === 'published')
  if (on && !published) {
    // After sharing, before the rest: the server's order (spaces.TraitOrder).
    const at = space.traits.findIndex((t) => t.key !== 'shared')
    const traits = [...space.traits]
    traits.splice(at < 0 ? traits.length : at, 0, PUBLISHING)
    return { ...space, traits }
  }
  if (!on && published) {
    return { ...space, traits: space.traits.filter((t) => t !== published), leaving: [published] }
  }
  return space
}

/**
 * Shows a space published, or not, at once, while the call that does it is on its way: its glyph and the lock
 * rules (a published space refuses locked notes) follow. Turned off, the lock rules keep counting it until the
 * server confirms.
 */
export function expectPublished(id: string, on: boolean): number {
  expected.set(id, { on, call: ++calls })
  set(fetched)
  return calls
}

/** The call failed: the space shows as the list has it, unless a newer call expects it otherwise. */
export function unexpectPublished(id: string, call: number) {
  if (expected.get(id)?.call !== call) return
  expected.delete(id)
  set(fetched)
}

/** After a change on the server: a device keeping notes syncs first, since its list is its own copy. */
export async function catchUpSpaces(): Promise<void> {
  await notesSource()
    .sharing.settle()
    .catch(() => {})
  await reloadSpaces()
}

/** Fetches the list again; failures keep what was there. */
export function reloadSpaces(): Promise<void> {
  const n = ++fetches
  loading = notesSource().spaces.list().then(
    (spaces) => {
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

/** The spaces, fetched first if they haven't loaded; null if that fails. */
export async function loadedSpaces(): Promise<Space[] | null> {
  if (current) return current
  const waiting = loading ?? reloadSpaces()
  await waiting
  // Overtaken by a newer fetch before it answered: that one's list.
  return !current && loading && loading !== waiting ? loadedSpaces() : current
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
  const stored = await notesSource().spaces.put(id, name, icon)
  await reloadSpaces()
  return stored
}

export async function deleteSpace(id: string): Promise<void> {
  await notesSource().spaces.remove(id)
  await reloadSpaces()
}

/** Drops the list, and any fetch still on its way, so the next useSpaces fetches anew. */
export function forgetSpaces() {
  fetched = null
  current = null
  expected.clear()
  loading = null
  fetches++
  listeners.forEach((l) => l())
}

// Another source can have other spaces: the device's copy before its first sync.
onNotesSourceChange(forgetSpaces)
