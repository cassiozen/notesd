// Cache Storage in memory: Node and jsdom have none. Keys are compared as given.

class FakeCache {
  private entries = new Map<string, Response>()

  async match(key: RequestInfo | URL) {
    return this.entries.get(String(key))?.clone()
  }

  async put(key: RequestInfo | URL, res: Response) {
    // A Response body can be read once: keep bytes, hand out copies.
    const body = await res.arrayBuffer()
    this.entries.set(String(key), new Response(body, { status: res.status, headers: res.headers }))
  }

  async delete(key: RequestInfo | URL) {
    return this.entries.delete(String(key))
  }

  async keys() {
    return [...this.entries.keys()].map((k) => ({ url: k }))
  }
}

export function fakeCaches(): CacheStorage {
  const caches = new Map<string, FakeCache>()
  return {
    async open(name: string) {
      let c = caches.get(name)
      if (!c) caches.set(name, (c = new FakeCache()))
      return c
    },
    async has(name: string) {
      return caches.has(name)
    },
    async delete(name: string) {
      return caches.delete(name)
    },
    async keys() {
      return [...caches.keys()]
    },
    async match(key: RequestInfo | URL) {
      for (const c of caches.values()) {
        const hit = await c.match(key)
        if (hit) return hit
      }
      return undefined
    },
  } as unknown as CacheStorage
}
