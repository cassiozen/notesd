import { useEffect } from 'react'
import { useAgentConfigs } from '../notes/agent-config'
import { useAiSettings } from '../notes/ai-settings'

export const SOCKET_PATH = '/api/v1/agent/socket'

/** Reconnect backoff; tests shorten it. */
export const socketTiming = { retryBaseMs: 1000, retryMaxMs: 30_000 }

/** Opens the socket; tests swap in one that carries their cookie jar. */
export const socketFactory = {
  make: (path: string): WebSocket => new WebSocket(new URL(path, window.location.href.replace(/^http/, 'ws'))),
}

/**
 * The tab's connection for agents: it tells the server which notes this
 * tab's editor has open, so closing one ends its writing session at once.
 * One per tab, kept open while some space's agent is on.
 */
class AgentSocket {
  private ws: WebSocket | null = null
  private wanted = false
  private failures = 0
  private timer: ReturnType<typeof setTimeout> | undefined
  private readonly open = new Set<string>()
  /** The server's id for this connection, once it said hello. */
  connectionId: string | null = null

  start() {
    if (this.wanted) return
    this.wanted = true
    window.addEventListener('online', this.online)
    this.connect()
  }

  stop() {
    this.wanted = false
    window.removeEventListener('online', this.online)
    clearTimeout(this.timer)
    const ws = this.ws
    this.ws = null
    this.connectionId = null
    ws?.close()
  }

  noteOpened(noteId: string) {
    this.open.add(noteId)
    this.send({ type: 'editor.opened', noteId })
  }

  /** revision: the server's revision of what the editor last showed, if it has it. */
  noteClosed(noteId: string, revision: number | null) {
    this.open.delete(noteId)
    this.send({ type: 'editor.closed', noteId, revision })
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

  private send(m: object) {
    // Before hello, nothing is lost: hello re-sends what's open.
    if (this.ws?.readyState === WebSocket.OPEN && this.connectionId) this.ws.send(JSON.stringify(m))
  }
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

/** Keeps the socket open while AI is set up and some space's agent is on. */
export function useAgentSocket() {
  const ai = useAiSettings()
  const configs = useAgentConfigs()
  const wanted = Boolean(ai?.configured) && [...(configs?.values() ?? [])].some((c) => c.enabled)
  useEffect(() => {
    if (!wanted) return
    agentSocket.start()
    return () => agentSocket.stop()
  }, [wanted])
}
