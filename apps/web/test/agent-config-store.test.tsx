import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { agent, type AgentConfig } from '../src/api/agent'
import { resetAgentConfigs, useAgentConfigs } from '../src/notes/agent-config'

describe('agent setups store', () => {
  it('fetches again once back online after a failed fetch, and after a reset', async () => {
    let n = 0
    let up = false
    vi.spyOn(agent, 'list').mockImplementation(async () => {
      if (!up) throw new Error('offline')
      n++
      return { spaces: [{ spaceId: `s${n}` } as AgentConfig] }
    })
    const { result } = renderHook(() => useAgentConfigs())
    await waitFor(() => expect(agent.list).toHaveBeenCalledTimes(1))
    expect(result.current).toBeNull()

    up = true
    act(() => {
      window.dispatchEvent(new Event('online'))
    })
    await waitFor(() => expect(result.current?.has('s1')).toBe(true))

    act(() => resetAgentConfigs())
    await waitFor(() => expect(result.current?.has('s2')).toBe(true))
  })
})
