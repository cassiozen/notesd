// App shell from cache so the app opens instantly, and offline from this device's notes.
// Build files only: the API is cookie-auth + no-store; the engine keeps its own copy.

// Prepended by the service-worker plugin (vite.config.ts); file must stay import-free.
declare const PRECACHE: string[]
/** The sync engine's files: only for a browser that keeps notes, so cached once it loads them. */
declare const ENGINE: string[]
declare const VERSION: string

const sw = self as unknown as ServiceWorkerGlobalScope
const CACHE = `shell-${VERSION}`
const precached = new Set(PRECACHE)
const engine = new Set(ENGINE)
const NETWORK_ONLY = ['/api/', '/healthz', '/readyz']

sw.addEventListener('install', (event) => {
  // no-cache: index.html and the root files may sit stale in the HTTP cache.
  event.waitUntil(install())
  // Don't wait for windows to close: next load gets this build. Safe with no lazy JS chunks;
  // an open old page keeps its JS and at worst misses an unloaded font subset.
  void sw.skipWaiting()
})

sw.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => sw.clients.claim()),
  )
})

sw.addEventListener('fetch', (event) => {
  const req = event.request
  if (req.method !== 'GET') return
  const url = new URL(req.url)
  if (url.origin !== sw.location.origin) return
  if (NETWORK_ONLY.some((p) => url.pathname === p || url.pathname.startsWith(p))) return

  // Mirrors the server's SPA fallback: extensionless paths are app routes.
  if (req.mode === 'navigate' && !/\.[^/]*$/.test(url.pathname)) {
    event.respondWith(fromCache('/index.html', req))
    return
  }
  if (precached.has(url.pathname)) event.respondWith(fromCache(url.pathname, req))
  else if (engine.has(url.pathname)) event.respondWith(keep(url.pathname, req))
})

async function install() {
  const cache = await caches.open(CACHE)
  const files = (await enginePreviouslyKept()) ? [...PRECACHE, ...ENGINE] : PRECACHE
  await cache.addAll(files.map((u) => new Request(u, { cache: 'no-cache' })))
}

/** This browser keeps notes: the next offline open needs this build's engine too. */
async function enginePreviouslyKept(): Promise<boolean> {
  for (const name of await caches.keys()) {
    if (!name.startsWith('shell-') || name === CACHE) continue
    // oxlint-disable-next-line no-await-in-loop
    const keys = await (await caches.open(name)).keys()
    if (keys.some((r) => new URL(r.url).pathname.endsWith('.wasm'))) return true
  }
  return false
}

async function keep(key: string, req: Request): Promise<Response> {
  const hit = await caches.match(key, { cacheName: CACHE })
  if (hit) return hit
  const res = await fetch(req)
  // Out of quota, say: the engine still starts, only not offline.
  if (res.ok) await (await caches.open(CACHE)).put(key, res.clone()).catch(() => undefined)
  return res
}

async function fromCache(key: string, req: Request): Promise<Response> {
  const hit = await caches.match(key, { cacheName: CACHE })
  return hit ?? fetch(req)
}
