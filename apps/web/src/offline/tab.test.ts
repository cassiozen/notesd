import { afterEach, describe, expect, it, vi } from 'vitest'
import { connectEngine } from './tab'

// Two tabs in one page: the first to ask gets the lock and starts the worker.

class FailingWorker extends EventTarget {
  constructor() {
    super()
    // Late enough that the other tab's first calls have reached it.
    setTimeout(() => this.dispatchEvent(Object.assign(new Event('error'), { message: 'No OPFS here' })), 50)
  }
  postMessage() {}
  terminate() {}
}

function stubLocks() {
  let held = false
  const locks = {
    request: (_name: string, opts: { signal: AbortSignal }, fn: () => Promise<void>) => {
      if (!held) {
        held = true
        return fn()
      }
      return new Promise((_, reject) => opts.signal.addEventListener('abort', () => reject(new Error('aborted'))))
    },
  }
  Object.defineProperty(navigator, 'locks', { configurable: true, value: locks })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('a worker that fails to start', () => {
  it('fails every tab’s calls, not only the starting tab’s, and the ones after', async () => {
    vi.stubGlobal('Worker', FailingWorker)
    stubLocks()
    const leader = connectEngine('me', 'token')
    const follower = connectEngine('me', 'token')

    // Both asked before it fails: the follower's call is with the worker by then.
    const calls = [leader.call('status'), follower.call('status')]
    await expect(calls[0]).rejects.toThrow('No OPFS here')
    await expect(calls[1]).rejects.toThrow('No OPFS here')
    await expect(follower.call('pendingNotes')).rejects.toThrow('No OPFS here')

    // A tab opened after the failure asks the failed one, which answers.
    const late = connectEngine('me', 'token')
    await expect(late.call('status')).rejects.toThrow('No OPFS here')

    for (const t of [leader, follower, late]) t.close()
  })
})
