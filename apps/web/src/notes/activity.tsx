import { useEffect, useId, useRef, useState } from 'react'
import {
  agent,
  type AgentRun,
  type AgentRunDetail,
  type AgentUsage,
  type DiffLine,
  type NoteDiff,
  type UndoBlock,
} from '../api/agent'
import { ApiError } from '../api/client'
import { genericMessage } from '../auth/errors'
import { triggerLabel } from '../settings/agent-section'
import { Banner } from '../ui/banner'
import { Button } from '../ui/button'
import { Sheet } from '../ui/sheet'
import { Surface } from '../ui/surface'
import { relativeTime } from './relative-time'
import { spaceName, useSpaces } from './spaces'
import styles from './activity.module.css'

const FAILURES: Record<string, string> = {
  agent_off: 'the agent was turned off',
  note_gone: 'the note was deleted',
  note_moved: 'the note was moved',
  note_locked: 'the note is locked',
  daily_cap: 'today’s tokens were used up',
  token_cap: 'it used too many tokens',
  tool_cap: 'it took too many steps',
  timeout: 'it took too long',
  provider: 'the AI provider failed',
  interrupted: 'the server stopped',
  queue_full: 'too many runs were waiting',
  unknown_trigger: 'this server doesn’t know the trigger',
}

const OUTCOMES: Record<string, string> = {
  edited: 'Changed the note',
  created: 'Made a note',
  no_change: 'No change',
  undone: 'Undone',
  partly_undone: 'Partly undone',
}

const BLOCKED: Record<UndoBlock, string> = {
  undone: 'Already undone.',
  running: 'The run hasn’t finished.',
  deleted: 'The note was deleted since.',
  locked: 'The note was locked since.',
  changed: 'The note changed since, so undoing would lose those changes.',
}

/** Why a run failed or was skipped, in a few words. */
export function failureText(r: AgentRun): string {
  return FAILURES[r.failure] ?? r.failure
}

/** What became of a run, in a few words. */
export function runStatus(r: AgentRun): string {
  switch (r.status) {
    case 'queued':
      return 'Waiting'
    case 'running':
      return 'Running'
    case 'done':
      return OUTCOMES[r.outcome] ?? 'Done'
    case 'failed':
      return `Failed: ${failureText(r)}`
    case 'skipped':
      return `Skipped: ${failureText(r)}`
  }
}

function noteName(r: AgentRun): string {
  if (r.noteTitle) return r.noteTitle
  return r.noteDeleted ? 'Deleted note' : 'Untitled'
}

const tokens = (n: number) => n.toLocaleString()

/** What each space's agent did: the runs, newest first, and each one's changes, with Undo. */
export function ActivitySheet({ onClose }: { onClose: () => void }) {
  const spaces = useSpaces()
  // From a space's menu: that space first.
  const [space, setSpace] = useState(() => {
    const state = window.history.state as { space?: string } | null
    return state?.space ?? ''
  })
  // From a failed run's notice: that run.
  const [open, setOpen] = useState<string | null>(() => {
    const state = window.history.state as { run?: string } | null
    return state?.run ?? null
  })
  // Bumped by an undo: the list behind the run shows its new outcome.
  const [listVersion, setListVersion] = useState(0)
  const [usage, setUsage] = useState<AgentUsage | null>(null)
  const selectId = useId()
  const heading = useRef<HTMLHeadingElement>(null)
  const was = useRef(open)

  useEffect(() => {
    const abort = new AbortController()
    // The log shows without it.
    agent.usage(abort.signal).then(setUsage, () => {})
    return () => abort.abort()
  }, [])

  useEffect(() => {
    if (was.current !== open) heading.current?.focus()
    was.current = open
  }, [open])

  return (
    <Sheet labelledBy="activity-title" onClose={onClose}>
      <div className={styles.top}>
        <div className={styles.back}>
          {open && (
            <Button size="md" onClick={() => setOpen(null)} aria-label="Back to Activity">
              ‹ Activity
            </Button>
          )}
          <h2 id="activity-title" ref={heading} tabIndex={-1}>
            {open ? 'Run' : 'Activity'}
          </h2>
        </div>
        <Button size="md" onClick={onClose}>
          Done
        </Button>
      </div>
      {open && <RunView id={open} onUndone={() => setListVersion((v) => v + 1)} />}
      {/* Kept while a run is open, so Back returns to the same place in the list. */}
      <div hidden={open !== null} className={styles.page}>
        {/* Outside the keyed list, so the select keeps focus as it changes. */}
        <div className={styles.controls}>
          <label htmlFor={selectId} className={styles.label}>
            Space
          </label>
          <select id={selectId} className={styles.select} value={space} onChange={(e) => setSpace(e.target.value)}>
            <option value="">All spaces</option>
            {spaces?.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
          {usage && (
            <p className={styles.usage}>
              Today: {tokens(usage.tokens)} of {tokens(usage.dailyTokenCap)} tokens
            </p>
          )}
        </div>
        <RunList key={`${space}:${listVersion}`} space={space} spaces={spaces} onOpen={setOpen} />
      </div>
    </Sheet>
  )
}

function RunList({
  space,
  spaces,
  onOpen,
}: {
  space: string
  spaces: ReturnType<typeof useSpaces>
  onOpen: (id: string) => void
}) {
  const [runs, setRuns] = useState<AgentRun[] | null>(null)
  const [next, setNext] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    const abort = new AbortController()
    agent.runs({ space }, abort.signal).then(
      (page) => {
        setRuns(page.runs)
        setNext(page.nextCursor)
      },
      (err: unknown) => {
        if (!abort.signal.aborted) setError(genericMessage(err))
      },
    )
    return () => abort.abort()
  }, [space])

  async function more() {
    setBusy(true)
    try {
      const page = await agent.runs({ space, cursor: next })
      setRuns((r) => [...(r ?? []), ...page.runs])
      setNext(page.nextCursor)
    } catch (err) {
      setError(genericMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      {error && <Banner tone="error">{error}</Banner>}
      {runs === null && !error && <p className={styles.muted}>Loading…</p>}
      {runs?.length === 0 && (
        <p className={styles.muted}>Nothing yet. Each time an agent acts, it shows here, with what it changed.</p>
      )}
      {runs && runs.length > 0 && (
        <ul className={styles.list} aria-label="Runs">
          {runs.map((r) => (
            <li key={r.id}>
              <button type="button" className={styles.run} onClick={() => onOpen(r.id)}>
                <span className={styles.runTop}>
                  <span className={styles.runNote}>{noteName(r)}</span>
                  <span className={styles.muted}>{relativeTime(r.createdAt)}</span>
                </span>
                <span className={styles.runBottom}>
                  <span>
                    {triggerLabel(r.trigger)}
                    {!space && ` · ${spaceName(spaces, r.spaceId)}`}
                  </span>
                  <span className={r.status === 'failed' ? styles.failed : undefined}>{runStatus(r)}</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {next && (
        <Button size="md" onClick={() => void more()} disabled={busy}>
          {busy ? 'Loading…' : 'Show more'}
        </Button>
      )}
    </>
  )
}

function RunView({ id, onUndone }: { id: string; onUndone: () => void }) {
  const [run, setRun] = useState<AgentRunDetail | null>(null)
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState<string>()

  useEffect(() => {
    let live = true
    agent.run(id).then(
      (r) => live && setRun(r),
      (err: unknown) => live && setError(genericMessage(err)),
    )
    return () => {
      live = false
    }
  }, [id])

  async function undo() {
    setBusy(true)
    setError(undefined)
    setDone(undefined)
    try {
      const res = await agent.undo(id)
      onUndone()
      // Notes an earlier undo put back aren't left over.
      const left = res.skipped.filter((sk) => sk.reason !== 'undone').length
      setDone(left ? `Undone ${res.undone.length} of ${res.undone.length + left}.` : 'Undone.')
    } catch (err) {
      // Nothing left to undo: the reasons are on each note, once reloaded.
      if (!(err instanceof ApiError && err.code === 'nothing_to_undo')) setError(genericMessage(err))
    }
    // What it undid, and why not the rest.
    try {
      setRun(await agent.run(id))
    } catch (err) {
      setError(genericMessage(err))
    }
    setBusy(false)
  }

  if (!run) return error ? <Banner tone="error">{error}</Banner> : <p className={styles.muted}>Loading…</p>
  const undoable = run.edits.some((e) => e.undoable)
  return (
    <div className={styles.page}>
      <Surface as="section" className={styles.card} aria-label="Summary">
        <h3 className={styles.title}>{noteName(run)}</h3>
        <p className={run.status === 'failed' ? styles.failed : undefined}>{runStatus(run)}</p>
        <dl className={styles.facts}>
          <dt>Trigger</dt>
          <dd>{triggerLabel(run.trigger)}</dd>
          <dt>When</dt>
          <dd>{new Date(run.createdAt).toLocaleString()}</dd>
          {run.model && (
            <>
              <dt>Model</dt>
              <dd>{run.model}</dd>
            </>
          )}
          <dt>Tokens</dt>
          <dd>{tokens(run.promptTokens + run.completionTokens)}</dd>
          <dt>Tool calls</dt>
          <dd>{run.toolCalls}</dd>
        </dl>
        {run.message && <p className={styles.message}>{run.message}</p>}
        {undoable && (
          <div>
            <Button variant="solid" size="md" onClick={() => void undo()} disabled={busy}>
              {busy ? 'Undoing…' : 'Undo'}
            </Button>
          </div>
        )}
        {done && <p role="status">{done}</p>}
        {error && <Banner tone="error">{error}</Banner>}
      </Surface>
      {run.diffs.map((d) => (
        <ChangeView key={d.noteId} diff={d} blocked={run.edits.find((e) => e.noteId === d.noteId)?.blocked} />
      ))}
    </div>
  )
}

function ChangeView({ diff, blocked }: { diff: NoteDiff; blocked: UndoBlock | undefined }) {
  const title = diff.titleAfter || 'Untitled'
  return (
    <Surface as="section" className={styles.card} aria-label={`Changes to ${title}`}>
      <h3 className={styles.title}>
        {diff.kind === 'create' ? 'Made ' : ''}
        {title}
      </h3>
      {diff.kind === 'edit' && diff.titleBefore !== diff.titleAfter && (
        <p className={styles.muted}>
          Title: “{diff.titleBefore}” → “{diff.titleAfter}”
        </p>
      )}
      {diff.textRemoved ? (
        <p className={styles.muted}>The note was locked or deleted since, so its text isn’t kept here.</p>
      ) : (
        diff.lines.length > 0 && <Lines lines={diff.lines} />
      )}
      {blocked && blocked !== 'undone' && <p className={styles.muted}>Can’t undo: {BLOCKED[blocked]}</p>}
    </Surface>
  )
}

const OP_LABEL = { '+': 'Added', '-': 'Removed', '=': 'Unchanged' } as const

/** Each line's key: its number before (kept, removed, left out) or after (added) the change. */
function lineKeys(lines: DiffLine[]): string[] {
  let a = 0
  let b = 0
  return lines.map((l) => {
    if (l.op === '…') {
      const key = `s${a}`
      a += l.count
      b += l.count
      return key
    }
    if (l.op === '+') return `b${b++}`
    const key = `a${a++}`
    if (l.op === '=') b++
    return key
  })
}

function Lines({ lines }: { lines: DiffLine[] }) {
  const keys = lineKeys(lines)
  return (
    <ol className={styles.diff} aria-label="Line changes">
      {lines.map((l, i) =>
        l.op === '…' ? (
          <li key={keys[i]} className={styles.skip}>
            {l.count} unchanged {l.count === 1 ? 'line' : 'lines'}
          </li>
        ) : (
          <li key={keys[i]} className={styles[l.op === '+' ? 'add' : l.op === '-' ? 'del' : 'same']}>
            <span className="visually-hidden">{OP_LABEL[l.op]}: </span>
            <span aria-hidden="true" className={styles.op}>
              {l.op === '=' ? ' ' : l.op}
            </span>
            {l.text || ' '}
          </li>
        ),
      )}
    </ol>
  )
}
