import { agentConfigFor } from '../notes/agent-config'
import { agentSocket } from './socket'

/** How long without a change before the next one counts as starting to edit again. */
export const triggerTiming = { idleMs: 3 * 60_000 }

const created = new Set<string>()

/**
 * Asks for a run the open editor fires, if the space's agent acts on it. The run works in this tab's
 * editor, never on the server's copy, so it's dropped while the editor isn't connected.
 */
export function requestRun(trigger: 'noteCreated' | 'editStarted' | 'menu', noteId: string, spaceId: string): boolean {
  const c = agentConfigFor(spaceId)
  if (!c?.enabled || !c.triggers[trigger]) return false
  // Once per new note, however often its editor mounts; one asked while offline, on the next mount.
  if (trigger === 'noteCreated' && created.has(noteId)) return false
  if (!agentSocket.requestRun(trigger, noteId, spaceId)) return false
  if (trigger === 'noteCreated') created.add(noteId)
  return true
}
