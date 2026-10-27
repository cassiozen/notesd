import { useEffect, useState } from 'react'
import { csrfTokenNow, sessionGone } from '../api/client'
import { deviceUser, keepOnDevice } from '../auth/session-hint'
import { localSource } from '../notes/local-source'
import { onlineSource } from '../notes/online-source'
import { setNotesSource } from '../notes/source'
import { connectEngine, type Engine } from './tab'

// Which notes the screens show for a signed-in user: this browser's copy
// when it keeps one for them (they opted in), else the server's.

type Start = (user: string, csrfToken: string) => Engine

let start: Start = connectEngine
let current: { user: string; engine: Engine; off: () => void } | null = null
let failure: string | null = null

/** Tests run the engine in-process, where every browser feature it needs is "there". */
export function setEngineStart(fn: Start) {
  start = fn
}

/** Whether this browser can keep notes (private windows have no OPFS); false until known, or unless [asked]. */
export function useDeviceSupported(asked = true): boolean {
  const [supported, setSupported] = useState(false)
  useEffect(() => {
    if (!asked) return
    let live = true
    void (start === connectEngine ? detectSupport() : Promise.resolve(true)).then((ok) => live && setSupported(ok))
    return () => {
      live = false
    }
  }, [asked])
  return supported
}

async function detectSupport(): Promise<boolean> {
  if (typeof Worker === 'undefined' || typeof BroadcastChannel === 'undefined' || typeof caches === 'undefined') {
    return false
  }
  if (!navigator.locks || !navigator.storage?.getDirectory) return false
  try {
    await navigator.storage.getDirectory()
    return true
  } catch {
    return false
  }
}

/** Before the screens mount, and on every sign-in (a new token). Booted offline, there's no token yet. */
export function openNotes(user: string): Engine | null {
  if (deviceUser() !== user) {
    closeNotes()
    return null
  }
  const token = csrfTokenNow()
  if (current?.user === user) {
    if (token) current.engine.setCsrfToken(token)
    return current.engine
  }
  closeNotes()
  const engine = start(user, token ?? '')
  const off = engine.on((e) => {
    // The screens may make no request that would find out.
    if (e.type === 'status' && e.status.kind === 'SignedOut') sessionGone()
  })
  current = { user, engine, off }
  failure = null
  setNotesSource(localSource(engine))
  // A worker that can't start (storage blocked since opting in): the server's notes, not errors everywhere.
  engine.call('status').catch((err: unknown) => {
    if (current?.engine !== engine) return
    failure = err instanceof Error ? err.message : String(err)
    closeNotes()
  })
  return engine
}

export function closeNotes() {
  setNotesSource(onlineSource)
  if (!current) return
  current.off()
  current.engine.close()
  current = null
}

/** Best effort, from a click (some browsers grant it only then). Unasked, the browser may clear the copy when short of space. */
export function askToPersist() {
  void navigator.storage?.persist?.().catch(() => false)
}

/** Opting in: from now on this browser keeps [user]'s notes. Call from the click, for persist()'s sake. */
export function keepNotesHere(user: string) {
  keepOnDevice(user)
  askToPersist()
  openNotes(user)
}

/** Notes changed here that haven't reached the server, after trying once more; 0 when nothing's kept here. */
export async function unsyncedHere(): Promise<number> {
  const engine = current?.engine
  if (!engine) return 0
  await engine.call('sync').catch(() => undefined)
  return engine.call('pendingNotes')
}

/** Opting out, or signing out: every note, picture and key off this device. */
export async function forgetNotesHere() {
  const engine = current?.engine
  keepOnDevice(null)
  try {
    await engine?.call('wipe')
  } finally {
    closeNotes()
  }
}

/** Why the notes kept here couldn't be opened, since the last try. */
export function deviceFailure(): string | null {
  return failure
}
