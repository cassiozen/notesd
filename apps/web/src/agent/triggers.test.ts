import { describe, expect, it, vi } from 'vitest'
import { agent } from '../api/agent'
import { requestRun } from './triggers'
import { agentSocket } from './socket'

vi.mock('../notes/agent-config', () => ({
  agentConfigFor: () => ({ enabled: true, triggers: { noteCreated: 'Ask a question.' } }),
}))

describe('requestRun', () => {
  it('asks again for a new note whose first ask failed', async () => {
    agentSocket.connectionId = 'c_1'
    const request = vi.spyOn(agent, 'request').mockRejectedValueOnce(new Error('offline')).mockResolvedValue({ runId: 'r1' })
    try {
      expect(await requestRun('noteCreated', 'n1', 'default')).toBe(false)
      expect(await requestRun('noteCreated', 'n1', 'default')).toBe(true)
      expect(await requestRun('noteCreated', 'n1', 'default')).toBe(false)
      expect(request).toHaveBeenCalledTimes(2)
    } finally {
      agentSocket.connectionId = null
      request.mockRestore()
    }
  })
})
