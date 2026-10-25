import { ApiError } from '../api/client'
import { notes, SEARCH_PAGE } from '../api/notes'
import type { ListItem, Note } from '../api/types'
import type { LocalListItem, LocalNote } from '../offline/protocol'
import type { Engine } from '../offline/tab'
import { NoteSaver } from './saver'
import type { NotesSource } from './source'

const LIST_PAGE = 50

/** Sync commits in bursts: one refetch per burst. */
const STALE_DEBOUNCE_MS = 100

/** This device's copy of the notes, kept in step with the server by the engine. */
export function localSource(engine: Engine): NotesSource {
  // A list is read once and paged from memory, so pages don't shift while scrolling.
  let snapshot: ListItem[] = []

  const requestSync = () => void engine.call('requestSync').catch(() => {})

  return {
    async list(filter, space, cursor) {
      const offset = cursor ? Number(cursor) : 0
      if (!cursor) snapshot = (await engine.call('list', filter, space)).map(listItem)
      const end = offset + LIST_PAGE
      return { notes: snapshot.slice(offset, end), nextCursor: String(end), hasMore: end < snapshot.length, total: snapshot.length }
    },
    async get(id) {
      const local = await engine.call('get', id)
      // Saves still go straight to the server, so it can have a note before this device does.
      return local ? note(local) : notes.get(id)
    },
    async filters(space, signal) {
      const [tags, locked] = await Promise.all([engine.call('tags', space), engine.call('lockedCount', space)])
      signal?.throwIfAborted()
      return { tags, locked }
    },
    async search(q, filter, scope, offset, signal) {
      // One past the page tells whether there's more.
      const [hits, otherSpaces] = await Promise.all([
        engine.call('search', q, filter, scope, offset, SEARCH_PAGE + 1),
        offset === 0 && 'space' in scope ? engine.call('countElsewhere', q, scope.space) : undefined,
      ])
      signal.throwIfAborted()
      return {
        results: hits.slice(0, SEARCH_PAGE),
        hasMore: hits.length > SEARCH_PAGE,
        ...(otherSpaces !== undefined && { otherSpaces }),
      }
    },
    saver: (init, hooks) =>
      new NoteSaver(init, {
        ...hooks,
        saved(...args) {
          hooks.saved?.(...args)
          requestSync()
        },
      }),
    spaces: {
      list: () => engine.call('spaces'),
      async put(id, name, icon) {
        const exists = (await engine.call('spaces')).some((s) => s.id === id)
        if (exists) await engine.call('updateSpace', id, name, icon)
        else await engine.call('createSpace', id, name, icon ?? '')
        requestSync()
        const stored = (await engine.call('spaces')).find((s) => s.id === id)
        if (!stored) throw new Error(`Space ${id} wasn't stored`)
        return stored
      },
      async remove(id) {
        // Also false for the default space, which the screens never offer to delete.
        if (!(await engine.call('deleteSpace', id))) {
          throw new ApiError(409, { error: 'space_not_empty', message: 'The space still has notes' })
        }
        requestSync()
      },
    },
    onStale(listener) {
      let timer: ReturnType<typeof setTimeout> | undefined
      const off = engine.on((e) => {
        if (e.type !== 'change') return
        clearTimeout(timer)
        timer = setTimeout(listener, STALE_DEBOUNCE_MS)
      })
      return () => {
        clearTimeout(timer)
        off()
      }
    },
  }
}

// Revision 0 is how the screens tell a note the server doesn't have yet.
const listItem = ({ revision, ...item }: LocalListItem): ListItem => ({ ...item, revision: revision ?? 0 })

function note(n: LocalNote): Note {
  return {
    id: n.id,
    title: n.title,
    content: n.content,
    revision: n.revision ?? 0,
    changeSeq: 0,
    createdAt: n.createdAt,
    updatedAt: n.updatedAt,
    deletedAt: null,
    ...(n.conflictOf !== null && { conflictOf: n.conflictOf }),
    spaceId: n.spaceId,
  }
}
