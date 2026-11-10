import type { AgentConfig, AgentConfigInput, AgentTrigger } from '../api/agent'
import { ApiError } from '../api/client'
import { sentence } from '../auth/errors'
import type { Idea } from './agent-examples'

/** A space's agent being edited. */
export type AgentDraft = Required<AgentConfigInput>
export type AgentErrors = Partial<Record<string, string>>

/** Notes it always reads, at most; as the server allows. */
export const MAX_REFERENCES = 5

export const emptyAgent: AgentDraft = { enabled: false, prompt: '', menuLabel: '', triggers: {}, referenceNotes: [] }

export function draftOf(config: AgentConfig | undefined): AgentDraft {
  if (!config) return emptyAgent
  const { enabled, prompt, menuLabel, triggers, referenceNotes } = config
  return { enabled, prompt, menuLabel, triggers, referenceNotes: referenceNotes ?? [] }
}

/** Equal as saved: the same rules, an empty one too. */
export function sameAgent(a: AgentDraft, b: AgentDraft): boolean {
  const norm = (d: AgentDraft) =>
    JSON.stringify([d.enabled, d.prompt, d.menuLabel, TRIGGERS.map((t) => d.triggers[t.key] ?? null), d.referenceNotes])
  return norm(a) === norm(b)
}

/** Rules with nothing to do: saving them would drop them without a word. */
export function emptyRules(d: AgentDraft): AgentTrigger[] {
  return rulesOf(d).filter((k) => !d.triggers[k]?.trim())
}

/** The rules a draft has: the triggers it has a prompt for, even an empty one being written. */
export function rulesOf(d: AgentDraft): AgentTrigger[] {
  return TRIGGERS.map((t) => t.key).filter((k) => d.triggers[k] !== undefined)
}

/** The server's message for a field of the agent, or undefined. */
export function agentFieldError(err: unknown): AgentErrors | undefined {
  if (!(err instanceof ApiError) || err.status !== 400 || !err.field) return undefined
  if (['prompt', 'menuLabel', 'referenceNotes'].includes(err.field) || err.field.startsWith('triggers.')) {
    return { [err.field]: sentence(err.message) }
  }
  return undefined
}

export type TriggerGroup = 'writing' | 'done' | 'moved'

export const GROUPS: { key: TriggerGroup; title: string }[] = [
  { key: 'writing', title: 'While you write' },
  { key: 'done', title: 'When you’re done' },
  { key: 'moved', title: 'When notes come and go' },
]

/**
 * label: the trigger's name, as Activity shows it. rule: a rule's heading, always "When …".
 * when: exactly when it happens. idea: a general one, where the examples have none or need a Digest.
 */
export const TRIGGERS: { key: AgentTrigger; group: TriggerGroup; label: string; rule: string; when: string; idea?: Idea }[] = [
  {
    key: 'noteCreated',
    group: 'writing',
    label: 'Note created',
    rule: 'When a new note is opened',
    when: 'Before you type anything. Good for a prompt or a template to start from.',
    idea: { title: 'A question to start', prompt: 'Show me a card at the top with one short question to get me started.' },
  },
  {
    key: 'editStarted',
    group: 'writing',
    label: 'Started editing',
    rule: 'When you start editing a note',
    when: 'At your first change after opening an existing note, or after 3 minutes without changes.',
    idea: { title: 'Date it', prompt: 'If the note has no date, add today’s at the top.' },
  },
  {
    key: 'menu',
    group: 'writing',
    label: 'Run from menu',
    rule: 'When you ask, from the note’s menu',
    when: 'A command in the note’s ⋯ menu. Also a handy way to try a prompt.',
  },
  {
    key: 'newNoteDone',
    group: 'done',
    label: 'New note done',
    rule: 'When you finish a new note',
    when: 'When you close it, or leave it for 2 minutes.',
    idea: { title: 'Title and tags', prompt: 'Give the note a short title if it has none, and add 1–3 tags at the end.' },
  },
  {
    key: 'editDone',
    group: 'done',
    label: 'Note edit done',
    rule: 'When you finish editing a note',
    when: 'When you close a note you changed, or leave it for 2 minutes.',
    idea: { title: 'Finished tasks last', prompt: 'Move finished tasks to the end of their list.' },
  },
  {
    key: 'noteDeleted',
    group: 'moved',
    label: 'Note deleted',
    rule: 'When a note is deleted',
    when: 'The agent gets what the note said, and can update other notes.',
  },
  {
    key: 'movedIn',
    group: 'moved',
    label: 'Note moved in',
    rule: 'When a note is moved here',
    when: 'From another space.',
    idea: { title: 'Tag it for here', prompt: 'Add the space’s tags that fit, at the end.' },
  },
  {
    key: 'movedOut',
    group: 'moved',
    label: 'Note moved out',
    rule: 'When a note is moved away',
    when: 'To another space. The agent gets it as it left, and can’t change it.',
  },
]

/** A trigger's name, as Activity shows it. */
export function triggerLabel(key: AgentTrigger): string {
  return TRIGGERS.find((t) => t.key === key)?.label ?? key
}
