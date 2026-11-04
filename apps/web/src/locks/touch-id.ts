// Touch ID unlock in the macOS app, as Face ID on iOS: the key in the Keychain (apps/desktop), kept against the
// password entry it came from; once that changes, it's turned off, so a stale key never encrypts new notes.
import { useEffect, useSyncExternalStore } from 'react'
import { inDesktopApp } from '../desktop'
import { lockSession } from './session'

/** What asking for Touch ID came to, as the app answers. */
export type TouchIdAnswer = { outcome: 'ok'; key?: number[] } | { outcome: 'cancelled' | 'invalidated' | 'failed' }

/** The app's side; tests put in their own. */
export type TouchIdBridge = {
  available(): Promise<boolean>
  seal(user: string, key: number[]): Promise<TouchIdAnswer>
  open(user: string): Promise<TouchIdAnswer>
  forget(user: string): Promise<void>
}

/**
 * - done
 * - cancelled: dismissed; nothing changes
 * - stale: the lock password changed or was reset, or the fingers did: turned off, use the password
 * - failed: locked out, interrupted, or the keyring couldn't be read; it stays as it was
 */
export type TouchIdOutcome = 'done' | 'cancelled' | 'stale' | 'failed'

type Tauri = { invoke<T>(cmd: string, args?: Record<string, unknown>): Promise<T> }

const app: TouchIdBridge = {
  available: () => tauri().invoke<boolean>('touch_id_available'),
  seal: (user, key) => tauri().invoke<TouchIdAnswer>('touch_id_seal', { user, key }),
  open: (user) => tauri().invoke<TouchIdAnswer>('touch_id_open', { user }),
  forget: (user) => tauri().invoke<void>('touch_id_forget', { user }),
}

// What the app puts in every page it opens; on the server's pages it reaches only the commands they're granted.
const INTERNALS = '__TAURI_INTERNALS__'

function tauri(): Tauri {
  return (window as unknown as Record<string, Tauri>)[INTERNALS] as Tauri
}

let bridge: TouchIdBridge | null = null

/** Tests only: stands in for the app. Null puts the app's back. */
export function setTouchIdBridge(next: TouchIdBridge | null) {
  bridge = next
  available = null
  checking = null
  offer = null
  notify()
}

function current(): TouchIdBridge | null {
  if (bridge) return bridge
  return inDesktopApp() && INTERNALS in window ? app : null
}

const listeners = new Set<() => void>()
/** Known once asked: a Mac without Touch ID, or an unsigned build, says no. */
let available: boolean | null = null
let checking: Promise<void> | null = null

function notify() {
  for (const l of listeners) l()
}

function checkAvailable(): Promise<void> {
  if (checking) return checking
  const b = current()
  if (!b) {
    available = false
    return Promise.resolve()
  }
  checking = b.available().then(
    (yes) => {
      available = yes
      notify()
    },
    () => {
      available = false
      notify()
    },
  )
  return checking
}

// The password entry the kept key goes with, per account. Not a secret: the server has it too.
const boundKey = (user: string) => `notes.touchid.${user}`
// Declined, per account: "Not now", Cancel at Touch ID, or turned off in Settings.
const declinedKey = (user: string) => `notes.touchid-declined.${user}`

function read(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function write(key: string, value: string | null) {
  try {
    if (value === null) localStorage.removeItem(key)
    else localStorage.setItem(key, value)
  } catch {
    // Not kept: Touch ID stays off, or is offered again.
  }
}

const bound = (user: string) => read(boundKey(user))
const declined = (user: string) => read(declinedKey(user)) !== null

function setBound(user: string, entry: string | null) {
  write(boundKey(user), entry)
  notify()
}

const setDeclined = (user: string, yes: boolean) => write(declinedKey(user), yes ? '1' : null)

function subscribe(onChange: () => void) {
  listeners.add(onChange)
  return () => listeners.delete(onChange)
}

/** Whether Touch ID can be offered, and whether it's on for [user] here. */
export function useTouchId(user: string | null): { available: boolean; enabled: boolean } {
  useEffect(() => void checkAvailable(), [])
  const can = useSyncExternalStore(subscribe, () => available === true)
  const on = useSyncExternalStore(subscribe, () => user !== null && bound(user) !== null)
  return { available: can, enabled: can && on }
}

/**
 * Turns Touch ID unlock on for [user]: the lock password unlocks notes and gives a copy of the key for the
 * Keychain. Throws LockedError for a wrong password.
 */
export async function turnOnTouchId(user: string, password: string): Promise<Exclude<TouchIdOutcome, 'stale'>> {
  const b = current()
  if (!b) return 'failed'
  const { raw, entry } = await lockSession.unlockForDevice(password)
  let answer: TouchIdAnswer
  try {
    answer = await b.seal(user, [...raw])
  } finally {
    raw.fill(0)
  }
  if (answer.outcome === 'cancelled') return 'cancelled'
  if (answer.outcome !== 'ok') return 'failed'
  // The keyring may have moved on while Touch ID was asked for.
  const now = await lockSession.load(true).then(
    () => lockSession.passwordEntry(),
    () => null,
  )
  if (now !== entry) {
    await b.forget(user).catch(() => undefined)
    return 'failed'
  }
  setBound(user, entry)
  // Storage refused it: Touch ID would look off while a key sat in the Keychain. Give the key back.
  if (bound(user) !== entry) {
    await b.forget(user).catch(() => undefined)
    return 'failed'
  }
  setDeclined(user, false)
  return 'done'
}

/** The offer after the lock password, until it's answered: whose, and the password that turns Touch ID on. */
let offer: { user: string; password: string } | null = null

/**
 * The lock password was just typed for [user]: unless Touch ID is on, was declined or isn't here, offer it, so
 * it's the last time the password is needed on this Mac. useTouchIdOffer shows the question.
 */
export async function offerTouchId(user: string, password: string) {
  await checkAvailable()
  if (offer || available !== true || bound(user) !== null || declined(user)) return
  offer = { user, password }
  notify()
}

/** Whose offer is waiting for an answer. */
export function useTouchIdOffer(): string | null {
  return useSyncExternalStore(subscribe, () => offer?.user ?? null)
}

/** No is remembered, as is Cancel at Touch ID; a failure (a lockout, the keyring moved on) offers again next time. */
export async function answerTouchIdOffer(yes: boolean) {
  const asked = offer
  if (!asked) return
  let outcome: TouchIdOutcome = 'cancelled'
  try {
    if (yes) outcome = await turnOnTouchId(asked.user, asked.password)
  } catch {
    outcome = 'failed'
  }
  if (offer !== asked) return
  offer = null
  if (outcome === 'cancelled') setDeclined(asked.user, true)
  notify()
}

export async function unlockWithTouchId(user: string): Promise<TouchIdOutcome> {
  const b = current()
  const kept = bound(user)
  if (!b || !kept) return stale(user)
  try {
    await lockSession.load(true)
  } catch {
    // An unread keyring says nothing about the kept key: it stays on, and the password works meanwhile.
    return 'failed'
  }
  if (lockSession.passwordEntry() !== kept) return stale(user)
  const answer = await b.open(user).catch((): TouchIdAnswer => ({ outcome: 'failed' }))
  switch (answer.outcome) {
    case 'ok':
      return (await lockSession.unlockWithKey(new Uint8Array(answer.key ?? []), kept)) ? 'done' : stale(user)
    case 'cancelled':
      return 'cancelled'
    case 'invalidated':
      return stale(user)
    case 'failed':
      return 'failed'
  }
}

/**
 * The lock password was changed here, from the entry [rewrapped]: the key is the same, so the kept copy
 * follows the new entry. Only if it was kept against that entry, though; one kept against an older keyring
 * (reset elsewhere since) is stale, and turned off rather than revived.
 */
export function touchIdPasswordChanged(user: string, rewrapped: string | null) {
  const kept = bound(user)
  if (!kept) return
  const next = lockSession.passwordEntry()
  if (rewrapped && next && kept === rewrapped) setBound(user, next)
  else forgetTouchId(user)
}

/** Turned off in Settings: not offered again after the password, only turned on from there. */
export function turnOffTouchId(user: string) {
  forgetTouchId(user)
  setDeclined(user, true)
}

/** Off, and offered again: gone stale, the lock password reset, or signed out. */
export function forgetTouchId(user: string) {
  if (offer?.user === user) offer = null
  setDeclined(user, false)
  setBound(user, null)
  void current()
    ?.forget(user)
    .catch(() => undefined)
}

function stale(user: string): 'stale' {
  forgetTouchId(user)
  return 'stale'
}
