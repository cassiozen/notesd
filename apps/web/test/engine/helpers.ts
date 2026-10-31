import sqlite3InitModule from '@sqlite.org/sqlite-wasm'
import { onTestFinished } from 'vitest'
import type { SessionInfo } from '../../src/api/types'
import { engineHost } from '../../src/offline/host'
import type { EngineEvent, Method } from '../../src/offline/protocol'
import { syncTiming } from '../../src/offline/scheduler'
import type { Engine } from '../../src/offline/tab'
import { createOwner, startServer, type TestServer } from '../server'

const sqlite = sqlite3InitModule()
const defaults = { ...syncTiming }

/**
 * The worker's engine, run in this process on an in-memory database. Calls and
 * events are structured-cloned, as postMessage would, so nothing but plain data crosses.
 */
export function inProcessEngine(user: string, csrfToken: string): Engine & { events: EngineEvent[] } {
  const listeners = new Set<(event: EngineEvent) => void>()
  const events: EngineEvent[] = []
  const host = engineHost(
    async () => {
      const s = await sqlite
      return { db: new s.oo1.DB(':memory:'), capi: s.capi }
    },
    (event) => {
      events.push(structuredClone(event))
      for (const l of listeners) l(structuredClone(event))
    },
  )
  const call = async (method: Method, ...args: unknown[]) => {
    const run = host[method] as (...a: unknown[]) => Promise<unknown>
    return structuredClone(await run(...structuredClone(args)))
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

/** A server, an owner signed in as "the browser", and the engine for them. */
export async function engineSignedIn(): Promise<{ server: TestServer; engine: ReturnType<typeof inProcessEngine>; password: string }> {
  // Long enough that only what a test triggers runs; tests that want the scheduler shorten these.
  Object.assign(syncTiming, { afterEditMs: 60_000, intervalMs: 60_000, firstRetryMs: 60_000 })
  onTestFinished(() => {
    Object.assign(syncTiming, defaults)
  })
  const server = await startServer()
  const { username, password } = await createOwner(server)
  await server.api('POST', '/auth/login', { username, password, client: 'web' })
  const engine = inProcessEngine(username, await signIn(username, password))
  // The first sync starts on open; done before the test makes its own.
  await waitFor(async () => (await engine.call('status')).kind === 'Synced')
  return { server, engine, password }
}

export async function waitFor(check: () => Promise<boolean> | boolean, timeoutMs = 5_000) {
  const end = Date.now() + timeoutMs
  while (!(await check())) {
    if (Date.now() > end) throw new Error('timed out')
    await new Promise((r) => setTimeout(r, 20))
  }
}

