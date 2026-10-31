import { sessionGone } from '../api/client'
import { deviceUser } from '../auth/session-hint'
import { localSource } from '../notes/local-source'
import { onlineSource } from '../notes/online-source'
import { setNotesSource } from '../notes/source'
import { connectEngine, type Engine } from './tab'

// Which notes the screens show for a signed-in user: this browser's copy
// when it keeps one for them, else the server's.

type Start = (user: string, csrfToken: string) => Engine

let start: Start = connectEngine
let current: { user: string; engine: Engine; off: () => void } | null = null

/** Tests run the engine in-process. */
export function setEngineStart(fn: Start) {
  start = fn
}

/** Before the screens mount, and on every sign-in (a new token). [csrfToken] null: booted offline. */
export function openNotes(user: string, csrfToken: string | null): Engine | null {
  if (deviceUser() !== user) {
    closeNotes()
    return null
  }
  if (current?.user === user) {
    if (csrfToken) current.engine.setCsrfToken(csrfToken)
    return current.engine
  }
  closeNotes()
  const engine = start(user, csrfToken ?? '')
  const off = engine.on((e) => {
    // The screens may make no request that would find out.
    if (e.type === 'status' && e.status.kind === 'SignedOut') sessionGone()
  })
  current = { user, engine, off }
  // Best effort: without it the browser may clear the copy under storage pressure, unsynced edits too.
  void navigator.storage?.persist?.().catch(() => false)
  setNotesSource(localSource(engine))
  return engine
}

export function closeNotes() {
  if (current) {
    current.off()
    current.engine.close()
    current = null
  }
  setNotesSource(onlineSource)
}
