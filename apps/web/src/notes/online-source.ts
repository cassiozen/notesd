import { imageURL, images } from '../api/images'
import { locks } from '../api/locks'
import { notes, SEARCH_PAGE } from '../api/notes'
import { sharing, spaces } from '../api/spaces'
import type { Space } from '../api/types'
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
  // The server finds a note in whichever file it is (§7.9).
  get: (id) => notes.get(id),
  filters: (space, signal) => notes.filters(space, signal),
  async search(q, filter, scope, offset, signal) {
    const { results, otherSpaces } = await notes.search(q, filter, scope, offset, signal)
    return { results, hasMore: results.length === SEARCH_PAGE, ...(otherSpaces !== undefined && { otherSpaces }) }
  },
  saver: (init, hooks) => new NoteSaver(init, hooks),
  images: {
    get: (id, signal) => images.get(id, signal),
    put: async (id, bytes, type) => {
      await images.put(id, bytes, type)
    },
    url: imageURL,
  },
  keyring: {
    load: async () => (await locks.keys()).keys,
    changed: async () => {},
  },
  spaces: {
    async list() {
      const list = (await spaces.list()).spaces
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
      if (me !== null && known.some((s) => s.id === space && s.shared)) markVisited(me, space)
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
      window.removeEventListener('focus', onFocus)
      window.removeEventListener('online', listener)
    }
  },
}
