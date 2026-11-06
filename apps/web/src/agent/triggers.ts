import { agent } from '../api/agent'
import { agentConfigFor } from '../notes/agent-config'
import { agentSocket } from './socket'

/** How long without a change before the next one counts as starting to edit again. */
export const triggerTiming = { idleMs: 3 * 60_000 }

const created = new Set<string>()

/**
 * Asks for a run the open editor fires, if the space's agent acts on it. The run works in this tab's
 * editor, never on the server's copy, so it's dropped while the editor isn't connected.
 */
export async function requestRun(trigger: 'noteCreated' | 'editStarted' | 'menu', noteId: string, spaceId: string): Promise<boolean> {
  const c = agentConfigFor(spaceId)
  if (!c?.enabled || !c.triggers[trigger]) return false
  const connectionId = agentSocket.connectionId ?? undefined
  if (!connectionId) return false
  if (trigger === 'noteCreated') {
    // Once per note, however often its editor mounts.
    if (created.has(noteId)) return false
    created.add(noteId)
  }
  try {
    await agent.request({ trigger, noteId, spaceId, connectionId })
    return true
  } catch {
    // The editor's next mount may try again.
    if (trigger === 'noteCreated') created.delete(noteId)
    return false
  }
}
