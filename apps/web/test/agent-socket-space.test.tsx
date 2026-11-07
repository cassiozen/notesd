import { renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { agentSocket, useAgentSocket } from '../src/agent/socket'
import { agent, type AgentConfig } from '../src/api/agent'
import { forgetAgentConfigs } from '../src/notes/agent-config'
import { forgetAiSettings, setAiSettings } from '../src/notes/ai-settings'

afterEach(() => {
  vi.restoreAllMocks()
  forgetAgentConfigs()
  forgetAiSettings()
})

const config = (spaceId: string, enabled: boolean) => ({ spaceId, enabled }) as AgentConfig

describe('the agent socket, by space', () => {
  it('is open while the space shown has its agent on', async () => {
    setAiSettings({ configured: true, baseUrl: 'https://ai.example', model: 'm', keyHint: '', dailyTokenCap: 1, editable: true })
    const list = vi.spyOn(agent, 'list').mockResolvedValue({ spaces: [config('work', true), config('journal', true), config('home', false)] })
    const start = vi.spyOn(agentSocket, 'start').mockImplementation(() => {})
    const stop = vi.spyOn(agentSocket, 'stop').mockImplementation(() => {})
    const { rerender } = renderHook(({ space }) => useAgentSocket(space), { initialProps: { space: 'home' } })
    await waitFor(() => expect(list).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 0))
    expect(start).not.toHaveBeenCalled()

    rerender({ space: 'work' })
    await waitFor(() => expect(start).toHaveBeenCalledTimes(1))
    // From one agent's space to another's: the same socket.
    rerender({ space: 'journal' })
    expect(start).toHaveBeenCalledTimes(1)
    expect(stop).not.toHaveBeenCalled()
    rerender({ space: 'home' })
    expect(stop).toHaveBeenCalledTimes(1)
  })
})
