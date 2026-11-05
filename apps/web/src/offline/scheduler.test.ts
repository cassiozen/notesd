import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SyncOutcome } from './protocol'
import { Scheduler, syncTiming } from './scheduler'

// Timing the engine tests can't pin down: they run against a real server, in real time.

let scheduler: Scheduler | undefined

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  scheduler?.stop()
  scheduler = undefined
  vi.useRealTimers()
})

function counting(outcome: SyncOutcome = 'Done') {
  const sync = vi.fn(async () => outcome)
  const s = new Scheduler(sync)
  scheduler = s
  return { sync, s }
}

describe('polls', () => {
  it('run a cycle once the last one is older than asked', async () => {
    const { sync, s } = counting()
    await vi.advanceTimersByTimeAsync(0)
    expect(sync).toHaveBeenCalledTimes(1)

    s.request(10_000)
    await vi.advanceTimersByTimeAsync(0)
    expect(sync).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(10_000)
    s.request(10_000)
    await vi.advanceTimersByTimeAsync(0)
    expect(sync).toHaveBeenCalledTimes(2)
  })

  it('leave a failing sync to its backoff', async () => {
    const { sync, s } = counting('Offline')
    await vi.advanceTimersByTimeAsync(0)
    expect(sync).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(1_000)
    s.request(0)
    await vi.advanceTimersByTimeAsync(0)
    expect(sync).toHaveBeenCalledTimes(1)

    // The first retry: 2 s plus up to half again of jitter.
    await vi.advanceTimersByTimeAsync(2_000)
    expect(sync).toHaveBeenCalledTimes(2)
  })

  it('aren’t skipped because the last cycle was slow', async () => {
    const sync = vi.fn(() => new Promise<SyncOutcome>((resolve) => setTimeout(() => resolve('Done'), 10_000)))
    const s = new Scheduler(sync)
    scheduler = s
    await vi.advanceTimersByTimeAsync(10_000)
    await vi.advanceTimersByTimeAsync(20_000)

    s.request(24_000)
    await vi.advanceTimersByTimeAsync(0)
    expect(sync).toHaveBeenCalledTimes(2)
  })

  it('leave a signed-out sync waiting for the sign-in', async () => {
    const { sync, s } = counting('SignedOut')
    await vi.advanceTimersByTimeAsync(0)

    await vi.advanceTimersByTimeAsync(60_000)
    s.request(0)
    await vi.advanceTimersByTimeAsync(0)
    expect(sync).toHaveBeenCalledTimes(1)

    s.request()
    await vi.advanceTimersByTimeAsync(0)
    expect(sync).toHaveBeenCalledTimes(2)
  })
})

it('syncs on its own at the hidden pace', async () => {
  const { sync } = counting()
  await vi.advanceTimersByTimeAsync(0)
  await vi.advanceTimersByTimeAsync(syncTiming.intervalMs - 1)
  expect(sync).toHaveBeenCalledTimes(1)
  await vi.advanceTimersByTimeAsync(1)
  expect(sync).toHaveBeenCalledTimes(2)
})
