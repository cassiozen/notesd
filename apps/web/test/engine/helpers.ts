import sqlite3InitModule from '@sqlite.org/sqlite-wasm'
import { onTestFinished } from 'vitest'
import type { SessionInfo } from '../../src/api/types'
import { engineHost } from '../../src/offline/host'
import type { EngineEvent, Method } from '../../src/offline/protocol'
import { syncTiming } from '../../src/offline/scheduler'
import type { Engine } from '../../src/offline/tab'
import { createOwner, startServer, type TestServer } from '../server'
import { fakeCaches } from './fake-caches'

// On first use: most app tests never start an engine.
let sqlite: ReturnType<typeof sqlite3InitModule> | undefined
const defaults = { ...syncTiming }

/**
 * The worker's engine, run in this process on an in-memory database. Calls and
 * events are structured-cloned, as postMessage would, so nothing but plain data crosses.
 */
export function inProcessEngine(user: string, csrfToken: string): Engine & { events: EngineEvent[] } {
  const listeners = new Set<(event: EngineEvent) => void>()
  const events: EngineEvent[] = []
  // Fresh per test: usernames repeat across tests.
  const before = globalThis.caches
  globalThis.caches = fakeCaches()
  onTestFinished(() => {
    globalThis.caches = before
  })
  const host = engineHost(
    async () => {
      const s = await (sqlite ??= sqlite3InitModule())
      return { db: new s.oo1.DB(':memory:'), capi: s.capi }
    },
    (event) => {
      events.push(structuredClone(event))
      for (const l of listeners) l(structuredClone(event))
    },
  )
  const call = async (method: Method, ...args: unknown[]) => {
    const run = host[method] as (...a: unknown[]) => Promise<unknown>
    const result = await run(...structuredClone(args))
    // Browsers clone a Blob; Node's structuredClone turns it into {}.
    return result instanceof Blob ? result : structuredClone(result)
  }
  void host.open(user, csrfToken)
  onTestFinished(() => host.close())
  return {
    events,
    call: call as Engine['call'],
    on(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    setCsrfToken: (t) => void host.open(user, t),
    close: () => host.close(),
  }
}

export async function signIn(username: string, password: string): Promise<string> {
  const res = await fetch('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, client: 'web' }),
  })
  if (!res.ok) throw new Error(`login failed: ${res.status}`)
  return ((await res.json()) as SessionInfo).csrfToken
}

/** Long enough that only what a test triggers runs; tests that want the scheduler shorten these. */
function onlyWhatTestsRun() {
  Object.assign(syncTiming, { afterEditMs: 60_000, intervalMs: 60_000, visibleIntervalMs: 60_000, openFreshMs: 60_000, firstRetryMs: 60_000 })
  onTestFinished(() => {
    Object.assign(syncTiming, defaults)
  })
}

/** The engine for an account the browser signs in as, after its first sync. */
async function engineAs(username: string, password: string) {
  const engine = inProcessEngine(username, await signIn(username, password))
  // The first sync starts on open; done before the test makes its own.
  await waitFor(async () => (await engine.call('status')).kind === 'Synced')
  return engine
}

/** A server, an owner signed in as "the browser", and the engine for them. */
export async function engineSignedIn(): Promise<{ server: TestServer; engine: ReturnType<typeof inProcessEngine>; password: string }> {
  onlyWhatTestsRun()
  const server = await startServer()
  const { username, password } = await createOwner(server)
  await server.api('POST', '/auth/login', { username, password, client: 'web' })
  const engine = await engineAs(username, password)
  return { server, engine, password }
}

/**
 * For shared spaces (§7.9): the owner "me", signed in on server.api, and Ana, another account on a client of her
 * own. This device's engine is [device]'s, knowing its account's id.
 */
export async function twoAccounts(device: 'me' | 'ana' = 'me') {
  onlyWhatTestsRun()
  const server = await startServer()
  const owner = await createOwner(server)
  await server.api('POST', '/auth/login', { ...owner, client: 'web' })
  const me = (await server.api<SessionInfo>('GET', '/auth/session')).body.userId
  if (me === undefined) throw new Error('the session names no userId')
  const ana = await server.addAccount('ana')
  const engine = device === 'me' ? await engineAs(owner.username, owner.password) : await engineAs(ana.username, ana.password)
  await engine.call('setUserId', device === 'me' ? me : ana.id)
  return { server, ana, me, engine }
}

export async function waitFor(check: () => Promise<boolean> | boolean, timeoutMs = 5_000) {
  const end = Date.now() + timeoutMs
  while (!(await check())) {
    if (Date.now() > end) throw new Error('timed out')
    await new Promise((r) => setTimeout(r, 20))
  }
}

