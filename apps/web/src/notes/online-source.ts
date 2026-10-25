import { notes, SEARCH_PAGE } from '../api/notes'
import { spaces } from '../api/spaces'
import { NoteSaver } from './saver'
import type { NotesSource } from './source'

/** Other devices may have changed things; refetch on focus after this long. */
const STALE_MS = 30_000

let loadedAt = 0
let lists = 0

/** Everything on the server, as it is right now; nothing kept on this device. */
export const onlineSource: NotesSource = {
  async list(filter, space, cursor) {
    const n = ++lists
    const page = await notes.list(filter, space, cursor)
    // A page from before a newer list call is dropped by the caller: it doesn't make the list fresh.
    if (n === lists) loadedAt = Date.now()
    return page
  },
  get: (id) => notes.get(id),
  filters: (space, signal) => notes.filters(space, signal),
  async search(q, filter, scope, offset, signal) {
    const { results, otherSpaces } = await notes.search(q, filter, scope, offset, signal)
    return { results, hasMore: results.length === SEARCH_PAGE, ...(otherSpaces !== undefined && { otherSpaces }) }
  },
  saver: (init, hooks) => new NoteSaver(init, hooks),
  spaces: {
    list: async () => (await spaces.list()).spaces,
    put: (id, name, icon) => spaces.put(id, name, icon),
    remove: async (id) => {
      await spaces.remove(id)
    },
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
      window.removeEventListener('focus', onFocus)
      window.removeEventListener('online', listener)
    }
  },
}
