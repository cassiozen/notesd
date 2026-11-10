import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { agent, type AgentConfig } from '../src/api/agent'
import { forgetAgentConfigs, saveAgentConfig, useAgentConfigs } from '../src/notes/agent-config'

const config = (spaceId: string) => ({ spaceId }) as AgentConfig

describe('agent setups store', () => {
  afterEach(() => {
    forgetAgentConfigs()
    vi.restoreAllMocks()
  })

  it('fetches again once back online after a failed fetch, and after a forget', async () => {
    let n = 0
    let up = false
    vi.spyOn(agent, 'list').mockImplementation(async () => {
      if (!up) throw new Error('offline')
      n++
      return { spaces: [config(`s${n}`)] }
    })
    const { result } = renderHook(() => useAgentConfigs())
    await waitFor(() => expect(agent.list).toHaveBeenCalledTimes(1))
    expect(result.current).toBeNull()

    up = true
    act(() => {
      window.dispatchEvent(new Event('online'))
    })
    await waitFor(() => expect(result.current?.has('s1')).toBe(true))

    act(() => forgetAgentConfigs())
    await waitFor(() => expect(result.current?.has('s2')).toBe(true))
  })

  it('drops a fetch or save still on its way at a forget', async () => {
    const lists: ((v: { spaces: AgentConfig[] }) => void)[] = []
    vi.spyOn(agent, 'list').mockImplementation(() => new Promise((resolve) => lists.push(resolve)))
    let put!: (v: AgentConfig) => void
    vi.spyOn(agent, 'put').mockImplementation(() => new Promise((resolve) => (put = resolve)))

    const { result } = renderHook(() => useAgentConfigs())
    await waitFor(() => expect(lists).toHaveLength(1))
    const saved = saveAgentConfig('old-save', {} as AgentConfig)

    act(() => {
      forgetAgentConfigs()
      window.dispatchEvent(new Event('online'))
    })
    await waitFor(() => expect(lists).toHaveLength(2))
    await act(async () => {
      lists[0]?.({ spaces: [config('old-list')] })
      put(config('old-save'))
      await saved
    })
    expect(result.current).toBeNull()

    act(() => lists[1]?.({ spaces: [config('new')] }))
    await waitFor(() => expect([...(result.current?.keys() ?? [])]).toEqual(['new']))
  })
})
