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

function checkAvailable() {
  if (checking) return
  const b = current()
  if (!b) {
    available = false
    return
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
}

// The password entry the kept key goes with, per account. Not a secret: the server has it too.
const boundKey = (user: string) => `notes.touchid.${user}`

function bound(user: string): string | null {
  try {
    return localStorage.getItem(boundKey(user))
  } catch {
    return null
  }
}

function setBound(user: string, entry: string | null) {
  try {
    if (entry === null) localStorage.removeItem(boundKey(user))
    else localStorage.setItem(boundKey(user), entry)
  } catch {
    // Not kept: Touch ID stays off.
  }
  notify()
}

function subscribe(onChange: () => void) {
  listeners.add(onChange)
  return () => listeners.delete(onChange)
}

/** Whether Touch ID can be offered, and whether it's on for [user] here. */
export function useTouchId(user: string | null): { available: boolean; enabled: boolean } {
  useEffect(checkAvailable, [])
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
  return 'done'
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
  else turnOffTouchId(user)
}

/** Off: turned off in Settings, gone stale, the lock password reset, or signed out. */
export function turnOffTouchId(user: string) {
  setBound(user, null)
  void current()
    ?.forget(user)
    .catch(() => undefined)
}

function stale(user: string): 'stale' {
  turnOffTouchId(user)
  return 'stale'
}
