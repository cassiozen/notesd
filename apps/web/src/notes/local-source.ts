import { ApiError } from '../api/client'
import { SEARCH_PAGE } from '../api/notes'
import type { ListItem, Note } from '../api/types'
import type { LocalListItem, LocalNote, SyncStatus } from '../offline/protocol'
import type { Engine } from '../offline/tab'
import { LocalSaver } from './local-saver'
import type { NotesSource, Unsynced } from './source'

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
      if (!local) throw new ApiError(404, { error: 'not_found', message: 'No such note on this device' })
      return note(local)
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
    saver: (init, hooks) => new LocalSaver(engine, init, hooks),
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
    sync: {
      onUnsynced(listener) {
        // As on the phones: Syncing is skipped, or the scheduler's retries would make it flicker.
        let status: SyncStatus | null = null
        let pending = 0
        let shown: string | undefined
        let open = true
        const emit = () => {
          if (!open || !status) return
          let u: Unsynced | null = null
          if (pending > 0 && status.kind === 'Offline') u = { pending, error: null }
          else if (pending > 0 && status.kind === 'Failed') u = { pending, error: status.message }
          const key = JSON.stringify(u)
          if (key === shown) return
          shown = key
          listener(u)
        }
        const settled = (s: SyncStatus) => {
          if (s.kind === 'Syncing') return
          status = s
          emit()
        }
        const count = () =>
          engine.call('pendingNotes').then(
            (n) => {
              pending = n
              emit()
            },
            () => {},
          )
        const off = engine.on((e) => {
          if (e.type === 'status') settled(e.status)
          else if (e.type === 'change') void count()
        })
        engine.call('status').then(settled, () => {})
        void count()
        return () => {
          open = false
          off()
        }
      },
      onNotice: (listener) =>
        engine.on((e) => {
          if (e.type === 'notice') listener(e.notice)
        }),
      now: requestSync,
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
