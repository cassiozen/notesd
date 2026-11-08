import { request, upload } from './client'
import type { Person } from './types'

/** The signed-in account's own name and photo; each answers with the account as it is now. */
export const profile = {
  /** '' goes back to the username. */
  setName: (displayName: string) => request<Person>('PATCH', '/profile', { displayName }),
  /** A square JPEG of at most 512 px a side (settings/photo.ts makes one). */
  setPhoto: (jpeg: Uint8Array<ArrayBuffer>) => upload<Person>('/profile/avatar', jpeg, 'image/jpeg'),
  removePhoto: () => request<Person>('DELETE', '/profile/avatar'),
}

/** Versioned, so the browser keeps it for good and a new photo is a new URL. */
export const avatarURL = (userId: number, version: string) => `/api/v1/users/${userId}/avatar?v=${encodeURIComponent(version)}`

/** How the apps name someone: their chosen name, or else their username. */
export const nameOf = (p: { username: string; displayName?: string }) => p.displayName || p.username
