import { describe, expect, it, vi } from 'vitest'
import { requestRun } from './triggers'
import { agentSocket } from './socket'

vi.mock('../notes/agent-config', () => ({
  agentConfigFor: () => ({ enabled: true, triggers: { noteCreated: 'Ask a question.' } }),
}))

describe('requestRun', () => {
  it('asks again for a new note whose first ask found no connection', () => {
    const ask = vi.spyOn(agentSocket, 'requestRun').mockReturnValueOnce(false).mockReturnValue(true)
    try {
      expect(requestRun('noteCreated', 'n1', 'default')).toBe(false)
      expect(requestRun('noteCreated', 'n1', 'default')).toBe(true)
      expect(requestRun('noteCreated', 'n1', 'default')).toBe(false)
      expect(ask).toHaveBeenCalledTimes(2)
    } finally {
      ask.mockRestore()
    }
  })
})
