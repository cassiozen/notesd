import { ApiError } from '../api/client'
import { filePath, forgetFiles, isSharedFile, knowShared, knowSpaces, sharedSpaces } from '../api/files'
import { imageURL, images } from '../api/images'
import { locks } from '../api/locks'
import { notes, SEARCH_PAGE } from '../api/notes'
import { sharing, spaces } from '../api/spaces'
import type { Note, NoteFilter, SearchScope, Space } from '../api/types'
import { feedNews, markVisited } from './online-feeds'
import { NoteSaver } from './saver'
import type { NotesSource } from './source'

/** Other devices may have changed things; refetch on focus after this long. */
const STALE_MS = 30_000

let loadedAt = 0
let lists = 0
/** The last list fetched: who's in each shared space, for what changed. */
let known: Space[] = []

/** Everything on the server, as it is right now; nothing kept on this device. */
export const onlineSource: NotesSource = {
  async list(filter, space, cursor) {
    const n = ++lists
    const page = await notes.list(filter, space, cursor)
    // A page from before a newer list call is dropped by the caller: it doesn't make the list fresh.
    if (n === lists) loadedAt = Date.now()
    return page
  },
  get: findNote,
  filters: (space, signal) => notes.filters(space, signal),
  search,
  saver: (init, hooks) => new NoteSaver(init, hooks),
  images: {
    get: (id, signal) => images.get(id, signal),
    put: async (id, bytes, type, space) => {
      await images.put(id, bytes, type, space)
    },
    url: imageURL,
  },
  keyring: {
    load: async () => (await locks.keys()).keys,
    changed: async () => {},
  },
  spaces: {
    async list() {
      const list = await knowSpaces(async () => (await spaces.list()).spaces)
      known = list
      return list
    },
    put: (id, name, icon) => spaces.put(id, name, icon),
    remove: async (id) => {
      await spaces.remove(id)
    },
  },
  sharing: {
    invites: () => sharing.invites(),
    news: async (me) => (me === null ? [] : feedNews(known, me)),
    async visit(space, me) {
      if (me !== null && isSharedFile(space)) markVisited(me, space)
    },
    // Edits not yet on the server are the open notes' own: the screens count those.
    unsynced: async () => [],
    settle: async () => {},
  },
  // Only a refetch sees other devices' changes: on coming back to the tab after a while, and on reconnecting.
  onStale(listener) {
    const onFocus = () => {
      if (Date.now() - loadedAt > STALE_MS) listener()
    }
    window.addEventListener('focus', onFocus)
    window.addEventListener('online', listener)
    return () => {
      // The notes unmount (signed out): the next account's list hasn't loaded.
      loadedAt = 0
      known = []
      forgetFiles()
      window.removeEventListener('focus', onFocus)
      window.removeEventListener('online', listener)
    }
  },
}

/**
 * A note is in one file: [space]'s first, then the account's, then each shared
 * space's. A tombstone a move left (§7.9) says where it went.
 */
async function findNote(id: string, space?: string): Promise<Note> {
  const queue = [space === undefined ? '' : filePath(space), '', ...sharedSpaces().map(filePath)]
  const tried = new Set<string>()
  let gone: unknown = null
  for (let file = queue.shift(); file !== undefined; file = queue.shift()) {
    if (tried.has(file)) continue
    tried.add(file)
    try {
      // One file after another, each only if the last didn't have it.
      // oxlint-disable-next-line no-await-in-loop
      const note = await notes.get(id, file)
      if (file) knowShared(note.spaceId)
      return note
    } catch (err) {
      if (!(err instanceof ApiError) || (err.status !== 404 && err.status !== 410)) throw err
      const moved = err.status === 410 ? err.current?.movedTo : undefined
      // Gone from here to a space that may be shared or the account's own: both are looked in next.
      if (moved) queue.unshift(`/spaces/${encodeURIComponent(moved)}`, '')
      else if (err.status === 410) gone = err
      gone ??= err
    }
  }
  throw gone instanceof Error ? gone : new ApiError(404, { error: 'not_found', message: 'No such note' })
}

/**
 * One space is searched in its own file. The server does the rest across every file the account reaches (§7.9):
 * exceptSpace merges them all into one ranked page, and otherSpaces counts them all.
 */
async function search(q: string, filter: NoteFilter, scope: SearchScope, offset: number, signal: AbortSignal) {
  const { results, otherSpaces } = await notes.search(q, filter, scope, offset, signal, 'space' in scope ? filePath(scope.space) : '')
  return { results, hasMore: results.length === SEARCH_PAGE, ...(otherSpaces !== undefined && { otherSpaces }) }
}
