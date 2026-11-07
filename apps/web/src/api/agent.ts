import { request } from './client'

/** An event a space's agent can act on; the keys of AgentConfig.triggers. */
export type AgentTrigger =
  | 'noteCreated'
  | 'editStarted'
  | 'newNoteDone'
  | 'editDone'
  | 'noteDeleted'
  | 'movedIn'
  | 'movedOut'
  | 'menu'

/** A space's agent. A trigger without a prompt does nothing. */
export type AgentConfig = {
  spaceId: string
  enabled: boolean
  prompt: string
  menuLabel: string
  triggers: Partial<Record<AgentTrigger, string>>
  /** Titles of notes in the space that go with every run. */
  referenceNotes: string[]
  updatedAt: number
}

/** Without referenceNotes, the server keeps them. */
export type AgentConfigInput = Pick<AgentConfig, 'enabled' | 'prompt' | 'menuLabel' | 'triggers'> & { referenceNotes?: string[] }

/** Why an edit can't be undone. */
export type UndoBlock = 'undone' | 'running' | 'deleted' | 'locked' | 'changed' | 'not_saved'

/** A note a run changed or created. */
export type RunEdit = {
  noteId: string
  kind: 'edit' | 'create'
  title: string
  writtenAt: number
  undoneAt: number | null
  undoable: boolean
  blocked?: UndoBlock
}

export type RunStatus = 'queued' | 'running' | 'done' | 'failed' | 'skipped'

/** One run of a space's agent, as the activity log shows it. */
export type AgentRun = {
  id: string
  trigger: AgentTrigger
  status: RunStatus
  spaceId: string
  noteId: string
  noteTitle: string
  noteDeleted: boolean
  /** edited, created, no_change, undone, partly_undone; empty until it finishes. */
  outcome: string
  failure: string
  message: string
  model: string
  toolCalls: number
  promptTokens: number
  completionTokens: number
  createdAt: number
  startedAt: number | null
  finishedAt: number | null
  edits: RunEdit[]
  /** What it showed beside its note, when it did: cards, and suggested changes with how many were accepted. */
  overlays?: { cards: number; suggestions: number; accepted: number }
}

/** A line of a diff; "…" stands for count unchanged lines left out. */
export type DiffLine = { op: '=' | '-' | '+'; text?: string } | { op: '…'; count: number }

export type NoteDiff = {
  noteId: string
  kind: 'edit' | 'create'
  titleBefore: string
  titleAfter: string
  lines: DiffLine[]
  /** The note was locked or deleted since, and the run's copy of its text cleared. */
  textRemoved: boolean
}

export type AgentRunDetail = AgentRun & { diffs: NoteDiff[] }

export type AgentUsage = { day: string; tokens: number; runs: number; dailyTokenCap: number }

export type RunsPage = { runs: AgentRun[]; nextCursor: string; hasMore: boolean }

export type UndoResult = { undone: string[]; skipped: { noteId: string; reason: UndoBlock }[] }

/** GET /settings/account. */
export type AccountSettings = { timeZone: string; timeZoneSource: 'default' | 'auto' | 'user' }

const path = (id: string) => `/agent/spaces/${encodeURIComponent(id)}`

export const agent = {
  /** Every space whose agent has been set up. */
  list: () => request<{ spaces: AgentConfig[] }>('GET', '/agent/spaces'),
  /** Replaces the space's agent; 404 not_found if the server doesn't have the space. */
  put: (spaceId: string, config: AgentConfigInput) => request<AgentConfig>('PUT', path(spaceId), config),
  /** The activity log, newest first: a space's or a note's, or every run. */
  runs: (filter: { space?: string; note?: string; limit?: number; cursor?: string }, signal?: AbortSignal) => {
    const q = new URLSearchParams()
    for (const [k, v] of Object.entries(filter)) if (v !== undefined && v !== '') q.set(k, String(v))
    return request<RunsPage>('GET', `/agent/runs?${q.toString()}`, undefined, { signal })
  },
  run: (id: string) => request<AgentRunDetail>('GET', `/agent/runs/${encodeURIComponent(id)}`),
  /** Today's, against the daily cap. */
  usage: (signal?: AbortSignal) => request<AgentUsage>('GET', '/agent/usage', undefined, { signal }),
  /** 409 nothing_to_undo when none of its changes can be: the run's edits then say why. */
  undo: (id: string) => request<UndoResult>('POST', `/agent/runs/${encodeURIComponent(id)}/undo`),
  account: () => request<AccountSettings>('GET', '/settings/account'),
  setTimeZone: (timeZone: string) => request<AccountSettings>('PUT', '/settings/account', { timeZone }),
}
