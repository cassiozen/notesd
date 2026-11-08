import { useEffect, useSyncExternalStore } from 'react'
import { ApiError } from '../api/client'
import { sharing } from '../api/spaces'
import type { Invite, Space, SpaceNews } from '../api/types'
import { genericMessage } from '../auth/errors'
import { notesSource, onNotesSourceChange } from './source'
import { onSpacesChange, reloadSpaces } from './spaces'

// Shared spaces (§7.9) for the screens: invitations, what others changed, and membership changes,
// which go to the server whichever source the notes come from.

type State = { invites: Invite[]; news: Readonly<Record<string, SpaceNews>> }

const EMPTY: State = { invites: [], news: {} }
let state = EMPTY
let loaded = false
let me: number | null = null
let shown: string | null = null
let fetches = 0
const listeners = new Set<() => void>()

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

function set(next: State) {
  state = next
  for (const l of listeners) l()
}

/** The signed-in account's id: its own edits are never news. */
export function setSharingAccount(id: number | null) {
  if (me === id) return
  me = id
  void reloadSharing()
}

/** The space on screen: shown, it's visited, so what's in it stops being news. */
export async function showSpace(space: string) {
  shown = space
  // A background tab (opened, or switched, out of sight) hasn't shown it: visited once the tab is seen (tabSeen).
  if (document.visibilityState === 'visible') await notesSource().sharing.visit(space, me).catch(() => {})
  await reloadSharing()
}

/** The tab seen again: the space on screen, shown while it was hidden, is seen now. */
export function tabSeen() {
  if (document.visibilityState === 'visible' && shown !== null && state.news[shown]) void showSpace(shown)
}

/** Invitations and news again; failures keep what was there. */
export async function reloadSharing(): Promise<void> {
  const n = ++fetches
  const source = notesSource()
  const [invites, news] = await Promise.all([source.sharing.invites().catch(() => null), source.sharing.news(me).catch(() => null)])
  if (n !== fetches) return
  loaded = true
  const byId = news ? Object.fromEntries(news.map((x) => [x.spaceId, x])) : state.news
  // Changes arriving in the space on screen are seen as they come. Visiting only then, not on every reload: a
  // visit is a write, and every write here reloads.
  const here = shown && byId[shown]
  if (here && document.visibilityState === 'visible') {
    void source.sharing.visit(here.spaceId, me).catch(() => {})
    const { [here.spaceId]: _seen, ...rest } = byId
    set({ invites: invites ?? state.invites, news: rest })
    return
  }
  set({ invites: invites ?? state.invites, news: byId })
}

export function useSharing(): State {
  const value = useSyncExternalStore(subscribe, () => state)
  useEffect(() => {
    if (!loaded) void reloadSharing()
  }, [])
  return value
}

export function forgetSharing() {
  state = EMPTY
  loaded = false
  shown = null
  fetches++
  for (const l of listeners) l()
}

// Another source has its own copy (the device's), and another account its own invitations.
onNotesSourceChange(forgetSharing)
// Online, what changed is worked out from the list's members: a new list can change it.
onSpacesChange(() => void reloadSharing())

/** "Ana", "Ana and Ben", "Ana, Ben and Cy", "Ana, Ben and 2 others": as the traits' summaries name people. */
export function names(list: readonly string[]): string {
  if (list.length <= 1) return list[0] ?? ''
  if (list.length > 3) return `${list[0]}, ${list[1]} and ${list.length - 2} others`
  return `${list.slice(0, -1).join(', ')} and ${list.at(-1)}`
}

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

type DateFormats = { offset: number; thisYear: Intl.DateTimeFormat; older: Intl.DateTimeFormat }
let dateFormats: DateFormats | undefined

/** Kept between calls, as the list's rows run this for each note; remade in a new time zone, as list-time's are. */
function currentDateFormats(): DateFormats {
  const offset = new Date().getTimezoneOffset()
  if (dateFormats?.offset !== offset) {
    dateFormats = {
      offset,
      thisYear: new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' }),
      older: new Intl.DateTimeFormat(undefined, { dateStyle: 'short' }),
    }
  }
  return dateFormats
}

/** How long ago another member's change was, in a few characters: "now", "5m", "2h", "3d", then a date (§7.9). */
export function ago(ts: number, now = Date.now()): string {
  const d = Math.max(0, now - ts)
  if (d < MINUTE) return 'now'
  if (d < HOUR) return `${Math.floor(d / MINUTE)}m`
  if (d < DAY) return `${Math.floor(d / HOUR)}h`
  if (d < 7 * DAY) return `${Math.floor(d / DAY)}d`
  const f = currentDateFormats()
  return (new Date(ts).getFullYear() === new Date(now).getFullYear() ? f.thisYear : f.older).format(ts)
}

let minutes = 0
let ticker: ReturnType<typeof setInterval> | undefined
const ticking = new Set<() => void>()

function subscribeMinutes(listener: () => void) {
  ticking.add(listener)
  ticker ??= setInterval(() => {
    minutes++
    for (const l of ticking) l()
  }, MINUTE)
  return () => {
    ticking.delete(listener)
    if (ticking.size > 0) return
    clearInterval(ticker)
    ticker = undefined
  }
}

const still = () => () => {}

/**
 * Re-renders each minute while [active], so an ago() label moves on with nothing else changing. One timer for every
 * label on screen; the value is only something to depend on.
 */
export function useMinutes(active = true): number {
  return useSyncExternalStore(active ? subscribeMinutes : still, () => minutes)
}

/** The switcher's line for a space (§7.7): "Ben edited 3 notes · 2h", or "30+ notes" when only some were read. */
export function newsLine(n: SpaceNews, now = Date.now()): string {
  const what = n.count === 1 && !n.atLeast ? 'a note' : `${n.count}${n.atLeast ? '+' : ''} notes`
  // Everyone who made them has left since: still worth the line.
  return `${names(n.usernames) || 'Someone'} edited ${what} · ${ago(n.latestAt, now)}`
}

/** Who last changed a note, when it's another member of [space] (§7.9); else nothing. */
export function editorName(space: Pick<Space, 'shared'> | undefined, updatedBy: number | null | undefined, self: number | undefined): string | null {
  if (updatedBy == null || updatedBy === self || !space?.shared) return null
  return space.shared.members.find((m) => m.id === updatedBy)?.username ?? null
}

/** After a change on the server: this device catches up, then the lists are read again. */
async function refresh() {
  await notesSource().sharing.settle().catch(() => {})
  await Promise.all([reloadSpaces(), reloadSharing()])
}

/**
 * [call], then the lists read again, failed or not: a refusal like 404 not_found means they're out of date, and
 * one that's a partial failure has changed some of it.
 */
async function change<T>(call: () => Promise<T>): Promise<T> {
  try {
    return await call()
  } finally {
    await refresh()
  }
}

/** The first invitation shares the space, moving its notes to its own file. */
export function invite(space: string, userId: number) {
  return change(() => sharing.invite(space, userId))
}

export function cancelInvite(space: string, inviteId: string) {
  return change(() => sharing.cancel(space, inviteId))
}

/** The owner removing a member, or a member leaving (its notes leave this device). */
export function removeMember(space: string, userId: number) {
  return change(() => sharing.remove(space, userId))
}

export function makeOwner(space: string, userId: number) {
  return change(() => sharing.transfer(space, userId))
}

/** Removes every other member and cancels every invitation: the space stays, private again. */
export function stopSharing(space: Space, self: number) {
  const others = space.shared?.members.filter((m) => m.id !== self) ?? []
  return change(async () => {
    // Every one tried, so the lists read after show all that went through.
    const results = await Promise.allSettled([
      ...(space.shared?.invites ?? []).map((i) => sharing.cancel(space.id, i.id)),
      ...others.map((m) => sharing.remove(space.id, m.id)),
    ])
    const failed = results.find((r) => r.status === 'rejected')
    if (failed) throw failed.reason
  })
}

/** Joins: resolves with the space, once its notes are here. */
export async function join(inviteId: string): Promise<string> {
  const space = await change(() => sharing.accept(inviteId))
  return space.id
}

export async function decline(inviteId: string) {
  try {
    await sharing.decline(inviteId)
  } finally {
    // Failed with 404 not_found, it was cancelled meanwhile: the list drops it.
    await reloadSharing()
  }
}

/** What went wrong, in words. */
export function sharingError(err: unknown): string {
  if (err instanceof ApiError) {
    switch (err.code) {
      case 'space_has_locked':
        if (err.count === 1) return 'Unlock its locked note to share it.'
        return err.count === undefined ? 'Unlock its locked notes to share it.' : `Unlock its ${err.count} locked notes to share it.`
      case 'space_default':
        return 'This space can’t be shared. Share another one.'
      case 'not_space_owner':
        return 'Only the space’s owner can do that.'
      case 'owner_cannot_leave':
        return 'As the owner, you can’t leave. Make someone else the owner, or stop sharing.'
      case 'already_member':
        return 'They’re already in this space.'
      case 'not_found':
        return 'That’s no longer there. It may have changed on another device.'
    }
  }
  return genericMessage(err)
}
