import { useSyncExternalStore } from 'react'
import type { LockKey, MutationResult, Note, NoteFilter, NoteList, SearchResult, SearchScope, Space, TagCount } from '../api/types'
import type { SyncNotice, SyncStatus } from '../offline/protocol'
import { onlineSource } from './online-source'
import type { Fields, Snapshot } from './saver'

// Where the notes screens read and write: the server directly, or a database
// on this device that syncs with it. Screens only see this.

export type SaverInit = {
  id: string
  /** Plain text: a locked note's body already decrypted. */
  fields: Fields
  /** Set when the note is stored already. */
  revision?: number
  updatedAt?: number
  conflictOf?: string
  /** A new note with these values (e.g. just the selected tag) is still blank. */
  blank?: Fields
  locked?: boolean
  /** The locked body as stored. */
  envelope?: string | null
  spaceId?: string
}

export type SaverHooks = {
  /** A save landed: update the list. */
  saved?: (id: string, fields: Fields, result: MutationResult, locked: boolean, spaceId: string) => void
  /** A new note was made by resolving a conflict. */
  copied?: (saver: Saver) => void
}

/** Notes changed here that sync can't get to the server; [error] when the server refused them. */
export type Unsynced = { pending: number; error: string | null }

/** [pending]: notes changed here and not yet on the server. */
export type SyncState = { status: SyncStatus; pending: number; lastSyncAt: number | null }

/** Saving for one open (or recently open) note, independent of React so saves outlive the editor. */
export interface Saver {
  readonly id: string

  // The editor's view of it.
  getSnapshot: () => Snapshot
  subscribe: (listener: () => void) => () => void
  /** The note's text changed underneath the editor (a merge, "Use theirs"). */
  onRemoteChange(listener: (fields: Fields) => void): () => void
  fields(): Fields
  /** The editor reads its text through [read] while attached. */
  attach(read: () => Fields): () => void
  isAttached(): boolean
  edit(): void
  flush(): Promise<void>
  /** Flushes, then the revision the server has, or null when it has none of this. */
  savedRevision(): Promise<number | null>
  retryNow(): void
  /** Something that blocked saving may have cleared (focus, online, signed in, unlocked). */
  resume(): void
  /** The page is going away: send what can still go. */
  flushOnUnload(): void
  hasUnsavedWork(): boolean
  hasUnsentEdits(): boolean
  isBlankDraft(): boolean

  // Locks, images and spaces.
  isLocked(): boolean
  setLocked(locked: boolean): void
  changeLock(locked: boolean): Promise<void>
  setResealer(fn: (locked: boolean) => Promise<void>): () => void
  /** Runs image work, showing it as busy; a failure becomes Snapshot.imageError and false. */
  withImages(work: () => Promise<void>): Promise<boolean>
  /** Adds Markdown at the end while no editor has the note open; false if one does. */
  appendClosed(markdown: string): boolean
  dismissImageError(): void
  spaceId(): string
  canMove(): boolean
  moveTo(spaceId: string): void

  // A conflict or a delete elsewhere (Snapshot.problem).
  keepMine(): Saver | null
  takeTheirs(): void
  recover(): Saver | null

  delete(keepalive?: boolean): Promise<'deleted' | 'changed'>
  dispose(): void
}

export interface NotesSource {
  /** A page of live notes, newest first; [cursor] from the page before. */
  list(filter: NoteFilter, space: string, cursor?: string): Promise<NoteList>
  /** Rejects with an ApiError 404 or 410 when there's no such note. */
  get(id: string): Promise<Note>
  /** The tags and locked notes in [space], for the sidebar. */
  filters(space: string, signal?: AbortSignal): Promise<{ tags: TagCount[]; locked: number }>
  /** In one space, the first page also counts the matches in every other space. */
  search(
    q: string,
    filter: NoteFilter,
    scope: SearchScope,
    offset: number,
    signal: AbortSignal,
  ): Promise<{ results: SearchResult[]; hasMore: boolean; otherSpaces?: number }>
  saver(init: SaverInit, hooks: SaverHooks): Saver
  spaces: {
    list(): Promise<Space[]>
    /** Creates (a new id) or renames; without icon, it stays as it is. */
    put(id: string, name: string, icon?: string): Promise<Space>
    /** Only an empty space: an ApiError space_not_empty otherwise. */
    remove(id: string): Promise<void>
  }
  /** Note pictures' files, by id. */
  images: {
    /** Rejects with an ApiError 404 when there's no such image. */
    get(id: string, signal?: AbortSignal): Promise<Blob>
    /** Idempotent: the id is the bytes' SHA-256. */
    put(id: string, bytes: Uint8Array<ArrayBuffer>, type: string): Promise<void>
    /** Where an <img> can load a plain image straight from; none when they're kept on this device. */
    url?: (id: string) => string
  }
  /** The locked-notes keyring. */
  keyring: {
    load(): Promise<LockKey[]>
    /** After a change made here: a copy on this device is in use offline before sync brings it. */
    changed(keys: readonly LockKey[]): Promise<void>
  }
  /** Calls [listener] whenever what was fetched may be out of date. Returns the unsubscribe function. */
  onStale(listener: () => void): () => void
  /** Only when notes are kept on this device and synced in the background. */
  sync?: {
    /** Null while nothing is stuck: sync is meant to go unnoticed. Returns the unsubscribe function. */
    onUnsynced(listener: (unsynced: Unsynced | null) => void): () => void
    /** Everything about sync, Syncing included, for Settings. Returns the unsubscribe function. */
    onState(listener: (state: SyncState) => void): () => void
    /** What sync did to keep both sides of a clash. Returns the unsubscribe function. */
    onNotice(listener: (notice: SyncNotice) => void): () => void
    now(): void
  }
}

// The server's until a sign-in finds this browser keeps the user's notes (offline/device.ts).
let current: NotesSource = onlineSource
let version = 0
const listeners = new Set<() => void>()

export function setNotesSource(source: NotesSource) {
  if (source === current) return
  current = source
  version++
  for (const l of listeners) l()
}

export function notesSource(): NotesSource {
  return current
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** Calls [listener] after every change of source. Returns the unsubscribe function. */
export function onNotesSourceChange(listener: () => void): () => void {
  return subscribe(listener)
}

export function useNotesSource(): NotesSource {
  return useSyncExternalStore(subscribe, notesSource)
}

/** Moves on with every change of source, for keying the screens: they remount on the new one. */
export function useNotesSourceVersion(): number {
  return useSyncExternalStore(subscribe, () => version)
}
