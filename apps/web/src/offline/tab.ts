import {
  EngineError,
  EngineRestarted,
  ONCE,
  type Call,
  type EngineApi,
  type EngineEvent,
  type Method,
  type Reply,
  type WorkerMessage,
} from './protocol'

/** A tab's handle on the engine, wherever it runs. */
export type Engine = {
  call<M extends Method>(method: M, ...args: Parameters<EngineApi[M]>): ReturnType<EngineApi[M]>
  /** Every event, from any tab's writes or sync. Returns an unsubscribe function. */
  on(listener: (event: EngineEvent) => void): () => void
  /** A new token after signing in again. */
  setCsrfToken(token: string): void
  close(): void
}

// Per origin, not per user: the OPFS pool is the origin's, and the session cookie too.
const NAME = 'notesd-engine'

type Wire =
  | { type: 'call'; call: Call }
  | { type: 'reply'; reply: Reply }
  | { type: 'event'; event: EngineEvent }
  /** A tab started the engine: calls sent before then went nowhere, or to a tab that's gone. */
  | { type: 'leader' }

type Pending = { call: Call; resolve: (value: unknown) => void; reject: (err: Error) => void }

/**
 * The tab holding the Web Lock runs the worker; others call it over a BroadcastChannel. When it closes the next
 * tab takes over, and unanswered calls go to the new worker, except ONCE ones, which fail.
 */
export function connectEngine(user: string, csrfToken: string): Engine {
  const tab = crypto.randomUUID()
  let seq = 0
  let token = csrfToken
  const pending = new Map<number, Pending>()
  const listeners = new Set<(event: EngineEvent) => void>()
  const channel = new BroadcastChannel(NAME)
  let worker: Worker | null = null
  let closed = false
  let release: (() => void) | undefined
  const abort = new AbortController()

  const emit = (event: EngineEvent) => {
    for (const l of listeners) l(event)
  }

  const deliver = ({ id, result, error }: Reply) => {
    const p = pending.get(id)
    if (!p) return
    pending.delete(id)
    if (error === undefined) p.resolve(result)
    else p.reject(new EngineError(error))
  }

  const send = (call: Call) => {
    if (worker) worker.postMessage(call)
    else channel.postMessage({ type: 'call', call } satisfies Wire)
  }

  const resend = (calls: Pending[]) => {
    for (const p of calls) {
      if (!pending.has(p.call.id)) continue
      if (ONCE.has(p.call.method)) {
        pending.delete(p.call.id)
        p.reject(new EngineRestarted())
      } else {
        send(p.call)
      }
    }
  }

  function request(method: Method, args: unknown[]): Promise<unknown> {
    if (closed) return Promise.reject(new Error('The notes engine is closed'))
    const c: Call = { from: tab, id: ++seq, method, args }
    return new Promise((resolve, reject) => {
      pending.set(c.id, { call: c, resolve, reject })
      send(c)
    })
  }

  const lead = () => {
    const earlier = [...pending.values()]
    worker = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module', name: 'notesd engine' })
    worker.addEventListener('message', ({ data }: MessageEvent<WorkerMessage>) => {
      if (data.type === 'event') {
        emit(data.event)
        channel.postMessage(data satisfies Wire)
      } else if (data.to === tab) {
        deliver(data)
      } else {
        const { to, id, result, error } = data
        channel.postMessage({ type: 'reply', reply: { to, id, result, error } } satisfies Wire)
      }
    })
    worker.addEventListener('error', (e) => {
      // Only this tab's calls can be failed here; the others' wait for a tab that can start one.
      for (const p of pending.values()) p.reject(new EngineError(e.message || 'The notes engine failed to start'))
      pending.clear()
    })
    // Before anything else reaches the new worker.
    void request('open', [user, token]).catch(() => {})
    resend(earlier)
    channel.postMessage({ type: 'leader' } satisfies Wire)
  }

  channel.addEventListener('message', ({ data }: MessageEvent<Wire>) => {
    switch (data.type) {
      case 'call':
        worker?.postMessage(data.call)
        break
      case 'reply':
        if (data.reply.to === tab) deliver(data.reply)
        break
      case 'event':
        if (!worker) emit(data.event)
        break
      case 'leader':
        resend([...pending.values()])
        break
    }
  })

  // Held for the life of the tab; the browser lets go when it closes.
  navigator.locks
    .request(NAME, { signal: abort.signal }, () => {
      if (closed) return
      lead()
      return new Promise<void>((r) => {
        release = r
      })
    })
    .catch(() => {
      // Aborted by close() while waiting.
    })

  const online = () => void request('requestSync', []).catch(() => {})
  const visible = () => {
    if (document.visibilityState === 'visible') online()
  }
  window.addEventListener('online', online)
  document.addEventListener('visibilitychange', visible)

  void request('open', [user, token]).catch(() => {})

  return {
    call: ((method: Method, ...args: unknown[]) => request(method, args)) as Engine['call'],
    on(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    setCsrfToken(t) {
      token = t
      void request('open', [user, t]).catch(() => {})
    },
    close() {
      closed = true
      window.removeEventListener('online', online)
      document.removeEventListener('visibilitychange', visible)
      abort.abort()
      worker?.terminate()
      worker = null
      release?.()
      channel.close()
      for (const p of pending.values()) p.reject(new Error('The notes engine is closed'))
      pending.clear()
      listeners.clear()
    },
  }
}
