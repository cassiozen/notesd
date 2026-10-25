import sqlite3InitModule, { type SAHPoolUtil, type Sqlite3Static } from '@sqlite.org/sqlite-wasm'
import { engineHost } from './host'
import type { Call, EngineApi, WorkerMessage } from './protocol'

// The one worker per origin that owns the database: OPFS sync access handles
// only exist in dedicated workers, and the pool holds them exclusively.

// src/ is typed against the DOM; these are the worker global's members used here.
const scope = self as unknown as {
  postMessage(message: WorkerMessage): void
  addEventListener(type: 'message', listener: (e: MessageEvent<Call>) => void): void
}

const sqlite = (async () => {
  const sqlite3 = await sqlite3InitModule()
  return { sqlite3, pool: await installPool(sqlite3) }
})()

// The previous owner's handles can outlive its tab for a moment.
async function installPool(sqlite3: Sqlite3Static): Promise<SAHPoolUtil> {
  for (let attempt = 1; ; attempt++) {
    try {
      return await sqlite3.installOpfsSAHPoolVfs({ name: 'notesd' })
    } catch (err) {
      if (attempt === 20) throw err
      await new Promise((r) => setTimeout(r, 100 * attempt))
    }
  }
}

const host = engineHost(
  async (user) => {
    const { sqlite3, pool } = await sqlite
    // Up to three files a database (itself, -wal, -journal), so a new user's always fits.
    await pool.reserveMinimumCapacity(pool.getFileCount() + 4)
    const db = new pool.OpfsSAHPoolDb(`/${encodeURIComponent(user)}.sqlite3`)
    // WAL without shared memory needs exclusive locking; only this worker opens the file anyway.
    db.exec('PRAGMA locking_mode = EXCLUSIVE; PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL')
    return { db, capi: sqlite3.capi }
  },
  (event) => scope.postMessage({ type: 'event', event }),
)

scope.addEventListener('message', async ({ data: { from, id, method, args } }) => {
  try {
    const run = host[method] as (...a: unknown[]) => ReturnType<EngineApi[typeof method]>
    scope.postMessage({ type: 'reply', to: from, id, result: await run(...args) })
  } catch (err) {
    scope.postMessage({ type: 'reply', to: from, id, error: err instanceof Error ? err.message : String(err) })
  }
})
