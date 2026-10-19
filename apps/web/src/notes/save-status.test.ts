import { describe, expect, it } from 'vitest'
import type { Snapshot } from './saver'
import { offlineNotice, urgency } from './save-status'

const snap = (s: Partial<Snapshot>): Snapshot => ({
  state: 'saved',
  problem: null,
  created: true,
  updatedAt: 1,
  failures: 0,
  locked: false,
  imageError: null,
  imagesBusy: false,
  spaceId: 'default',
  ...s,
})

describe('save status', () => {
  it('stays out of the way while saves go through', () => {
    expect(urgency(snap({}))).toBe('none')
    expect(urgency(snap({ state: 'unsaved' }))).toBe('pending')
    expect(urgency(snap({ state: 'saving' }))).toBe('pending')
  })

  it('waits out one unreachable-server failure, then speaks up', () => {
    const blip = snap({ state: 'failed', problem: { kind: 'offline' }, failures: 1 })
    expect(urgency(blip)).toBe('pending')
    expect(offlineNotice(blip)).toBe(false)

    const down = { ...blip, failures: 2 }
    expect(urgency(down)).toBe('now')
    expect(offlineNotice(down)).toBe(true)
  })

  it('shows refusals, ended sessions and conflicts at once', () => {
    expect(urgency(snap({ state: 'failed', problem: { kind: 'rejected', message: 'no' } }))).toBe('now')
    expect(urgency(snap({ state: 'failed' }))).toBe('now')
    expect(urgency(snap({ state: 'conflict', problem: { kind: 'deleted' } }))).toBe('now')
  })
})
