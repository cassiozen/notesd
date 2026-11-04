import type { SyncOutcome } from './protocol'

/** Tests shorten these. */
export const syncTiming = {
  afterEditMs: 2_000,
  /** The worker's own pace, when no tab is looking. */
  intervalMs: 300_000,
  /** How often a visible tab asks. */
  visibleIntervalMs: 30_000,
  /** Opening a note asks for a sync unless one finished this recently. */
  openFreshMs: 10_000,
  firstRetryMs: 2_000,
  maxRetryMs: 300_000,
}

/**
 * When to sync (§5.5): on start, after edits settle, when a tab asks, every 5 min (visible tabs ask every 30 s).
 * Failures back off 2 s doubling to 5 min with jitter. Signed out, it waits to be asked: retrying can't restore a
 * session. Told the app is too old (UpgradeRequired), it stops.
 */
export class Scheduler {
  private requested = true
  private running = false
  private lastEnded = 0
  /** The last cycle went through: polls only add to that. Backoff and sign-in own the timing otherwise. */
  private healthy = false
  private wake: (() => void) | null = null
  private editTimer: ReturnType<typeof setTimeout> | undefined
  private stopped = false

  constructor(private readonly sync: () => Promise<SyncOutcome>) {
    void this.loop()
  }

  /**
   * [ifOlderThanMs]: a poll, only if the last cycle went through and none is running or ended that recently.
   * Several visible tabs each poll on their own timer; this keeps them to about one cycle per interval between them.
   */
  request(ifOlderThanMs?: number) {
    const fresh = !this.healthy || this.running || Date.now() - this.lastEnded < (ifOlderThanMs ?? 0)
    if (ifOlderThanMs !== undefined && fresh) return
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
      this.running = true
      let outcome: SyncOutcome
      try {
        outcome = await this.sync()
      } catch {
        outcome = 'Failed'
      } finally {
        this.running = false
        this.lastEnded = Date.now()
      }
      // Only an updated app helps, and that's a new worker: no retry until then, asked or not.
      if (this.stopped || outcome === 'UpgradeRequired') return
      this.healthy = outcome === 'Done'
      if (outcome === 'Offline' || outcome === 'Failed') {
        const backoff = syncTiming.firstRetryMs * 2 ** failures++
        wait = Math.min(backoff + Math.random() * (backoff / 2), syncTiming.maxRetryMs)
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
