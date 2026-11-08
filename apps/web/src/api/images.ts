import { download, upload } from './client'

/** The type a locked note's images are stored as: ciphertext (architecture §7.6). The one version this release opens. */
export const LOCKED_IMAGE = 'application/vnd.notesd.locked'

/** A locked note's image in any version: later ones add theirs to the subtype (….locked.v2). */
export function isLockedImageType(type: string): boolean {
  return type === LOCKED_IMAGE || type.startsWith(`${LOCKED_IMAGE}.`)
}

export type StoredImage = { id: string; contentType: string; size: number; createdAt: number }

export const imagePath = (id: string) => `/images/${id}`

export const images = {
  /**
   * Idempotent: the id is the bytes' SHA-256, so a retry stores nothing new. Always to the account's address: a
   * note written into a shared space takes the pictures it shows there (§7.9).
   */
  put: (id: string, body: Uint8Array<ArrayBuffer>, contentType: string) => upload<StoredImage>(imagePath(id), body, contentType),
  get: (id: string, signal?: AbortSignal) => download(imagePath(id), { ...(signal && { signal }) }),
}

/** Where an <img> loads a plain image from (same origin, so the session cookie goes along). */
export const imageURL = (id: string) => `/api/v1${imagePath(id)}`
