import { useEffect, useSyncExternalStore } from 'react'
import type { AgentTrigger, RunStatus } from '../api/agent'
import type { AgentChange, AgentOp } from '../editor/agent-edit'
import type { Overlay } from '../editor/agent-overlays'
import type { Fields } from '../notes/saver'
import { useAgentConfigs } from '../notes/agent-config'
import { useAiSettings } from '../notes/ai-settings'

export const SOCKET_PATH = '/api/v1/agent/socket'

/**
 * Reconnect backoff; how long a socket no longer wanted waits for the notes open here to say they
 * closed; and how long a run asked for while connecting waits to be sent: later, the moment it was
 * for has passed. Tests shorten them.
 */
export const socketTiming = { retryBaseMs: 1000, retryMaxMs: 30_000, closeWaitMs: 5000, heldMs: 10_000 }

type Trigger = 'noteCreated' | 'editStarted' | 'menu'

/** Opens the socket; tests swap in one that carries their cookie jar. */
export const socketFactory = {
  make: (path: string): WebSocket => new WebSocket(new URL(path, window.location.href.replace(/^http/, 'ws'))),
}

/** A run starting or ending, as the server tells the editors. */
/** A run asked for here. */
export type RunRequest = { trigger: Trigger; noteId: string; spaceId: string }
/** Why one didn't reach the server: no connection in time, or the agent isn't on where it was asked. */
export type DropReason = 'unreachable' | 'off'

export type RunUpdate = { runId: string; noteId: string; trigger: AgentTrigger; status: RunStatus; failure?: string; outcome?: string }

/** What an open note's editor does for a run; each answer goes back as is, or { error }. */
export type EditorHandler = {
  read: () => object
  apply: (change: AgentChange) => Promise<object>
}

/**
 * The tab's connection for agents: it tells the server which notes this
 * tab's editor has open, so closing one ends its writing session at once,
 * and runs read and change those notes in the editor. One per tab, kept
 * open while the space it shows has its agent on.
 */
class AgentSocket {
  private ws: WebSocket | null = null
  private wanted = false
  private failures = 0
  private timer: ReturnType<typeof setTimeout> | undefined
  private closing: ReturnType<typeof setTimeout> | undefined
  /** Notes open here, each with how many opens haven't closed yet. */
  private readonly open = new Map<string, number>()
  private readonly editors = new Map<string, EditorHandler>()
  private readonly runListeners = new Set<(u: RunUpdate) => void>()
  readonly runningListeners = new Set<() => void>()
  /** Runs going now, by id: their notes. */
  private running = new Map<string, string>()
  /** Runs whose change already shows in this tab's editor. */
  private readonly landed = new Set<string>()
  /** Runs asked for before hello, by trigger and note: sent once it comes, or dropped after heldMs. */
  private readonly held = new Map<string, RunRequest & { at: number; expires: ReturnType<typeof setTimeout> }>()
  private readonly dropListeners = new Set<(r: RunRequest, why: DropReason) => void>()
  /** What runs show on the notes open here, as the server last said. */
  private overlays = new Map<string, Overlay[]>()
  /**
   * Answers to overlays not sent yet, by overlay: sent first once the server says hello, so the lists it
   * sends then leave them out. Dropped, a dismissed card would come back, or an accepted change twice.
   */
  private readonly answers = new Map<string, object>()
  readonly overlayListeners = new Set<() => void>()
  /** The server's id for this connection, once it said hello. */
  connectionId: string | null = null

  start() {
    if (this.wanted) return
    this.wanted = true
    window.addEventListener('online', this.online)
    clearTimeout(this.closing)
    // Still open, waiting to close: it carries on.
    if (!this.ws) this.connect()
  }

  /**
   * Closes once the notes open here have said they closed, so their writing sessions end now rather than
   * on the idle timer; or after closeWaitMs.
   */
  stop() {
    this.wanted = false
    window.removeEventListener('online', this.online)
    clearTimeout(this.timer)
    this.dropHeld(true)
    // Stopped again while waiting (a sign-out resets it): one wait, or it would close a later socket.
    clearTimeout(this.closing)
    if (this.open.size === 0 || !this.connectionId) this.close()
    else this.closing = setTimeout(() => this.close(), socketTiming.closeWaitMs)
  }

  private close() {
    clearTimeout(this.closing)
    const ws = this.ws
    this.ws = null
    this.connectionId = null
    // Its close is ignored once this.ws is null, so forget the runs here.
    this.landed.clear()
    this.setRunning(new Map())
    this.setOverlays(new Map())
    ws?.close()
  }

  /** Disconnects and forgets every note and run: another account may sign in next. */
  reset() {
    // Another account's: nothing to tell.
    this.dropHeld(false)
    this.stop()
    this.close()
    this.open.clear()
    this.editors.clear()
    this.answers.clear()
  }

  noteOpened(noteId: string) {
    this.open.set(noteId, (this.open.get(noteId) ?? 0) + 1)
    this.send({ type: 'editor.opened', noteId })
  }

  /** Answers runs' requests about the note while it's open here. */
  serve(noteId: string, handler: EditorHandler): () => void {
    this.editors.set(noteId, handler)
    return () => {
      if (this.editors.get(noteId) === handler) this.editors.delete(noteId)
    }
  }

  /** Hears of runs asked for here that never reached the server: offline too long, or the agent went off. */
  onDropped(listener: (r: RunRequest, why: DropReason) => void): () => void {
    this.dropListeners.add(listener)
    return () => {
      this.dropListeners.delete(listener)
    }
  }

  private dropped(r: RunRequest, why: DropReason) {
    const { trigger, noteId, spaceId } = r
    for (const l of this.dropListeners) l({ trigger, noteId, spaceId }, why)
  }

  private dropHeld(tell: boolean) {
    for (const h of this.held.values()) {
      clearTimeout(h.expires)
      if (tell) this.dropped(h, 'off')
    }
    this.held.clear()
  }

  /** Hears of runs starting and ending while connected. */
  onRun(listener: (u: RunUpdate) => void): () => void {
    this.runListeners.add(listener)
    return () => {
      this.runListeners.delete(listener)
    }
  }

  /** Whether a run on the note is going and its change isn't in the editor yet. */
  isWriting(noteId: string): boolean {
    for (const [run, n] of this.running) if (n === noteId && !this.landed.has(run)) return true
    return false
  }

  /**
   * Asks for a run on a note this tab's editor has open, sending what it shows so the run needn't ask
   * first. Still connecting, it's sent once the server says hello. False with no socket wanted.
   */
  requestRun(trigger: Trigger, noteId: string, spaceId: string): boolean {
    if (!this.wanted) {
      this.dropped({ trigger, noteId, spaceId }, 'off')
      return false
    }
    if (!this.connectionId) {
      const key = `${trigger}:${noteId}`
      clearTimeout(this.held.get(key)?.expires)
      const expires = setTimeout(() => {
        const h = this.held.get(key)
        if (h?.expires !== expires) return
        this.held.delete(key)
        this.dropped(h, 'unreachable')
      }, socketTiming.heldMs)
      this.held.set(key, { trigger, noteId, spaceId, at: Date.now(), expires })
      return true
    }
    // After the effects that asked: one of them may be what serves the note.
    queueMicrotask(() => this.sendRequest(trigger, noteId, spaceId))
    return true
  }

  private sendRequest(trigger: Trigger, noteId: string, spaceId: string) {
    const shown = this.editors.get(noteId)?.read()
    const note = shown && !('error' in shown) ? shown : undefined
    this.send({ type: 'run.request', trigger, noteId, spaceId, note })
  }

  /** revision: the server's revision of what the editor last showed, if it has it. */
  noteClosed(noteId: string, revision: number | null) {
    // A close lands after its save, so the note may have been opened again meanwhile.
    const count = this.open.get(noteId) ?? 0
    // Opened before a reset: another account's editor, whose socket is gone.
    if (count === 0) return
    if (count > 1) {
      this.open.set(noteId, count - 1)
      return
    }
    this.open.delete(noteId)
    this.dropOverlays(noteId)
    this.send({ type: 'editor.closed', noteId, revision })
    if (!this.wanted && this.open.size === 0 && this.ws) this.close()
  }

  private readonly online = () => {
    if (this.wanted && !this.ws) {
      clearTimeout(this.timer)
      this.connect()
    }
  }

  private connect() {
    if (!navigator.onLine) return
    const ws = socketFactory.make(SOCKET_PATH)
    this.ws = ws
    ws.addEventListener('message', (e: MessageEvent) => {
      if (ws !== this.ws || typeof e.data !== 'string') return
      const m = parse(e.data)
      if (m?.type === 'hello' && typeof m.connectionId === 'string') {
        this.failures = 0
        this.connectionId = m.connectionId
        for (const a of this.answers.values()) this.send(a)
        this.answers.clear()
        for (const noteId of this.open.keys()) this.send({ type: 'editor.opened', noteId })
        // Only for a note still open here, asked for recently enough.
        for (const h of this.held.values()) {
          clearTimeout(h.expires)
          if (this.open.has(h.noteId) && Date.now() - h.at <= socketTiming.heldMs) this.sendRequest(h.trigger, h.noteId, h.spaceId)
          else this.dropped(h, 'unreachable')
        }
        this.held.clear()
      } else if (m?.type === 'overlays' && typeof m.noteId === 'string' && Array.isArray(m.overlays)) {
        // Each of the account's tabs hears it: only those with the note open keep it.
        if (!this.open.has(m.noteId)) return
        const next = new Map(this.overlays)
        const list = m.overlays.filter(isOverlay)
        if (list.length) next.set(m.noteId, list)
        else next.delete(m.noteId)
        this.setOverlays(next)
      } else if (m?.type === 'run' && typeof m.runId === 'string' && typeof m.noteId === 'string') {
        this.heard(m as unknown as RunUpdate)
      } else if (m && typeof m.id === 'string') {
        void this.answer(m).then((reply) => {
          // Ids are per connection: on a newer one this would answer another call.
          if (ws !== this.ws) return
          this.send({ type: 'result', id: m.id, ...reply })
          // Its summary still to come, the run is done as far as the user can tell.
          if (m.type === 'apply' && typeof m.runId === 'string' && !('error' in reply) && this.running.has(m.runId)) {
            this.landed.add(m.runId)
            this.setRunning(this.running)
          }
        })
      }
    })
    // A close always follows, and handles it. Closed while still connecting, Node's WebSocket (tests) would
    // otherwise throw what a browser only logs.
    ws.addEventListener('error', () => {})
    ws.addEventListener('close', () => {
      if (ws !== this.ws) return
      this.ws = null
      this.connectionId = null
      // What's still going, or showing, is unknown until the server says again.
      this.landed.clear()
      this.setRunning(new Map())
      this.setOverlays(new Map())
      if (!this.wanted) return
      const wait = Math.min(socketTiming.retryMaxMs, socketTiming.retryBaseMs * 2 ** this.failures++)
      this.timer = setTimeout(() => this.connect(), wait * (0.5 + Math.random() / 2))
    })
  }

  private heard(u: RunUpdate) {
    const next = new Map(this.running)
    if (u.status === 'running') next.set(u.runId, u.noteId)
    else {
      next.delete(u.runId)
      this.landed.delete(u.runId)
    }
    this.setRunning(next)
    for (const l of this.runListeners) l(u)
  }

  private setRunning(next: Map<string, string>) {
    this.running = next
    for (const l of this.runningListeners) l()
  }

  private async answer(m: Record<string, unknown>): Promise<object> {
    const handler = typeof m.noteId === 'string' ? this.editors.get(m.noteId) : undefined
    try {
      if (handler && m.type === 'read') return handler.read()
      if (handler && m.type === 'apply' && isFields(m.before) && isFields(m.after)) {
        return await handler.apply({ before: m.before, after: m.after, op: isOp(m.op) ? m.op : undefined })
      }
    } catch {
      // Closed meanwhile, say: the server reads the store instead.
    }
    // A request this tab can't do: the server reads the store instead of waiting.
    return { error: 'not_open' }
  }

  /** What runs show on the note now. */
  overlaysOn(noteId: string): Overlay[] {
    return this.overlays.get(noteId) ?? noOverlays
  }

  /**
   * The user's answer to an overlay, which goes at once: the server tells every tab what's left. An
   * accepted suggestion's change was made in the editor, as applied says, as for an apply.
   */
  answerOverlay(overlay: Overlay, answer: 'dismiss' | 'accept' | 'reject' | 'stale', applied?: { before: Fields; revision: number | null; shows: Fields }) {
    const list = this.overlaysOn(overlay.noteId).filter((o) => o.id !== overlay.id)
    const next = new Map(this.overlays)
    if (list.length) next.set(overlay.noteId, list)
    else next.delete(overlay.noteId)
    this.setOverlays(next)
    const m = { type: 'overlay.answer', noteId: overlay.noteId, id: overlay.id, answer, ...applied }
    if (this.connected()) this.send(m)
    else this.answers.set(overlay.id, m)
  }

  private dropOverlays(noteId: string) {
    if (!this.overlays.has(noteId)) return
    const next = new Map(this.overlays)
    next.delete(noteId)
    this.setOverlays(next)
  }

  private setOverlays(next: Map<string, Overlay[]>) {
    if (next.size === 0 && this.overlays.size === 0) return
    this.overlays = next
    for (const l of this.overlayListeners) l()
  }

  private connected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN && this.connectionId !== null
  }

  private send(m: object) {
    // Before hello, nothing is lost: hello re-sends what's open.
    if (this.connected()) this.ws?.send(JSON.stringify(m))
  }
}

function isFields(v: unknown): v is Fields {
  return typeof v === 'object' && v !== null && typeof (v as Fields).title === 'string' && typeof (v as Fields).content === 'string'
}

function isOp(v: unknown): v is AgentOp {
  if (typeof v !== 'object' || v === null) return false
  const o = v as Record<string, unknown>
  switch (o.kind) {
    case 'title':
      return typeof o.title === 'string'
    case 'replace':
      return typeof o.find === 'string' && typeof o.replace === 'string'
    case 'insert':
      return (o.at === 'start' || o.at === 'end' || o.at === 'cursor') && typeof o.text === 'string'
    case 'content':
      return typeof o.text === 'string'
  }
  return false
}

const noOverlays: Overlay[] = []

function isOverlay(v: unknown): v is Overlay {
  if (typeof v !== 'object' || v === null) return false
  const o = v as Record<string, unknown>
  const place = o.place as Record<string, unknown> | null
  return (
    typeof o.id === 'string' &&
    typeof o.noteId === 'string' &&
    typeof o.body === 'string' &&
    typeof place === 'object' &&
    place !== null &&
    (o.kind === 'card' || (o.kind === 'suggestion' && isOp(o.change)))
  )
}

function parse(data: string): Record<string, unknown> | null {
  try {
    const m: unknown = JSON.parse(data)
    return m && typeof m === 'object' ? (m as Record<string, unknown>) : null
  } catch {
    return null
  }
}

export const agentSocket = new AgentSocket()

/** Whether an agent's run on the note hasn't changed it yet: "Agent is writing…". */
export function useAgentWriting(noteId: string): boolean {
  return useSyncExternalStore(
    (l) => {
      agentSocket.runningListeners.add(l)
      return () => {
        agentSocket.runningListeners.delete(l)
      }
    },
    () => agentSocket.isWriting(noteId),
  )
}

/** What runs show on the note now, beside its text. */
export function useAgentOverlays(noteId: string): Overlay[] {
  return useSyncExternalStore(
    (l) => {
      agentSocket.overlayListeners.add(l)
      return () => {
        agentSocket.overlayListeners.delete(l)
      }
    },
    () => agentSocket.overlaysOn(noteId),
  )
}

/** Keeps the tab's socket open while AI is set up and the space it shows (spaceId) has its agent on. */
export function useAgentSocket(spaceId: string) {
  const ai = useAiSettings()
  const configs = useAgentConfigs()
  const wanted = Boolean(ai?.configured) && Boolean(configs?.get(spaceId)?.enabled)
  useEffect(() => {
    if (!wanted) return
    agentSocket.start()
    return () => agentSocket.stop()
  }, [wanted])
}
