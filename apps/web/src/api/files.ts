import type { Space } from './types'

// Which file holds a space's notes on the server (architecture §7.9): the
// account's, or a shared space's own under /spaces/<id>. Known from the
// space list, and from any answer that names a shared space.

const shared = new Set<string>()
// Each list's number: one answering after a later one was applied is older, and may lack a space shared since.
let asked = 0
let applied = 0

/** Fetches the space list and takes every space it marks shared, and none other. */
export async function knowSpaces<T extends Pick<Space, 'id' | 'shared'>>(fetch: () => Promise<T[]>): Promise<T[]> {
  const n = ++asked
  const list = await fetch()
  if (n > applied) {
    applied = n
    shared.clear()
    for (const s of list) if (s.shared) shared.add(s.id)
  }
  return list
}

/** Signed out: the next account's spaces are its own, and no list asked for before applies. */
export function forgetFiles() {
  shared.clear()
  applied = asked
}

/** A space found shared before the list says so (a note's answer named it). */
export function knowShared(spaceId: string) {
  shared.add(spaceId)
}

export function isSharedFile(spaceId: string): boolean {
  return shared.has(spaceId)
}

/** The shared spaces known now. */
export function sharedSpaces(): string[] {
  return [...shared]
}

/** The path prefix of [spaceId]'s file: '' for the account's. */
export function filePath(spaceId: string): string {
  return shared.has(spaceId) ? `/spaces/${encodeURIComponent(spaceId)}` : ''
}

/** Whether moving a note between the two is a move between files (POST …/move), not an update. */
export function sameFile(a: string, b: string): boolean {
  return filePath(a) === filePath(b)
}
