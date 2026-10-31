import {
  EngineError,
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
  | WorkerMessage
  | { type: 'call'; call: Call }
  /** A tab started the engine: calls sent before then went nowhere, or to a tab that's gone. */
  | { type: 'leader' }

type Pending = { call: Call; resolve: (value: unknown) => void; reject: (err: Error) => void }

/**
 * The tab holding the Web Lock runs the worker; others call it over a BroadcastChannel. When it closes the next
 * tab takes over, and unanswered calls go to the new worker: every call is safe to run twice.
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
    for (const p of calls) if (pending.has(p.call.id)) send(p.call)
  }

  // A caller resends on hearing of a new owner, which may have had the call already: run it once.
  const forwarded = new Set<string>()
  const forward = (call: Call) => {
    const key = `${call.from}:${call.id}`
    if (forwarded.has(key)) return
    forwarded.add(key)
    // Resends come right after a hand-off; older keys are no longer needed.
    if (forwarded.size > 1000) forwarded.delete(forwarded.values().next().value as string)
    worker?.postMessage(call)
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
      if (data.type === 'event') emit(data.event)
      if (data.type === 'reply' && data.to === tab) deliver(data)
      else channel.postMessage(data satisfies Wire)
    })
    worker.addEventListener('error', (e) => {
      // Only this tab's calls can be failed here; the others' wait for a tab that can start one.
      for (const p of pending.values()) p.reject(new EngineError(e.message || 'The notes engine failed to start'))
      pending.clear()
    })
    // Before anything else reaches the new worker. The tab's own first open may still be waiting: a second would sync twice.
    if (earlier[0]?.call.method !== 'open') void request('open', [user, token]).catch(() => {})
    resend(earlier)
    channel.postMessage({ type: 'leader' } satisfies Wire)
  }

  channel.addEventListener('message', ({ data }: MessageEvent<Wire>) => {
    switch (data.type) {
      case 'call':
        if (worker) forward(data.call)
        break
      case 'reply':
        if (data.to === tab) deliver(data)
        break
      case 'event':
        // Never this tab's own: a BroadcastChannel doesn't deliver to its sender.
        emit(data.event)
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
    .catch((err: unknown) => {
      // Aborted by close() while waiting; otherwise the worker couldn't be made, and nothing will answer.
      if (closed) return
      for (const p of pending.values()) p.reject(new EngineError(err instanceof Error ? err.message : String(err)))
      pending.clear()
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
