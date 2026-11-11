import { agentConfigFor } from '../notes/agent-config'
import type { Fields } from '../notes/saver'
import { agentSocket, type Prepared } from './socket'

/** How long without a change before the next one counts as starting to edit again. */
export const triggerTiming = { idleMs: 3 * 60_000 }

const created = new Set<string>()
/** New notes that opened with a result done ahead, until their editor asks for their run. */
const opened = new Map<string, Prepared>()

/**
 * What a new note opens with: the space's "new note opened" run, done ahead, when it's ready. Null
 * opens it empty, and the run starts once it's open.
 */
export function openNewNote(noteId: string, spaceId: string): Fields | null {
  const c = agentConfigFor(spaceId)
  if (!c?.enabled || !c.triggers.noteCreated) return null
  const p = agentSocket.takePrepared(spaceId)
  if (!p) return null
  opened.set(noteId, p)
  return { title: p.title, content: p.content }
}

/**
 * Asks for a run the open editor fires, if the space's agent acts on it. The run works in this tab's
 * editor, never on the server's copy, so it's dropped while the editor isn't connected.
 */
export function requestRun(trigger: 'noteCreated' | 'editStarted' | 'menu', noteId: string, spaceId: string): boolean {
  // Once per new note, however often its editor mounts; one asked while offline, on the next mount.
  if (trigger === 'noteCreated' && created.has(noteId)) return false
  const ahead = trigger === 'noteCreated' ? opened.get(noteId) : undefined
  if (ahead) {
    opened.delete(noteId)
    created.add(noteId)
    agentSocket.usePrepared(ahead, noteId)
    return true
  }
  const c = agentConfigFor(spaceId)
  if (!c?.enabled || !c.triggers[trigger]) return false
  if (!agentSocket.requestRun(trigger, noteId, spaceId)) return false
  if (trigger === 'noteCreated') created.add(noteId)
  return true
}

/** Runs a new note's "new note opened" live: the result it opened with wasn't on the server any more. */
export function runNewNoteLive(noteId: string, spaceId: string) {
  created.delete(noteId)
  requestRun('noteCreated', noteId, spaceId)
}
