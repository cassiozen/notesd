import type { AgentConfig, AgentConfigInput, AgentTrigger } from '../api/agent'
import { ApiError } from '../api/client'

/** A space's agent being edited. */
export type AgentDraft = Required<AgentConfigInput>
export type AgentErrors = Partial<Record<string, string>>

export const emptyAgent: AgentDraft = { enabled: false, prompt: '', menuLabel: '', triggers: {}, referenceNotes: [] }

export function draftOf(config: AgentConfig | undefined): AgentDraft {
  if (!config) return emptyAgent
  const { enabled, prompt, menuLabel, triggers, referenceNotes } = config
  return { enabled, prompt, menuLabel, triggers, referenceNotes: referenceNotes ?? [] }
}

export function sameAgent(a: AgentDraft, b: AgentDraft): boolean {
  const norm = (d: AgentDraft) =>
    JSON.stringify([d.enabled, d.prompt, d.menuLabel, TRIGGERS.map((t) => d.triggers[t.key] ?? ''), d.referenceNotes])
  return norm(a) === norm(b)
}

/** The rules a draft has: the triggers it has a prompt for, even an empty one being written. */
export function rulesOf(d: AgentDraft): AgentTrigger[] {
  return TRIGGERS.map((t) => t.key).filter((k) => d.triggers[k] !== undefined)
}

/** The server's message for a field of the agent, or undefined. */
export function agentFieldError(err: unknown): AgentErrors | undefined {
  if (!(err instanceof ApiError) || err.status !== 400 || !err.field) return undefined
  if (['prompt', 'menuLabel', 'referenceNotes'].includes(err.field) || err.field.startsWith('triggers.')) {
    return { [err.field]: err.message.charAt(0).toUpperCase() + err.message.slice(1) + '.' }
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
 * label: the trigger's name, as Activity shows it. rule: a rule's heading.
 * when: exactly when it happens. suggestion: a new rule's prompt, to change or replace.
 */
export const TRIGGERS: { key: AgentTrigger; group: TriggerGroup; label: string; rule: string; when: string; suggestion: string }[] = [
  {
    key: 'noteCreated',
    group: 'writing',
    label: 'Note created',
    rule: 'When a new note is opened',
    when: 'Before you type anything. Good for a prompt or a template to start from.',
    suggestion: 'Ask me a question to get me started, as a heading at the top.',
  },
  {
    key: 'editStarted',
    group: 'writing',
    label: 'Started editing',
    rule: 'When you start editing a note',
    when: 'At your first change after opening a note, or after 3 minutes without changes.',
    suggestion: 'If the note has no date, add today’s at the top.',
  },
  {
    key: 'menu',
    group: 'writing',
    label: 'Run from menu',
    rule: 'When you ask, from the note’s menu',
    when: 'A command in the note’s ⋯ menu. Also a handy way to try a prompt.',
    suggestion: 'Fix spelling and obvious typos only.',
  },
  {
    key: 'newNoteDone',
    group: 'done',
    label: 'New note done',
    rule: 'When you finish a new note',
    when: 'When you close it, or leave it for 2 minutes.',
    suggestion: 'Give the note a short title if it has none, and add 1–3 tags at the end.',
  },
  {
    key: 'editDone',
    group: 'done',
    label: 'Note edit done',
    rule: 'When you finish editing a note',
    when: 'When you close a note you changed, or leave it for 2 minutes.',
    suggestion: 'Move finished tasks to the end of their list.',
  },
  {
    key: 'noteDeleted',
    group: 'moved',
    label: 'Note deleted',
    rule: 'When a note is deleted',
    when: 'The agent gets what the note said, and can update other notes.',
    suggestion: 'Take out of the Digest anything that came only from this note.',
  },
  {
    key: 'movedIn',
    group: 'moved',
    label: 'Note moved in',
    rule: 'When a note is moved here',
    when: 'From another space.',
    suggestion: 'Add the space’s tags that fit, at the end.',
  },
  {
    key: 'movedOut',
    group: 'moved',
    label: 'Note moved out',
    rule: 'When a note is moved away',
    when: 'To another space. The agent gets it as it left, and can’t change it.',
    suggestion: 'Take out of the Digest anything that came only from this note.',
  },
]

/** The menu rule's command name, when a suggestion fills in its prompt. */
export const SUGGESTED_MENU_LABEL = 'Fix typos'

/** A trigger's name, as Activity shows it. */
export function triggerLabel(key: AgentTrigger): string {
  return TRIGGERS.find((t) => t.key === key)?.label ?? key
}
