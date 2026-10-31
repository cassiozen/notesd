import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { runInNewContext } from 'node:vm'
import { transformWithOxc } from 'vite'
import { beforeEach, describe, expect, it } from 'vitest'

const ORIGIN = 'https://notes.test'
const PRECACHE = ['/index.html', '/assets/index-abc.js', '/icon-192.png']
const ENGINE = ['/assets/worker-abc.js', '/assets/sqlite3-abc.wasm']

type Listener = (event: Record<string, unknown>) => void

let listeners: Record<string, Listener>
let stores: Map<string, Map<string, string>>
let fetched: string[]
let skipped: number
let full: boolean

function store(name: string) {
  let s = stores.get(name)
  if (!s) stores.set(name, (s = new Map()))
  return s
}

async function fetch(req: Request) {
  fetched.push(req.url)
  return new Response('network')
}

async function loadWorker(version: string) {
  const src = resolve(import.meta.dirname, '../src/sw.ts')
  const { code } = await transformWithOxc(readFileSync(src, 'utf8'), src, { lang: 'ts' })
  const caches = {
    open: async (name: string) => ({
      addAll: async (reqs: Request[]) => {
        for (const r of reqs) store(name).set(new URL(r.url, ORIGIN).pathname, `cached ${r.url}`)
      },
      put: async (key: string, res: Response) => {
        if (full) throw new DOMException('The quota has been exceeded.', 'QuotaExceededError')
        store(name).set(key, `kept ${await res.text()}`)
      },
      keys: async () => [...store(name).keys()].map((k) => ({ url: new URL(k, ORIGIN).href })),
    }),
    keys: async () => [...stores.keys()],
    delete: async (name: string) => stores.delete(name),
    match: async (key: string, { cacheName }: { cacheName: string }) => {
      const body = stores.get(cacheName)?.get(key)
      return body === undefined ? undefined : new Response(body)
    },
  }
  const self = {
    location: new URL(ORIGIN),
    clients: { claim: async () => {} },
    skipWaiting: async () => {
      skipped++
    },
    addEventListener: (type: string, fn: Listener) => (listeners[type] = fn),
  }
  const header = `const PRECACHE = ${JSON.stringify(PRECACHE)}; const ENGINE = ${JSON.stringify(ENGINE)};`
  runInNewContext(`${header} const VERSION = '${version}';\n${code}`, {
    self,
    caches,
    fetch,
    Request: class extends Request {
      constructor(url: string, init?: RequestInit) {
        super(new URL(url, ORIGIN), init)
      }
    },
    URL,
    Set,
    Promise,
  })
}

async function lifecycle(type: 'install' | 'activate') {
  let done: Promise<unknown> = Promise.resolve()
  listeners[type]?.({ waitUntil: (p: Promise<unknown>) => (done = p) })
  await done
}

/** The response the worker gives, or null when it leaves the request to the network. */
async function request(path: string, { method = 'GET', mode = 'cors' }: { method?: string; mode?: string } = {}) {
  let response: Promise<Response> | null = null
  const req = { url: new URL(path, ORIGIN).href, method, mode }
  listeners.fetch?.({ request: req, respondWith: (r: Promise<Response>) => (response = r) })
  return response === null ? null : (await (response as Promise<Response>)).text()
}

beforeEach(async () => {
  listeners = {}
  stores = new Map()
  fetched = []
  skipped = 0
  full = false
  await loadWorker('v1')
  await lifecycle('install')
  await lifecycle('activate')
})

describe('service worker', () => {
  it('opens every app route from the cached index.html', async () => {
    for (const path of ['/', '/notes', '/notes/abc123', '/settings']) {
      expect(await request(path, { mode: 'navigate' })).toBe(`cached ${ORIGIN}/index.html`)
    }
    expect(fetched).toEqual([])
  })

  it('serves precached files from the cache', async () => {
    expect(await request('/assets/index-abc.js')).toBe(`cached ${ORIGIN}/assets/index-abc.js`)
    expect(await request('/icon-192.png')).toBe(`cached ${ORIGIN}/icon-192.png`)
  })

  it('leaves the API, health checks, other files and writes to the network', async () => {
    expect(await request('/api/v1/notes')).toBeNull()
    expect(await request('/api/v1/images/abc')).toBeNull()
    expect(await request('/api/v1/auth/session', { mode: 'navigate' })).toBeNull()
    expect(await request('/healthz')).toBeNull()
    expect(await request('/readyz', { mode: 'navigate' })).toBeNull()
    expect(await request('/assets/unknown.js')).toBeNull()
    expect(await request('/favicon.ico', { mode: 'navigate' })).toBeNull()
    expect(await request('/index.html', { method: 'POST' })).toBeNull()
    expect(await request('https://elsewhere.test/assets/index-abc.js')).toBeNull()
  })

  it('falls back to the network when the cache lost an entry', async () => {
    stores.get('shell-v1')?.delete('/index.html')
    expect(await request('/notes', { mode: 'navigate' })).toBe('network')
  })

  it('takes over from the previous build without waiting for its windows to close', async () => {
    expect(skipped).toBe(1)
    await loadWorker('v2')
    await lifecycle('install')
    expect(skipped).toBe(2)
  })

  it('keeps the sync engine only once this browser loads it', async () => {
    expect([...store('shell-v1').keys()]).toEqual(PRECACHE)
    expect(await request('/assets/worker-abc.js')).toBe('network')
    expect(await request('/assets/worker-abc.js')).toBe('kept network')
    expect(fetched).toEqual([`${ORIGIN}/assets/worker-abc.js`])
  })

  it('starts the sync engine even when there’s no room to keep it', async () => {
    full = true
    expect(await request('/assets/worker-abc.js')).toBe('network')
    expect(store('shell-v1').has('/assets/worker-abc.js')).toBe(false)
  })

  it('brings the next build’s engine along where the engine was kept', async () => {
    await loadWorker('v2')
    await lifecycle('install')
    expect(store('shell-v2').has('/assets/sqlite3-abc.wasm')).toBe(false)

    await request('/assets/sqlite3-abc.wasm')
    await loadWorker('v3')
    await lifecycle('install')
    expect([...store('shell-v3').keys()]).toEqual([...PRECACHE, ...ENGINE])
  })

  it('drops the previous build when a new one activates', async () => {
    await loadWorker('v2')
    await lifecycle('install')
    expect([...stores.keys()]).toEqual(['shell-v1', 'shell-v2'])
    await lifecycle('activate')
    expect([...stores.keys()]).toEqual(['shell-v2'])
  })
})
