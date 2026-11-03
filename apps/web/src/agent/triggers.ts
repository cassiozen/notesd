import { agent } from '../api/agent'
import { agentConfigFor } from '../notes/agent-config'
import { agentSocket } from './socket'

/** How long without a change before the next one counts as starting to edit again. */
export const triggerTiming = { idleMs: 3 * 60_000 }

/**
 * Asks for a run the open editor fires, if the space's agent acts on it.
 * Note created and started editing need this tab's editor, so they're
 * dropped while it isn't connected; the menu command isn't.
 */
export async function requestRun(trigger: 'noteCreated' | 'editStarted' | 'menu', noteId: string, spaceId: string): Promise<boolean> {
  const c = agentConfigFor(spaceId)
  if (!c?.enabled || !c.triggers[trigger]) return false
  const connectionId = agentSocket.connectionId ?? undefined
  if (!connectionId && trigger !== 'menu') return false
  // Before the request: the run reads the note from this editor.
  if (trigger !== 'menu') agentSocket.noteOpened(noteId)
  try {
    await agent.request({ trigger, noteId, spaceId, connectionId })
    return true
  } catch {
    return false
  }
}
