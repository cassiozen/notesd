import type { SyncOutcome } from './protocol'

/** Tests shorten these. */
export const syncTiming = {
  afterEditMs: 2_000,
  intervalMs: 60_000,
  firstRetryMs: 2_000,
  maxRetryMs: 300_000,
}

/**
 * When to sync (§5.5): on start, after edits settle, when a tab asks, every minute. Failures back off
 * 2 s doubling to 5 min with jitter. Signed out, it waits to be asked: retrying can't restore a session.
 */
export class Scheduler {
  private requested = true
  private wake: (() => void) | null = null
  private editTimer: ReturnType<typeof setTimeout> | undefined
  private stopped = false

  constructor(private readonly sync: () => Promise<SyncOutcome>) {
    void this.loop()
  }

  request() {
    this.requested = true
    this.wake?.()
  }

  edited() {
    clearTimeout(this.editTimer)
    this.editTimer = setTimeout(() => this.request(), syncTiming.afterEditMs)
  }

  stop() {
    this.stopped = true
    clearTimeout(this.editTimer)
    this.wake?.()
  }

  private async loop() {
    let failures = 0
    let wait = 0
    while (true) {
      await this.until(wait)
      if (this.stopped) return
      this.requested = false
      let outcome: SyncOutcome
      try {
        outcome = await this.sync()
      } catch {
        outcome = 'Failed'
      }
      if (this.stopped) return
      if (outcome === 'Offline' || outcome === 'Failed') {
        const backoff = Math.min(syncTiming.firstRetryMs * 2 ** failures++, syncTiming.maxRetryMs)
        wait = backoff + Math.random() * (backoff / 2)
      } else {
        failures = 0
        wait = outcome === 'SignedOut' ? Infinity : syncTiming.intervalMs
      }
    }
  }

  /** After [ms], or sooner on a request. */
  private until(ms: number): Promise<void> {
    if (this.requested || this.stopped) return Promise.resolve()
    return new Promise((resolve) => {
      const done = () => {
        clearTimeout(timer)
        this.wake = null
        resolve()
      }
      const timer = Number.isFinite(ms) ? setTimeout(done, ms) : undefined
      this.wake = done
    })
  }
}
