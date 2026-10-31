import { imageURL } from '../api/images'
import { sha256Hex } from '../images/ref'

// Cache Storage: the page, the engine's worker and a service worker can all read it.
// One cache per user, so another account on this browser can't show them.

export type DeviceImages = {
  /** For the engine (openEngine's files): the bytes sync uploads. */
  read(id: string): Promise<Int8Array | null>
  write(id: string, bytes: Int8Array, type: string): Promise<void>
  /** From this device, or downloaded and kept; null when the server has no such image. */
  load(id: string): Promise<Blob | null>
  clear(): Promise<void>
}

export function deviceImages(user: string): DeviceImages {
  const name = `notesd-images:${encodeURIComponent(user)}`
  const cache = () => caches.open(name)
  const match = async (id: string) => (await cache()).match(imageURL(id))

  const put = async (id: string, bytes: Uint8Array<ArrayBuffer>, type: string) => {
    await (await cache()).put(imageURL(id), new Response(bytes, { headers: { 'Content-Type': type } }))
  }

  return {
    async read(id) {
      const hit = await match(id)
      return hit ? new Int8Array(await hit.arrayBuffer()) : null
    },
    write: (id, bytes, type) => put(id, new Uint8Array(bytes.buffer as ArrayBuffer, bytes.byteOffset, bytes.byteLength), type),
    async load(id) {
      const kept = await match(id)
      if (kept) return kept.blob()
      const res = await fetch(imageURL(id), { credentials: 'same-origin', cache: 'no-store' })
      if (res.status === 404) return null
      if (!res.ok) throw new Error(`The image didn’t download (${res.status})`)
      const bytes = new Uint8Array(await res.arrayBuffer())
      // The id is the hash: anything else isn't this image.
      if ((await sha256Hex(bytes)) !== id) return null
      const type = res.headers.get('Content-Type') ?? ''
      await put(id, bytes, type)
      return new Blob([bytes], { type })
    },
    async clear() {
      await caches.delete(name)
    },
  }
}
