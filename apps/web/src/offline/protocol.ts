import type { LockKey, NoteFilter, SearchResult, SearchScope, Space, TagCount } from '../api/types'

// What tabs ask of the engine worker. Everything here
// crosses postMessage, so plain data only.

/** A note as this device has it. */
export type LocalNote = {
  id: string
  title: string
  content: string
  createdAt: number
  updatedAt: number
  conflictOf: string | null
  /** Null until the server has the note. */
  revision: number | null
  /** Changed here since the last sync. */
  dirty: boolean
  spaceId: string
}

export type LocalListItem = {
  id: string
  title: string
  preview: string
  createdAt: number
  updatedAt: number
  locked: boolean
  /** Null until the server has the note. */
  revision: number | null
  spaceId: string
}

export type SyncOutcome = 'Done' | 'NotConnected' | 'Offline' | 'SignedOut' | 'Failed'

export type SyncStatus =
  | { kind: 'NotConnected' | 'Syncing' | 'Offline' | 'SignedOut' }
  | { kind: 'Synced'; at: number }
  | { kind: 'Failed'; message: string }

/** What sync did to keep both sides of a clash (§6.2, §6.4). */
export type SyncNotice =
  | { kind: 'ConflictedCopy'; noteId: string; copyId: string; title: string }
  | { kind: 'RecoveredCopy'; copyId: string; title: string }
  | { kind: 'Restored'; noteId: string; title: string }

export type SaveNote = {
  id: string
  title: string
  content: string
  /** The stored text the editor's grew from; a sync change since is merged (§6.1). */
  basedOn?: { title: string; content: string }
  /** A locked note's images, read before encrypting (§7.6). */
  imageIds?: string[]
  /** Where a new note goes; an existing one stays put. */
  spaceId: string
}

export type EngineApi = {
  /** First call from every tab. Switches databases when the user differs from the open one. */
  open(user: string, csrfToken: string): Promise<void>
  /** Sync soon: the tab came back, or the network did. */
  requestSync(): Promise<void>
  /** One cycle now. */
  sync(): Promise<SyncOutcome>
  status(): Promise<SyncStatus>
  /** Null until a sync has ever finished on this database: nothing here to show offline. */
  lastSyncAt(): Promise<number | null>
  get(id: string): Promise<LocalNote | null>
  /** Space null: every space. */
  list(filter: NoteFilter, space: string | null): Promise<LocalListItem[]>
  lockedCount(space: string | null): Promise<number>
  search(query: string, filter: NoteFilter, scope: SearchScope | null, offset: number, limit: number): Promise<SearchResult[]>
  countElsewhere(query: string, space: string): Promise<number>
  tags(space: string | null): Promise<TagCount[]>
  /** Null when nothing was stored: a blank new note, or one deleted meanwhile. */
  save(note: SaveNote): Promise<LocalNote | null>
  move(id: string, spaceId: string): Promise<boolean>
  delete(id: string): Promise<void>
  /** Keeps text whose note was deleted elsewhere, as the new note [id]. Again with the same id does nothing. */
  recover(id: string, title: string, content: string, imageIds: string[] | null, spaceId: string): Promise<string>
  /** A picture added here, already encrypted for a locked note; sync uploads it before the notes showing it. */
  addImage(id: string, bytes: Uint8Array<ArrayBuffer>, type: string): Promise<void>
  /** From this device, else downloaded and kept; null when the server has no such image. */
  image(id: string): Promise<Blob | null>
  pendingNotes(): Promise<number>
  spaces(): Promise<Space[]>
  /** Again with the same id does nothing. */
  createSpace(id: string, name: string, icon: string): Promise<void>
  /** Icon undefined keeps the current one. */
  updateSpace(id: string, name: string, icon?: string): Promise<void>
  /** False for the default space or one with notes. */
  deleteSpace(id: string): Promise<boolean>
  lockKeys(): Promise<LockKey[]>
  replaceLockKeys(keys: LockKey[]): Promise<void>
  wipe(): Promise<void>
}

/** Every call is safe to run twice: a tab resends the unanswered ones when the engine moves to another tab. */
export type Method = keyof EngineApi

export type EngineEvent =
  | { type: 'status'; status: SyncStatus }
  /** A write committed, from sync or any tab. */
  | { type: 'change' }
  | { type: 'notice'; notice: SyncNotice }

/** [from] is the calling tab, so the owner can route the reply back. */
export type Call = { from: string; id: number; method: Method; args: unknown[] }

export type Reply = { to: string; id: number; result?: unknown; error?: string }

export type WorkerMessage = ({ type: 'reply' } & Reply) | { type: 'event'; event: EngineEvent }

export class EngineError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'EngineError'
  }
}
