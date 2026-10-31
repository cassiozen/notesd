import {
  LocalLockKey,
  openEngine,
  type EngineNotice,
  type EngineStatus,
  type LocalNote as EngineNote,
  type NotesEngine,
} from '@notes/core'
import type { LockKey, NoteFilter } from '../api/types'
import { canonical } from '../editor/markdown'
import { deviceImages, type DeviceImages } from './images'
import type { EngineApi, EngineEvent, LocalNote, SyncNotice, SyncOutcome, SyncStatus } from './protocol'
import { Scheduler } from './scheduler'

/** A user's database on sqlite-wasm: an oo1 DB and the capi it came with. */
export type OpenDatabase = (user: string) => Promise<{ db: unknown; capi: unknown }>

type Open = { user: string; engine: NotesEngine; images: DeviceImages; scheduler: Scheduler; unsubscribe: (() => void)[] }

/**
 * The engine behind EngineApi, for the worker that owns the database, or
 * in-process in tests. [emit] gets every event, for every tab.
 */
export function engineHost(openDatabase: OpenDatabase, emit: (event: EngineEvent) => void): EngineApi & { close(): void } {
  // Opens chain, one at a time: two switching users at once would each stop the old engine, and one new one leak.
  let current: Promise<Open | null> = Promise.resolve(null)

  async function opened() {
    const o = await current
    if (!o) throw new Error('The notes engine is not open')
    return o
  }

  const engine = async () => (await opened()).engine

  async function start(user: string, csrfToken: string): Promise<Open> {
    const { db, capi } = await openDatabase(user)
    const images = deviceImages(user)
    const e = openEngine(db, capi, canonical, images)
    e.setCsrfToken(csrfToken)
    const scheduler = new Scheduler(async () => (await e.sync()) as SyncOutcome)
    const unsubscribe = [
      e.onStatus((s) => emit({ type: 'status', status: status(s) })),
      e.onChange(() => emit({ type: 'change' })),
      e.onNotice((n) => emit({ type: 'notice', notice: notice(n) })),
      e.onLocalEdit(() => scheduler.edited()),
    ]
    return { user, engine: e, images, scheduler, unsubscribe }
  }

  function stop(o: Open) {
    o.scheduler.stop()
    for (const u of o.unsubscribe) u()
    o.engine.close()
  }

  return {
    async open(user, csrfToken) {
      current = current
        .catch(() => null)
        .then((before) => {
          if (before?.user === user) {
            before.engine.setCsrfToken(csrfToken)
            // Usually a new sign-in after the session ended: sync was waiting for it.
            before.scheduler.request()
            return before
          }
          if (before) stop(before)
          return start(user, csrfToken)
        })
      await current
    },
    async requestSync() {
      const o = await current
      o?.scheduler.request()
    },
    async sync() {
      return (await (await engine()).sync()) as SyncOutcome
    },
    async status() {
      return status((await engine()).status())
    },
    async get(id) {
      const n = await (await engine()).get(id)
      return n ? localNote(n) : null
    },
    async list(filter, space) {
      const rows = await (await engine()).list(...filterArgs(filter), space)
      return rows.map((r) => ({
        id: r.id,
        title: r.title,
        preview: r.preview,
        createdAt: r.createdAt,
        updatedAt: r.updatedAt,
        locked: r.locked,
        revision: r.revision ?? null,
        spaceId: r.spaceId,
      }))
    },
    async lockedCount(space) {
      return (await engine()).lockedCount(space)
    },
    async search(query, filter, scope, offset, limit) {
      const space = scope && 'space' in scope ? scope.space : null
      const exceptSpace = scope && 'exceptSpace' in scope ? scope.exceptSpace : null
      const hits = await (await engine()).search(query, ...filterArgs(filter), space, exceptSpace, offset, limit)
      return hits.map((h) => ({
        id: h.id,
        title: h.title,
        excerpt: h.excerpt,
        updatedAt: h.updatedAt,
        locked: h.locked,
        spaceId: h.spaceId,
      }))
    },
    async countElsewhere(query, space) {
      return (await engine()).countElsewhere(query, space)
    },
    async tags(space) {
      return (await (await engine()).tags(space)).map((t) => ({ key: t.key, name: t.name, count: t.count }))
    },
    async save({ id, title, content, basedOn, imageIds, spaceId }) {
      const e = await engine()
      const saved = await e.save(id, title, content, basedOn?.title, basedOn?.content, imageIds, spaceId)
      return saved ? localNote(saved) : null
    },
    async move(id, spaceId) {
      return (await engine()).move(id, spaceId)
    },
    async delete(id) {
      await (await engine()).delete(id)
    },
    async recover(id, title, content, imageIds, spaceId) {
      return (await engine()).recover(id, title, content, imageIds, spaceId)
    },
    async addImage(id, bytes, type) {
      await (await engine()).addImage(id, new Int8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength), type)
    },
    async image(id) {
      return (await opened()).images.load(id)
    },
    async pendingNotes() {
      return (await engine()).pendingNotes()
    },
    async spaces() {
      return (await (await engine()).spaces()).map((s) => ({
        id: s.id,
        name: s.name,
        icon: s.icon,
        createdAt: s.createdAt,
        noteCount: s.noteCount,
      }))
    },
    async createSpace(id, name, icon) {
      await (await engine()).createSpace(id, name, icon)
    },
    async updateSpace(id, name, icon) {
      await (await engine()).updateSpace(id, name, icon)
    },
    async deleteSpace(id) {
      return (await engine()).deleteSpace(id)
    },
    async lockKeys() {
      return (await (await engine()).lockKeys()).map((k) => {
        const key: LockKey = {
          id: k.id,
          kind: k.kind as LockKey['kind'],
          salt: k.salt,
          iterations: k.iterations,
          wrapped: k.wrapped,
          createdAt: k.createdAt,
        }
        if (k.credentialId != null) key.credentialId = k.credentialId
        return key
      })
    },
    async replaceLockKeys(keys) {
      await (await engine()).replaceLockKeys(
        keys.map((k) => new LocalLockKey(k.id, k.kind, k.credentialId, k.salt, k.iterations, k.wrapped, k.createdAt)),
      )
    },
    async wipe() {
      const o = await opened()
      await o.engine.wipe()
      await o.images.clear()
    },
    close() {
      void current.then((o) => o && stop(o), () => {})
      current = Promise.resolve(null)
    },
  }
}

function localNote(n: EngineNote): LocalNote {
  return {
    id: n.id,
    title: n.title,
    content: n.content,
    createdAt: n.createdAt,
    updatedAt: n.updatedAt,
    conflictOf: n.conflictOf ?? null,
    revision: n.revision ?? null,
    dirty: n.dirty,
    spaceId: n.spaceId,
  }
}

function filterArgs(filter: NoteFilter): [string | null, string | null] {
  if (!filter) return [null, null]
  if ('tag' in filter) return ['tag', filter.tag]
  return ['locked' in filter ? 'locked' : 'untagged', null]
}

function status(s: EngineStatus): SyncStatus {
  switch (s.kind) {
    case 'Synced':
      return { kind: 'Synced', at: s.at ?? 0 }
    case 'Failed':
      return { kind: 'Failed', message: s.message ?? '' }
    default:
      return { kind: s.kind as 'NotConnected' | 'Syncing' | 'Offline' | 'SignedOut' }
  }
}

function notice(n: EngineNotice): SyncNotice {
  switch (n.kind) {
    case 'ConflictedCopy':
      return { kind: 'ConflictedCopy', noteId: n.noteId ?? '', copyId: n.copyId ?? '', title: n.title }
    case 'RecoveredCopy':
      return { kind: 'RecoveredCopy', copyId: n.copyId ?? '', title: n.title }
    default:
      return { kind: 'Restored', noteId: n.noteId ?? '', title: n.title }
  }
}
