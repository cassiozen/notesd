import { renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AgentRun } from '../api/agent'
import { useFailedRun } from './failed-run'

const latest = new Map<string, AgentRun>()
let configs = new Map([['work', {}]])

vi.mock('../api/agent', () => ({
  agent: {
    runs: ({ note }: { note: string }) => Promise.resolve({ runs: latest.has(note) ? [latest.get(note)] : [] }),
  },
}))
vi.mock('./agent-config', () => ({ useAgentConfigs: () => configs }))

const run = (id: string, status: AgentRun['status']) => ({ id, status }) as AgentRun

beforeEach(() => {
  latest.clear()
  configs = new Map([['work', {}]])
  localStorage.clear()
})

describe('useFailedRun', () => {
  it('drops a failure a later run replaced when the note opens again', async () => {
    latest.set('a', run('r1', 'failed'))
    const { result, rerender } = renderHook(({ id }) => useFailedRun(id), { initialProps: { id: 'a' } })
    await waitFor(() => expect(result.current.run?.id).toBe('r1'))

    rerender({ id: 'b' })
    latest.set('a', run('r2', 'done'))
    rerender({ id: 'a' })
    await waitFor(() => expect(result.current.run).toBeNull())
  })

  it('shows nothing once no space has an agent', async () => {
    latest.set('a', run('r1', 'failed'))
    const { result, rerender } = renderHook(() => useFailedRun('a'))
    await waitFor(() => expect(result.current.run?.id).toBe('r1'))

    configs = new Map()
    rerender()
    expect(result.current.run).toBeNull()
  })
})
