import { ApiError, NetworkError, UNEXPECTED, unexpectedMessage } from '../api/client'
import { notes } from '../api/notes'
import { canonical, parse } from '../editor/markdown'
import { mergeBody } from '../editor/merge'
import type { MutationResult, Note } from '../api/types'
import { imageIds } from '../images/ref'
import { LockedError, UnsupportedLockError, decryptBody, encryptBody, isLocked, isSupportedLock } from '../locks/crypto'
import { lockSession } from '../locks/session'
import type { SaveState } from '../ui/status-pill'
import type { Saver, SaverHooks, SaverInit } from './source'
import { DEFAULT_SPACE } from './default-space'

export type Fields = { title: string; content: string }

export type Problem =
  /** Both sides changed the content (or the title) differently. */
  | { kind: 'conflict'; current: Opened }
  /** The note was deleted elsewhere; our edits have nowhere to go. */
  | { kind: 'deleted' }
  /** Network or server failure; retrying with backoff. */
  | { kind: 'offline' }
  /** The server rejected the save for good (e.g. note too large); [onDevice]: this device couldn't store it. */
  | { kind: 'rejected'; message: string; onDevice?: boolean }
  /** A locked note has edits, but locked notes were locked again before they could be encrypted. */
  | { kind: 'unlock' }
  /** Something other than notesd answered (a proxy, a portal), or notesd said something unknown; kept, retried on Retry. */
  | { kind: 'unexpected'; message: string }
  /** The server needs a newer notesd: kept until this page is updated (or Retry). */
  | { kind: 'upgrade' }

export type Snapshot = {
  state: SaveState
  problem: Problem | null
  /** False until the server has the note (new notes POST on first save). */
  created: boolean
  updatedAt: number | null
  /** Unreachable-server failures since the last save that landed; Retry doesn't reset it. */
  failures: number
  /** The body is (to be) encrypted. */
  locked: boolean
  /** A picture couldn't be added, or the lock changed: shown until dismissed. */
  imageError: string | null
  /** Images are being uploaded or re-encrypted for a lock change. */
  imagesBusy: boolean
  /** The space the note is (to be) in. */
  spaceId: string
  /** The account that last changed it, when that wasn't this one here (§7.9). */
  updatedBy: number | null
  /** Gone from the store underneath the editor; [copy] holds edits that were still unsaved. */
  gone: { copy: Saver | null } | null
  /** Edits from another device were merged in (§6.2) and undoMerge() can still split them apart. */
  merged: boolean
  /** The latest conflicted copy a merge made by itself, to tell the user about once ([at] tells repeats apart). */
  split: { title: string; at: number } | null
}

/** One save of the note, wherever it goes. */
export type Save = {
  /** Plain text, as the editor shows it. */
  fields: Fields
  /** What goes over the wire: the content encrypted when locked. Fixed once, so retries send the same bytes. */
  wire: Fields
  /** A locked note's images, which the server can't read from the ciphertext (§7.6). */
  imageIds?: string[]
  locked: boolean
  spaceId: string
}

type Pending = Save & { operationId: string; create: boolean; baseRevision: number }

/** A note from the server with a locked body opened (§10.1). */
export type Opened<N = Note> = { note: N; locked: boolean; envelope: string | null }

/**
 * Decrypts a locked note's body with the session key. Throws UnsupportedLockError when a newer
 * notesd locked it (whether or not locked notes are unlocked), LockedError when locked notes are
 * locked (or the body doesn't open).
 */
export async function openNote<N extends { content: string } = Note>(note: N): Promise<Opened<N>> {
  if (!isLocked(note.content)) return { note, locked: false, envelope: null }
  if (!isSupportedLock(note.content)) throw new UnsupportedLockError()
  const key = lockSession.key()
  if (!key) throw new LockedError('Locked notes are locked')
  return { note: { ...note, content: await decryptBody(key, note.content) }, locked: true, envelope: note.content }
}

/**
 * The editor saves canonical Markdown, so the server's text is compared in
 * that form too: "* a" stored elsewhere isn't a local change to "- a".
 */
export function canonicalFields(f: Fields): Fields {
  return { title: f.title, content: canonical(f.content) }
}

// Tests shorten these; nothing else should touch them.
export const timing = { debounceMs: 1500, retryBaseMs: 2000, retryMaxMs: 30_000, undoDeleteMs: 5000, quietMs: 2000 }

/** Codes for a save the server will never take as it is: retrying the same thing can't help. */
const PERMANENT = new Set([
  'invalid_request',
  'hash_mismatch',
  'image_too_large',
  'unsupported_image',
  'space_default',
  'locked_in_exposed_space',
])

/** How many bytes a keepalive request may carry (browsers cap the total at 64 KB). */
const KEEPALIVE_LIMIT = 60_000

/** What every saver does, wherever the note is stored: the editor's side, locks, images and spaces. */
export abstract class SaverCore implements Saver {
  readonly id: string
  protected created: boolean
  /** A new note with these values (e.g. just the selected tag) is still blank. */
  protected blank: Fields
  /** Last values the store acknowledged: the base for merges and for "nothing changed". */
  protected base: Fields
  /** Whether the body is encrypted, here and as last stored. */
  protected locked: boolean
  protected baseLocked: boolean
  /** The note's space, here and as last stored (§7.7). */
  protected space: string
  protected baseSpace: string
  /** Who last changed it, when it wasn't this account here; null once this side saves. */
  protected updatedBy: number | null
  /** The stored envelope and the text it holds: unchanged text is sent as the same envelope, never re-encrypted. */
  protected sealed: { plain: string; envelope: string } | null
  /** The editor's current values; read lazily so serialization happens per save, not per keystroke. */
  protected read: () => Fields
  protected dirty = false
  private attached = 0
  protected state: SaveState = 'saved'
  protected problem: Problem | null = null
  protected updatedAt: number | null
  protected inflight: Promise<void> | null = null
  protected debounce: ReturnType<typeof setTimeout> | undefined
  protected failures = 0
  protected waitingForUnlock = false
  protected gone: Snapshot['gone'] = null
  protected merged = false
  protected split: Snapshot['split'] = null
  private imageError: string | null = null
  protected imagesBusy = 0
  /** Re-encrypts (or decrypts) the open note's images before a lock change (images/store.ts). */
  private resealer: ((locked: boolean) => Promise<void>) | null = null
  private listeners = new Set<() => void>()
  private remoteListeners = new Set<(fields: Fields) => void>()
  private snapshot: Snapshot

  constructor(
    init: SaverInit,
    protected hooks: SaverHooks = {},
  ) {
    this.id = init.id
    this.created = init.revision !== undefined
    this.updatedAt = init.updatedAt ?? null
    // A note not stored yet has nothing acknowledged: empty base.
    this.base = this.created ? canonicalFields(init.fields) : { title: '', content: '' }
    this.locked = init.locked ?? false
    this.baseLocked = this.created && this.locked
    this.space = init.spaceId ?? DEFAULT_SPACE
    this.baseSpace = this.space
    this.updatedBy = init.updatedBy ?? null
    this.sealed = init.envelope ? { plain: this.base.content, envelope: init.envelope } : null
    const fields = { ...init.fields }
    this.blank = init.blank ?? { title: '', content: '' }
    this.read = () => fields
    this.snapshot = this.makeSnapshot()
  }

  /** Runs one save cycle; flush() keeps it to one at a time. */
  protected abstract run(): Promise<void>
  /** Saving waits for the user (or a sign-in, an unlock). */
  protected abstract blocked(): boolean
  abstract canMove(): boolean
  abstract hasUnsavedWork(): boolean
  abstract savedRevision(): Promise<number | null>
  abstract retryNow(): void
  abstract resume(): void
  abstract flushOnUnload(): void
  abstract keepMine(): Saver | null
  abstract takeTheirs(): void
  abstract recover(): Saver | null
  abstract undoMerge(): Promise<string | null>
  abstract delete(keepalive?: boolean): Promise<'deleted' | 'changed'>

  // --- for the editor -------------------------------------------------------

  getSnapshot = (): Snapshot => this.snapshot

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  /** Called when the note's text changes underneath the editor (merge, "Use theirs"). */
  onRemoteChange(listener: (fields: Fields) => void): () => void {
    this.remoteListeners.add(listener)
    return () => this.remoteListeners.delete(listener)
  }

  /** The values the editor should start from. */
  fields(): Fields {
    return this.read()
  }

  /** Lets the editor own the live values. Returns a detach function that freezes them. */
  attach(read: () => Fields): () => void {
    this.read = read
    this.attached++
    return () => {
      this.attached--
      if (this.read !== read) return
      const frozen = read()
      this.read = () => frozen
    }
  }

  /** An editor has this note open; it must not be released. */
  isAttached(): boolean {
    return this.attached > 0
  }

  /** Whether the body is (to be) encrypted. */
  isLocked(): boolean {
    return this.locked
  }

  /** Lets the open editor move the note's images along with a lock change. */
  setResealer(fn: (locked: boolean) => Promise<void>): () => void {
    this.resealer = fn
    return () => {
      if (this.resealer === fn) this.resealer = null
    }
  }

  /**
   * Locks or unlocks the note, its images first (§7.6). If they can't be
   * moved, the lock stays as it was and imageError says why.
   */
  async changeLock(locked: boolean): Promise<void> {
    if (this.locked === locked) return
    const reseal = this.resealer
    if (reseal) {
      const ok = await this.withImages(() => reseal(locked))
      if (!ok) return
    }
    this.setLocked(locked)
  }

  protected imageFailure(err: unknown): string {
    return err instanceof Error && err.name === 'ImageError' ? err.message : 'The picture couldn’t be uploaded. Check the connection and try again.'
  }

  /** Runs image work, showing it as busy; a failure becomes imageError. */
  async withImages(work: () => Promise<void>): Promise<boolean> {
    this.imagesBusy++
    this.imageError = null
    this.set(this.state)
    try {
      await work()
      return true
    } catch (err) {
      this.imageError = this.imageFailure(err)
      return false
    } finally {
      this.imagesBusy--
      this.set(this.state)
    }
  }

  /**
   * Adds Markdown on its own line at the end of the note while no editor has
   * it open: a picture whose upload finished after its note was closed.
   * False if an editor has it open again, which owns the text.
   */
  appendClosed(markdown: string): boolean {
    if (this.isAttached()) return false
    const { title, content } = this.read()
    const next = { title, content: appendBlock(content, markdown) }
    this.read = () => next
    this.edit()
    return true
  }

  dismissImageError() {
    this.imageError = null
    this.set(this.state)
  }

  /** The space the note is (to be) in. */
  spaceId(): string {
    return this.space
  }

  /** Moves the note to another space: saved like an edit. */
  moveTo(spaceId: string) {
    if (this.space === spaceId) return
    this.space = spaceId
    this.dirty = true
    this.set(this.state === 'saved' ? 'unsaved' : this.state)
    void this.flush()
  }

  /** Locks or unlocks the note: saved like an edit, encrypted with the session key. */
  setLocked(locked: boolean) {
    if (this.locked === locked) return
    this.locked = locked
    this.dirty = true
    this.set(this.state === 'saved' ? 'unsaved' : this.state)
    void this.flush()
  }

  /** The editor changed something. */
  edit() {
    this.dirty = true
    if (this.problem?.kind === 'conflict' || this.problem?.kind === 'deleted') {
      // Edits pile up locally until the user resolves; the choice uses the latest text.
      return
    }
    if (this.state !== 'saving' && this.problem?.kind !== 'offline') this.set('unsaved')
    clearTimeout(this.debounce)
    this.debounce = setTimeout(() => void this.flush(), timing.debounceMs)
  }

  /** Edits made since the last save went out. */
  hasUnsentEdits(): boolean {
    return this.dirty
  }

  /** True for a new note nobody typed into: it can be dropped without a trace. */
  isBlankDraft(): boolean {
    return this.isBlank(this.read())
  }

  protected isBlank(f: Fields): boolean {
    return !this.created && f.title.trim() === this.blank.title.trim() && f.content.trim() === this.blank.content.trim()
  }

  // --- saving ---------------------------------------------------------------

  /** Saves now (skipping the debounce) and resolves when this note is idle. */
  async flush(): Promise<void> {
    clearTimeout(this.debounce)
    if (this.inflight) {
      await this.inflight
      // The in-flight save may have left new edits behind.
      if (this.dirty && !this.blocked()) await this.flush()
      return
    }
    if (this.blocked()) return
    this.inflight = this.run().finally(() => {
      this.inflight = null
    })
    await this.inflight
  }

  protected unchanged(fields: Fields): boolean {
    return (
      this.created &&
      fields.title === this.base.title &&
      fields.content === this.base.content &&
      this.locked === this.baseLocked &&
      this.space === this.baseSpace
    )
  }

  /** The session key for a locked save; without one, saving waits for an unlock. */
  protected lockKey(): CryptoKey | null {
    const key = lockSession.key()
    if (!key) {
      this.waitingForUnlock = true
      this.problem = { kind: 'unlock' }
    }
    return key
  }

  /** The next save of an unlocked note, built synchronously (for page unload). */
  protected plainSave(): Save | null {
    const fields = this.read()
    this.dirty = false
    if (this.unchanged(fields) || this.isBlank(fields)) return null
    return { fields, wire: fields, locked: false, spaceId: this.space }
  }

  /** The next save, its body encrypted if the note is locked; null when there's nothing to send or no key. */
  protected async nextSave(): Promise<Save | null> {
    if (!this.locked) return this.plainSave()
    const key = this.lockKey()
    if (!key) return null
    const fields = this.read()
    this.dirty = false
    if (this.unchanged(fields) || this.isBlank(fields)) return null
    const envelope = await this.envelope(key, fields.content)
    const wire = { title: fields.title, content: envelope }
    return { fields, wire, imageIds: imageIds(fields.content), locked: true, spaceId: this.space }
  }

  protected envelope(key: CryptoKey, content: string): Promise<string> {
    return this.sealed?.plain === content ? Promise.resolve(this.sealed.envelope) : encryptBody(key, content)
  }

  protected pushRemote(fields: Fields) {
    const frozen = { ...fields }
    // An attached editor updates what its reader returns synchronously in
    // the listener; with no editor, the pushed values become the local ones.
    if (this.remoteListeners.size === 0) this.read = () => frozen
    for (const l of this.remoteListeners) l(frozen)
  }

  dispose() {
    clearTimeout(this.debounce)
    this.listeners.clear()
    this.remoteListeners.clear()
  }

  protected set(state: SaveState) {
    this.state = state
    this.snapshot = this.makeSnapshot()
    for (const l of this.listeners) l()
  }

  private makeSnapshot(): Snapshot {
    return {
      state: this.state,
      problem: this.problem,
      created: this.created,
      updatedAt: this.updatedAt,
      failures: this.failures,
      locked: this.locked,
      imageError: this.imageError,
      imagesBusy: this.imagesBusy > 0,
      spaceId: this.space,
      updatedBy: this.updatedBy,
      gone: this.gone,
      merged: this.merged,
      split: this.split,
    }
  }
}

/**
 * Owns saving for one note, independent of React so saves outlive the editor
 * (closing the sheet, the session-expired dialog). Saves are serialized: one
 * request in flight, and edits made meanwhile go out afterwards against the
 * revision it returned.
 */
export class NoteSaver extends SaverCore {
  private conflictOf: string | undefined
  private revision: number
  /** Kept across retries so a lost response replays instead of applying twice. */
  private pending: Pending | null = null
  private retryTimer: ReturnType<typeof setTimeout> | undefined
  private attempts = 0
  private waitingForAuth = false
  /** The last automatic body merge, for undoMerge(); in memory only, so for this tab's life. */
  private undo: { keep: Fields; copy: Fields; merged: Fields } | null = null

  constructor(init: SaverInit, hooks: SaverHooks = {}) {
    super(init, hooks)
    this.conflictOf = init.conflictOf
    this.revision = init.revision ?? 0
  }

  /**
   * Whether a move would be saved: not while a conflict, a delete elsewhere or a refusal
   * holds every save until the user acts, nor for a new note with nothing in it.
   */
  canMove(): boolean {
    const k = this.problem?.kind
    return k !== 'conflict' && k !== 'deleted' && k !== 'rejected' && !this.isBlankDraft()
  }

  /** Whether closing the page now could lose something. */
  hasUnsavedWork(): boolean {
    return this.dirty || this.pending !== null || this.inflight !== null || this.imagesBusy > 0
  }

  /**
   * Saves now; then the server's revision of exactly what the editor shows,
   * or null if the server doesn't have it (offline, a conflict, a new note
   * nobody typed into).
   */
  async savedRevision(): Promise<number | null> {
    await this.flush()
    if (!this.created || this.dirty || this.pending || this.problem || this.waitingForAuth) return null
    return this.revision
  }

  /** Retry after a failure, now. */
  retryNow() {
    clearTimeout(this.retryTimer)
    this.waitingForAuth = false
    const k = this.problem?.kind
    if (k === 'rejected' || k === 'unexpected' || k === 'upgrade') this.problem = null
    this.attempts = 0
    void this.flush()
  }

  /** Signed in again after a 401, locked notes unlocked again, or back from a portal's sign-in. */
  resume() {
    const k = this.problem?.kind
    if (this.waitingForAuth || k === 'offline' || k === 'unlock' || k === 'unexpected') {
      this.waitingForUnlock = false
      if (this.problem?.kind === 'unlock') this.problem = null
      this.retryNow()
    }
  }

  /** Last-chance save while the page unloads. Fire and forget. */
  flushOnUnload() {
    clearTimeout(this.debounce)
    if (this.blocked() || this.inflight) return
    // A new locked save can't be encrypted synchronously; beforeunload asks the user to stay instead.
    const p = this.pending ?? (this.locked ? null : this.toPending(this.plainSave()))
    if (!p) return
    // The whole body counts, the image list too.
    if (JSON.stringify({ ...p.wire, imageIds: p.imageIds }).length > KEEPALIVE_LIMIT) return
    this.pending = p
    void this.send(p, true).catch(() => undefined)
  }

  protected blocked(): boolean {
    const k = this.problem?.kind
    return (
      k === 'conflict' ||
      k === 'deleted' ||
      k === 'rejected' ||
      k === 'unexpected' ||
      k === 'upgrade' ||
      this.waitingForAuth ||
      this.waitingForUnlock
    )
  }

  private toPending(s: Save | null): Pending | null {
    return s && { ...s, operationId: crypto.randomUUID(), create: !this.created, baseRevision: this.revision }
  }

  private async send(p: Pending, keepalive = false): Promise<MutationResult> {
    let result: MutationResult
    try {
      result = await this.request(p, keepalive)
    } catch (err) {
      if (!(err instanceof ApiError && err.code === 'operation_id_reused')) throw err
      // The id was spent on something else (a reused id from an older app). A fresh one is safe: the base
      // revision turns a duplicate into a conflict or a no-op. Once only.
      p.operationId = crypto.randomUUID()
      result = await this.request(p, keepalive)
    }
    // Something in front of notesd can answer 2xx with its own JSON: committing that would lose the edit.
    if (!this.isResult(result)) throw new ApiError(200, { error: UNEXPECTED, message: unexpectedMessage(200) })
    return result
  }

  private isResult(r: unknown): r is MutationResult {
    if (typeof r !== 'object' || r === null) return false
    const m = r as Partial<Record<keyof MutationResult, unknown>>
    return (
      m.id === this.id &&
      (m.status === 'created' || m.status === 'updated' || m.status === 'unchanged') &&
      typeof m.revision === 'number' &&
      typeof m.changeSeq === 'number' &&
      typeof m.updatedAt === 'number'
    )
  }

  // Moving into or out of a shared space is an update like any other: the server moves it between files (§7.9).
  private request(p: Pending, keepalive: boolean): Promise<MutationResult> {
    return p.create
      ? notes.create(
          {
            operationId: p.operationId,
            id: this.id,
            ...p.wire,
            spaceId: p.spaceId,
            ...(p.imageIds && { imageIds: p.imageIds }),
            ...(this.conflictOf !== undefined && { conflictOf: this.conflictOf }),
          },
          keepalive,
        )
      : notes.update(
          this.id,
          {
            operationId: p.operationId,
            baseRevision: p.baseRevision,
            ...p.wire,
            spaceId: p.spaceId,
            ...(p.imageIds && { imageIds: p.imageIds }),
          },
          keepalive,
        )
  }

  protected async run(): Promise<void> {
    for (;;) {
      // Sequential on purpose, like the request below.
      // oxlint-disable-next-line no-await-in-loop
      const p = this.pending ?? this.toPending(await this.nextSave())
      if (!p) {
        if (this.waitingForUnlock) this.set('failed')
        else if (!this.dirty) this.set('saved')
        return
      }
      this.pending = p
      this.set('saving')
      let result: MutationResult
      try {
        // Sequential on purpose: one request per note in flight.
        // oxlint-disable-next-line no-await-in-loop
        result = await this.send(p)
      } catch (err) {
        this.fail(err)
        return
      }
      this.pending = null
      this.attempts = 0
      this.failures = 0
      this.created = true
      this.revision = result.revision
      this.updatedBy = null
      this.base = p.fields
      this.baseLocked = p.locked
      this.sealed = p.locked ? { plain: p.fields.content, envelope: p.wire.content } : null
      // A space deleted elsewhere: the server filed the note in the default instead.
      const space = result.spaceId ?? p.spaceId
      if (this.space === p.spaceId) this.space = space
      this.baseSpace = space
      this.updatedAt = result.updatedAt
      this.problem = null
      // Moved since this save went: the move's own save reports the note, in its new space.
      if (this.space === space) this.hooks.saved?.(this.id, p.fields, result, p.locked, space)
      if (!this.dirty) {
        this.set('saved')
        return
      }
    }
  }

  private fail(err: unknown) {
    if (err instanceof ApiError && err.status === 409 && err.code === 'revision_conflict' && err.current) {
      this.pending = null
      // The unsent snapshot is the local side of the merge; edits since then are still in read().
      this.dirty = true
      void this.mergeRemote(err.current)
      return
    }
    if (err instanceof ApiError && err.status === 409 && err.code === 'note_deleted') {
      this.pending = null
      this.dirty = true
      this.problem = { kind: 'deleted' }
      this.set('conflict')
      return
    }
    if (err instanceof ApiError && err.status === 401 && err.code === 'unauthenticated') {
      // Same request again after sign-in, so a save that did land replays.
      this.waitingForAuth = true
      this.set('failed')
      return
    }
    if (err instanceof ApiError && err.code === 'upgrade_required') {
      // Held like a sign-in: the same request goes once this page is a notesd the server takes.
      this.problem = { kind: 'upgrade' }
      this.set('failed')
      return
    }
    // Only transport failures, server errors and rate limits are worth retrying on their own.
    if (err instanceof NetworkError || (err instanceof ApiError && (err.status >= 500 || err.status === 429))) {
      this.problem = { kind: 'offline' }
      this.failures++
      this.set('failed')
      const backoff = Math.min(timing.retryBaseMs * 2 ** this.attempts, timing.retryMaxMs)
      const delay = err instanceof ApiError && err.retryAfter ? Math.max(backoff, err.retryAfter * 1000) : backoff
      this.attempts++
      clearTimeout(this.retryTimer)
      this.retryTimer = setTimeout(() => void this.flush(), delay)
      return
    }
    // Refused for good. A 403 (CSRF/origin check) won't fix itself either: an ended session fails
    // with 401 before that check runs.
    if (err instanceof ApiError && (PERMANENT.has(err.code) || err.code === 'csrf_failed')) {
      this.pending = null
      this.dirty = true
      this.problem = { kind: 'rejected', message: err.message }
      this.set('failed')
      return
    }
    // Anything else may not even be notesd talking (a portal's sign-in page): the save is kept, as it was.
    this.dirty = true
    this.problem = {
      kind: 'unexpected',
      message: err instanceof ApiError ? unexpectedMessage(err.status) : 'The save didn’t go through. Try again.',
    }
    this.set('failed')
  }

  /** Opens the server's version (decrypting a locked body), then merges with it. */
  private async mergeRemote(current: Note) {
    let opened: Opened
    try {
      opened = await openNote(current)
    } catch (err) {
      if (err instanceof UnsupportedLockError) {
        // Locked by a newer notesd elsewhere: nothing here can merge into it, or should overwrite it.
        this.problem = { kind: 'rejected', message: err.message }
        this.set('failed')
        return
      }
      // Locked again meanwhile (or a body that won't open): wait for an unlock, then retry and conflict again.
      this.waitingForUnlock = true
      this.problem = { kind: 'unlock' }
      this.set('failed')
      return
    }
    this.merge(opened)
  }

  /**
   * §6.1: take each field from whichever side changed it, and a body both changed block by block (§6.2).
   * What doesn't merge goes to a conflicted copy by itself, as on the phones; only a body that merged
   * nothing (or a locked one) asks the user.
   */
  private merge(remote: Opened) {
    const current = remote.note
    // Undo splits the last merge only: this one may record none.
    this.undo = null
    this.merged = false
    // The latest local text, including edits typed after the rejected request.
    const local = this.read()
    const base = this.base
    const server = canonicalFields(current)
    const pick = (key: keyof Fields): string | null => {
      const mine = local[key]
      const theirs = server[key]
      if (mine === base[key]) return theirs
      if (theirs === base[key] || theirs === mine) return mine
      return null
    }
    const title = pick('title')
    let content = pick('content')
    if (current.deletedAt !== null) {
      this.problem = { kind: 'deleted' }
      this.set('conflict')
      return
    }
    // Locking is part of the body: a side that changed it wins, like a field.
    const locked = this.locked === this.baseLocked ? remote.locked : this.locked
    // So is the space, except that the server's wins when both moved it.
    const space = this.space === this.baseSpace || current.spaceId !== this.baseSpace ? current.spaceId : this.space
    // Locked bodies never merge block by block: either side's lock state could be the wrong one to keep.
    const body = content === null && !this.locked && !this.baseLocked && !remote.locked ? mergeBody(base.content, local.content, server.content) : null
    if (body?.kind === 'clean') content = body.content
    if (content === null && body?.kind !== 'partial') {
      this.problem = { kind: 'conflict', current: remote }
      this.set('conflict')
      return
    }
    const mySpace = this.space
    this.adopt(remote)
    this.locked = locked
    this.space = space
    if (title === null || body?.kind === 'partial') {
      // Both get every change that merged, so the two differ only where both sides changed the same thing.
      const theirs = body?.kind === 'partial' ? body.theirs : (content as string)
      const mine = body?.kind === 'partial' ? body.mine : (content as string)
      const copy = this.copy(' (Conflicted copy)', this.id, { title: title ?? local.title, content: mine }, mySpace)
      this.split = { title: copy.fields().title, at: Date.now() }
      this.pushRemote({ title: title ?? server.title, content: theirs })
    } else {
      const merged = { title, content: content as string }
      if (merged.title !== local.title || merged.content !== local.content) this.pushRemote(merged)
      if (body?.kind === 'clean') {
        this.undo = { keep: { title: current.title, content: current.content }, copy: local, merged }
        this.merged = true
      }
    }
    this.dirty = true
    void Promise.resolve().then(() => this.flush())
  }

  /** Splits the last automatic merge back apart: the note as the server had it, unless edited since, and ours as a copy. */
  async undoMerge(): Promise<string | null> {
    const undo = this.undo
    if (!undo) return null
    this.undo = null
    this.merged = false
    const now = this.read()
    if (now.title === undo.merged.title && canonical(now.content) === undo.merged.content) {
      this.pushRemote(undo.keep)
      this.dirty = true
    }
    const copy = this.copy(' (Conflicted copy)', this.id, undo.copy)
    this.set(this.state === 'saved' && this.dirty ? 'unsaved' : this.state)
    void this.flush()
    return copy.fields().title
  }

  /** Make the server's version our base. */
  private adopt(remote: Opened) {
    const current = remote.note
    this.revision = current.revision
    this.base = canonicalFields(current)
    this.baseLocked = remote.locked
    this.locked = remote.locked
    this.baseSpace = current.spaceId
    this.space = current.spaceId
    this.sealed = remote.envelope ? { plain: this.base.content, envelope: remote.envelope } : null
    this.updatedAt = current.updatedAt
    this.updatedBy = current.updatedBy ?? null
    this.created = true
    this.problem = null
  }

  // --- resolving conflicts --------------------------------------------------

  /** "Keep mine": the local text becomes a new note; this one shows the server's version. */
  keepMine(): NoteSaver | null {
    const problem = this.problem
    if (problem?.kind !== 'conflict') return null
    const copy = this.copy(' (Conflicted copy)', this.id)
    this.takeTheirs()
    return copy
  }

  /** "Use theirs": drop local edits. */
  takeTheirs() {
    const problem = this.problem
    if (problem?.kind !== 'conflict') return
    this.adopt(problem.current)
    this.dirty = false
    this.pushRemote({ title: problem.current.note.title, content: problem.current.note.content })
    this.set('saved')
  }

  /** "Keep as new note" after the note was deleted elsewhere. */
  recover(): NoteSaver | null {
    if (this.problem?.kind !== 'deleted') return null
    return this.copy(' (Recovered copy)')
  }

  private copy(suffix: string, conflictOf?: string, local: Fields = this.read(), spaceId = this.space): NoteSaver {
    const copy = new NoteSaver(
      {
        id: crypto.randomUUID(),
        fields: { title: `${local.title.trim() || 'Untitled'}${suffix}`, content: local.content },
        ...(conflictOf !== undefined && { conflictOf }),
        // A copy of a locked note stays locked, in the same space.
        locked: this.locked,
        spaceId,
      },
      this.hooks,
    )
    this.hooks.copied?.(copy)
    copy.dirty = true
    void copy.flush()
    return copy
  }

  // --- deleting ---------------------------------------------------------------

  /**
   * Deletes the note once any in-flight save has landed. Resolves to 'changed'
   * when someone else edited it first (the editor now shows their version).
   */
  async delete(keepalive = false): Promise<'deleted' | 'changed'> {
    clearTimeout(this.debounce)
    clearTimeout(this.retryTimer)
    // Unloading can't wait: send against the revision we have.
    if (this.inflight && !keepalive) await this.inflight.catch(() => undefined)
    this.dirty = false
    this.pending = null
    if (!this.created) return 'deleted'
    try {
      await notes.remove(this.id, { operationId: crypto.randomUUID(), baseRevision: this.revision }, keepalive)
      return 'deleted'
    } catch (err) {
      if (err instanceof ApiError && err.code === 'note_deleted') return 'deleted'
      if (err instanceof ApiError && err.code === 'revision_conflict' && err.current) {
        const remote = await openNote(err.current).catch(() => null)
        if (!remote) throw err
        this.adopt(remote)
        this.pushRemote({ title: remote.note.title, content: remote.note.content })
        this.set('saved')
        return 'changed'
      }
      throw err
    }
  }

  override dispose() {
    clearTimeout(this.retryTimer)
    super.dispose()
  }
}


/**
 * content with block on its own line at the end. A code fence left open at
 * the end would take the line in as code: it is closed first.
 */
export function appendBlock(content: string, block: string): string {
  const body = content.trimEnd()
  const next = `${body ? `${body}\n\n` : ''}${block}\n`
  if (outsideCode(next, block)) return next
  const fence = /^ {0,3}(`{3,}|~{3,})/
  const opener = body.split('\n').findLast((line) => fence.test(line))
  const marker = opener?.match(fence)?.[1]
  return marker ? `${body}\n${marker}\n\n${block}\n` : next
}

/** Whether block is still its own image (or text) in markdown, not swallowed by a raw code block. */
function outsideCode(markdown: string, block: string): boolean {
  let raw = false
  parse(markdown).descendants((node) => {
    if (node.type.name === 'rawBlock' && String(node.attrs.source).includes(block)) raw = true
  })
  return !raw
}
