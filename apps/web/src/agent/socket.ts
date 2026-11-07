import { useEffect } from 'react'
import type { AgentChange, AgentOp } from '../editor/agent-edit'
import type { Fields } from '../notes/saver'
import { useAgentConfigs } from '../notes/agent-config'
import { useAiSettings } from '../notes/ai-settings'

export const SOCKET_PATH = '/api/v1/agent/socket'

/**
 * Reconnect backoff, and how long a socket no longer wanted waits for the notes open here to say they
 * closed; tests shorten them.
 */
export const socketTiming = { retryBaseMs: 1000, retryMaxMs: 30_000, closeWaitMs: 5000 }

/** Opens the socket; tests swap in one that carries their cookie jar. */
export const socketFactory = {
  make: (path: string): WebSocket => new WebSocket(new URL(path, window.location.href.replace(/^http/, 'ws'))),
}

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
  private readonly open = new Set<string>()
  private readonly editors = new Map<string, EditorHandler>()
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
    ws?.close()
  }

  noteOpened(noteId: string) {
    this.open.add(noteId)
    this.send({ type: 'editor.opened', noteId })
  }

  /** Answers runs' requests about the note while it's open here. */
  serve(noteId: string, handler: EditorHandler): () => void {
    this.editors.set(noteId, handler)
    return () => {
      if (this.editors.get(noteId) === handler) this.editors.delete(noteId)
    }
  }

  /** revision: the server's revision of what the editor last showed, if it has it. */
  noteClosed(noteId: string, revision: number | null) {
    this.open.delete(noteId)
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
        for (const noteId of this.open) this.send({ type: 'editor.opened', noteId })
      } else if (m && typeof m.id === 'string') {
        void this.answer(m).then((reply) => {
          // Ids are per connection: on a newer one this would answer another call.
          if (ws === this.ws) this.send({ type: 'result', id: m.id, ...reply })
        })
      }
    })
    ws.addEventListener('close', () => {
      if (ws !== this.ws) return
      this.ws = null
      this.connectionId = null
      if (!this.wanted) return
      const wait = Math.min(socketTiming.retryMaxMs, socketTiming.retryBaseMs * 2 ** this.failures++)
      this.timer = setTimeout(() => this.connect(), wait * (0.5 + Math.random() / 2))
    })
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

  private send(m: object) {
    // Before hello, nothing is lost: hello re-sends what's open.
    if (this.ws?.readyState === WebSocket.OPEN && this.connectionId) this.ws.send(JSON.stringify(m))
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

function parse(data: string): Record<string, unknown> | null {
  try {
    const m: unknown = JSON.parse(data)
    return m && typeof m === 'object' ? (m as Record<string, unknown>) : null
  } catch {
    return null
  }
}

export const agentSocket = new AgentSocket()

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
