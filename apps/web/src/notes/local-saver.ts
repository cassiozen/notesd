import type { LocalNote } from '../offline/protocol'
import type { Engine } from '../offline/tab'
import { canonicalFields, openNote, SaverCore, type Fields, type Save } from './saver'
import type { SaverHooks, SaverInit } from './source'

/**
 * Saves to this device's engine. Sync merges and makes conflict copies on its
 * own (§6), so nothing waits for the user but an unlock.
 */
export class LocalSaver extends SaverCore {
  /** The note as stored, a locked body as its envelope: what the next save is based on. */
  private stored: Fields | null
  private deleting = false
  /** One per saver: a retried recovery finds its copy made, not a second one. */
  private recoveryId = crypto.randomUUID()
  private offChange: () => void

  constructor(
    private engine: Engine,
    init: SaverInit,
    hooks: SaverHooks = {},
  ) {
    super(init, hooks)
    this.stored = this.created ? { title: init.fields.title, content: init.envelope ?? init.fields.content } : null
    this.offChange = engine.on((e) => {
      if (e.type === 'change') void this.refresh()
    })
  }

  protected blocked(): boolean {
    return this.waitingForUnlock || this.problem?.kind === 'rejected' || this.gone !== null || this.deleting
  }

  canMove(): boolean {
    return this.problem?.kind !== 'rejected' && this.gone === null && !this.isBlankDraft()
  }

  hasUnsavedWork(): boolean {
    return this.dirty || this.inflight !== null || this.imagesBusy > 0
  }

  /** The server's revision of exactly what the editor shows, or null until sync has sent it. */
  async savedRevision(): Promise<number | null> {
    await this.flush()
    if (!this.created || this.dirty || this.problem) return null
    const n = await this.engine.call('get', this.id)
    return n && !n.dirty ? n.revision : null
  }

  retryNow() {
    if (this.problem?.kind === 'rejected') this.problem = null
    void this.flush()
  }

  resume() {
    if (this.problem?.kind !== 'unlock') return
    this.waitingForUnlock = false
    this.problem = null
    void this.flush()
  }

  /** The call is posted before the page goes; it lands if the engine's tab outlives this one. */
  flushOnUnload() {
    clearTimeout(this.debounce)
    // A locked save can't be encrypted synchronously; beforeunload asks the user to stay instead.
    if (this.blocked() || this.inflight || this.locked) return
    const s = this.plainSave()
    if (s) this.store(s).catch(() => undefined)
  }

  // Sync keeps both sides of a clash by itself: nothing for the user to resolve.
  keepMine() {
    return null
  }

  takeTheirs() {}

  recover() {
    return null
  }

  async delete(): Promise<'deleted'> {
    clearTimeout(this.debounce)
    // Before the wait: the editor's cleanup may flush meanwhile.
    this.deleting = true
    if (this.inflight) await this.inflight.catch(() => undefined)
    this.dirty = false
    if (!this.created) return 'deleted'
    try {
      await this.engine.call('delete', this.id)
    } catch (err) {
      this.deleting = false
      throw err
    }
    return 'deleted'
  }

  protected override imageFailure(err: unknown): string {
    return FULL.test(err instanceof Error ? err.message : String(err))
      ? 'This device is out of storage space for the picture. Free some up, then add it again.'
      : super.imageFailure(err)
  }

  override dispose() {
    this.offChange()
    super.dispose()
  }

  protected async run(): Promise<void> {
    for (;;) {
      // Sequential on purpose: saves of one note go in order.
      // oxlint-disable-next-line no-await-in-loop
      const s = await this.nextSave()
      if (!s) {
        if (this.waitingForUnlock) this.set('failed')
        else if (!this.dirty) this.set('saved')
        return
      }
      this.set('saving')
      let note: LocalNote | null
      try {
        // oxlint-disable-next-line no-await-in-loop
        note = await this.store(s)
      } catch (err) {
        this.dirty = true
        this.problem = { kind: 'rejected', message: storeError(err), onDevice: true }
        this.set('failed')
        return
      }
      if (!note) {
        // oxlint-disable-next-line no-await-in-loop
        if (this.stored) await this.deletedUnderneath(s)
        else this.set('saved')
        return
      }
      // oxlint-disable-next-line no-await-in-loop
      await this.landed(s, note)
      if (!this.dirty) {
        this.set('saved')
        return
      }
    }
  }

  private async store(s: Save): Promise<LocalNote | null> {
    const note = await this.engine.call('save', {
      id: this.id,
      ...s.wire,
      ...(this.stored && { basedOn: this.stored }),
      ...(s.imageIds && { imageIds: s.imageIds }),
      spaceId: s.spaceId,
    })
    // A save files only a new note; an existing one moves on its own.
    if (!note || note.spaceId === s.spaceId || !(await this.engine.call('move', this.id, s.spaceId))) return note
    return { ...note, spaceId: s.spaceId }
  }

  private async landed(s: Save, note: LocalNote) {
    const created = this.created
    this.created = true
    this.updatedAt = note.updatedAt
    // Moved again meanwhile: that move is the next save's.
    if (this.space === s.spaceId) this.space = note.spaceId
    this.baseSpace = note.spaceId
    const merged = note.title !== s.wire.title || note.content !== s.wire.content
    const opened = merged && !this.dirty ? await this.open(note) : null
    if (opened && !this.dirty) {
      // Merged with a change sync brought in: the editor shows the result.
      this.adopt(note, opened)
      this.pushRemote(opened.fields)
    } else {
      // Typed on meanwhile: the next save merges from what this one sent.
      this.stored = s.wire
      this.base = s.fields
      this.baseLocked = s.locked
      this.sealed = s.locked ? { plain: s.fields.content, envelope: s.wire.content } : null
    }
    const shown = opened?.fields ?? s.fields
    const status = created ? 'updated' : 'created'
    const result = { id: this.id, status, revision: note.revision ?? 0, changeSeq: 0, updatedAt: note.updatedAt } as const
    this.hooks.saved?.(this.id, shown, result, opened?.locked ?? s.locked, note.spaceId)
  }

  /** Deleted on another device while typed into here: the typing becomes a new note, as sync does (§6.4). */
  private async deletedUnderneath(s: Save) {
    const edited = s.wire.title !== this.stored?.title || s.wire.content !== this.stored.content
    let copy: LocalSaver | null = null
    if (edited) {
      let n: LocalNote | null
      try {
        const id = await this.engine.call('recover', this.recoveryId, s.wire.title, s.wire.content, s.imageIds ?? null, s.spaceId)
        n = await this.engine.call('get', id)
      } catch (err) {
        this.dirty = true
        this.problem = { kind: 'rejected', message: storeError(err), onDevice: true }
        this.set('failed')
        return
      }
      if (n) {
        copy = new LocalSaver(
          this.engine,
          {
            id: n.id,
            fields: { title: n.title, content: s.fields.content },
            revision: n.revision ?? 0,
            updatedAt: n.updatedAt,
            locked: s.locked,
            envelope: s.locked ? n.content : null,
            spaceId: n.spaceId,
          },
          this.hooks,
        )
        // Typed after the save that found it gone.
        const latest = this.read()
        if (this.dirty && (latest.title !== s.fields.title || latest.content !== s.fields.content)) {
          copy.read = () => latest
          copy.edit()
        }
        this.hooks.copied?.(copy)
      }
    }
    this.dirty = false
    this.gone = { copy }
    this.set('saved')
  }

  /** A write elsewhere (sync, another tab) may have changed this note under an idle editor. */
  private async refresh() {
    if (!this.created || this.dirty || this.inflight || this.gone || this.deleting) return
    let note: LocalNote | null
    try {
      note = await this.engine.call('get', this.id)
    } catch {
      return
    }
    if (this.dirty || this.inflight || this.gone || this.deleting) return
    if (!note) {
      this.gone = { copy: null }
      this.set(this.state)
      return
    }
    const same = note.title === this.stored?.title && note.content === this.stored.content
    if (same && note.spaceId === this.space) return
    const opened = same ? null : await this.open(note)
    if (this.dirty || this.inflight) return
    if (opened) {
      this.adopt(note, opened)
      this.pushRemote(opened.fields)
    } else {
      // Moved, or locked text this session can't open: only the space follows.
      this.space = this.baseSpace = note.spaceId
    }
    this.set(this.state)
  }

  private adopt(note: LocalNote, opened: { fields: Fields; locked: boolean }) {
    this.stored = { title: note.title, content: note.content }
    this.base = canonicalFields(opened.fields)
    this.locked = this.baseLocked = opened.locked
    this.space = this.baseSpace = note.spaceId
    this.sealed = opened.locked ? { plain: this.base.content, envelope: note.content } : null
    this.updatedAt = note.updatedAt
  }

  /** The note as the editor shows it, a locked body decrypted; null when it can't be. */
  private open(note: LocalNote): Promise<{ fields: Fields; locked: boolean } | null> {
    return openNote(note).then(
      ({ note: n, locked }) => ({ fields: { title: n.title, content: n.content }, locked }),
      () => null,
    )
  }
}

// SQLite's SQLITE_FULL, or Cache Storage's QuotaExceededError.
const FULL = /SQLITE_FULL|disk is full|quota/i

function storeError(err: unknown): string {
  const message = err instanceof Error ? err.message : String(err)
  return FULL.test(message) ? 'it’s out of storage space. Free some up, then retry.' : message
}
