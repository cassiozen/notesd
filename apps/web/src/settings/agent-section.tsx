import { useId, useState, type FormEvent } from 'react'
import type { AgentConfig, AgentConfigInput, AgentTrigger } from '../api/agent'
import { ApiError } from '../api/client'
import type { Space } from '../api/types'
import auth from '../auth/auth.module.css'
import { genericMessage } from '../auth/errors'
import { saveAgentConfig, useAgentConfigs } from '../notes/agent-config'
import { providerHost, useAiSettings } from '../notes/ai-settings'
import { notesSource } from '../notes/source'
import { reloadSpaces } from '../notes/spaces'
import { openOver } from '../router'
import { Button } from '../ui/button'
import { Field } from '../ui/field'
import { ChevronIcon } from '../ui/icons'
import { TextArea } from '../ui/text-area'
import styles from './settings.module.css'
import info from './space-info.module.css'
import { useUnsaved } from './unsaved'

export type AgentDraft = AgentConfigInput
export type AgentErrors = Partial<Record<string, string>>

export const emptyAgent: AgentDraft = { enabled: false, prompt: '', menuLabel: '', triggers: {} }

export function draftOf(config: AgentConfig | undefined): AgentDraft {
  if (!config) return emptyAgent
  const { enabled, prompt, menuLabel, triggers } = config
  return { enabled, prompt, menuLabel, triggers }
}

/** Whether there's anything to save: an agent turned on, or words written for one. */
export function hasAgent(d: AgentDraft): boolean {
  return d.enabled || d.prompt.trim() !== '' || d.menuLabel.trim() !== '' || Object.values(d.triggers).some((p) => p?.trim())
}

export function sameAgent(a: AgentDraft, b: AgentDraft): boolean {
  const norm = (d: AgentDraft) =>
    JSON.stringify([d.enabled, d.prompt, d.menuLabel, TRIGGERS.map((t) => d.triggers[t.key] ?? '')])
  return norm(a) === norm(b)
}

/** The server's message for a field of the agent, or undefined. */
export function agentFieldError(err: unknown): AgentErrors | undefined {
  if (!(err instanceof ApiError) || err.status !== 400 || !err.field) return undefined
  if (err.field === 'prompt' || err.field === 'menuLabel' || err.field.startsWith('triggers.')) {
    return { [err.field]: err.message.charAt(0).toUpperCase() + err.message.slice(1) + '.' }
  }
  return undefined
}

const TRIGGERS: { key: AgentTrigger; label: string; when: string }[] = [
  { key: 'noteCreated', label: 'Note created', when: 'A new note is opened, before anything is typed.' },
  {
    key: 'editStarted',
    label: 'Started editing',
    when: 'The first change to a note after it’s opened, or after 3 minutes without changes.',
  },
  { key: 'newNoteDone', label: 'New note done', when: 'A new note is closed, or left for 2 minutes.' },
  { key: 'editDone', label: 'Note edit done', when: 'The same, for a note that already existed.' },
  { key: 'noteDeleted', label: 'Note deleted', when: 'A note in this space is deleted. The agent gets what it said.' },
  { key: 'movedIn', label: 'Note moved in', when: 'A note is moved here from another space.' },
  {
    key: 'movedOut',
    label: 'Note moved out',
    when: 'A note is moved to another space. The agent gets it as it left, and can’t change it.',
  },
  { key: 'menu', label: 'Run from menu', when: 'You pick the command in a note’s menu. Also a handy way to try a prompt.' },
]

/** A trigger's name, as Settings shows it. */
export function triggerLabel(key: AgentTrigger): string {
  return TRIGGERS.find((t) => t.key === key)?.label ?? key
}

const DIGEST = 'Find the note titled "Digest"'

/** Starting points, from the agents guide. */
const EXAMPLES: { name: string; draft: AgentDraft }[] = [
  {
    name: 'Journal',
    draft: {
      enabled: true,
      prompt:
        'A journal. Each note is part of an ongoing line of thought. Help me start writing. Never write content for me. You maintain a note called "Digest".',
      menuLabel: '',
      triggers: {
        noteCreated: `${DIGEST} and read it if it exists. Then insert one short, open question to get me writing, as a ## heading, followed by an empty line.`,
        newNoteDone: `${DIGEST}, or create it with three sections: Themes, Open questions, Intentions. Update it with what this note adds. Keep each section short; merge, don’t append.`,
        noteDeleted: 'Remove from "Digest" anything that came only from this note.',
        movedOut: 'Remove from "Digest" anything that came only from this note.',
      },
    },
  },
  {
    name: 'Meeting notes',
    draft: {
      enabled: true,
      prompt: 'Meeting notes. One note per meeting. Keep my words; only add structure.',
      menuLabel: 'List action items',
      triggers: {
        newNoteDone:
          'If the note has no title, title it with the meeting’s subject and today’s date. Add a tag for each project it mentions, reusing existing tags. Don’t change anything else.',
        menu: 'Add an "Action items" section at the end, as a task list of what people agreed to do, with who does it. Don’t change anything else.',
      },
    },
  },
  {
    name: 'Recipes',
    draft: {
      enabled: true,
      prompt: 'Recipes. Metric quantities.',
      menuLabel: '',
      triggers: {
        newNoteDone:
          'Rewrite the ingredients as a task list with metric quantities. Add a tag for the cuisine and one for the main ingredient. Leave the method as written.',
      },
    },
  },
  {
    name: 'Auto-tags',
    draft: {
      enabled: true,
      prompt: 'Keep notes findable with a small, stable set of tags. Prefer existing tags.',
      menuLabel: 'Fix typos',
      triggers: {
        newNoteDone:
          'Add one line of 1–3 tags at the end of the note. Check for existing tags in the space first and reuse them when they fit. Don’t change anything else.',
        menu: 'Fix spelling and obvious typos only. Don’t change wording, punctuation style or formatting.',
      },
    },
  },
]

type Props = {
  draft: AgentDraft
  onChange: (next: AgentDraft) => void
  errors: AgentErrors
  /** Where notes go, for the notice: the AI provider's host. */
  provider: string | null
  /** The new-space wording and the examples. */
  isNew: boolean
}

/** A space's agent: on or off, what the space is for, and a prompt per trigger. */
function AgentFields({ draft, onChange, errors, provider, isNew }: Props) {
  const set = (patch: Partial<AgentDraft>) => onChange({ ...draft, ...patch })
  const setTrigger = (key: AgentTrigger, prompt: string) => set({ triggers: { ...draft.triggers, [key]: prompt } })
  const blank = !hasAgent({ ...draft, enabled: false })
  const whyId = useId()

  return (
    <fieldset className={styles.agent}>
      <legend className="visually-hidden">Agent</legend>
      <label className={auth.keep}>
        <input
          type="checkbox"
          aria-label={isNew ? 'Add an agent' : 'Agent'}
          aria-describedby={whyId}
          checked={draft.enabled}
          onChange={(e) => set({ enabled: e.target.checked })}
        />
        <span>
          {isNew ? 'Add an agent' : 'Agent'}
          <span id={whyId} className={auth.keepWhy}>
            It tags, titles and tidies notes when a prompt below asks it to. It never writes notes for you, and
            everything it does can be undone.
          </span>
        </span>
      </label>
      {!provider && (
        <p className={styles.muted}>Paused: AI isn’t set up on this server. What you write here is kept.</p>
      )}
      {draft.enabled && (
        <>
          {provider && (
            <p className={styles.agentNotice}>
              Notes in this space will be sent to {provider}. Locked notes never are.
            </p>
          )}
          {(isNew || blank) && (
            <div className={styles.agentExamples} role="group" aria-label="Start from an example">
              <span className={styles.muted}>Start from</span>
              {EXAMPLES.map((ex) => (
                <Button key={ex.name} size="sm" variant="chip" onClick={() => onChange(ex.draft)}>
                  {ex.name}
                </Button>
              ))}
            </div>
          )}
          <TextArea
            label="What this space is for"
            placeholder="Recipes. I cook mostly vegetarian. Quantities in metric."
            hint="Sent with every prompt. On its own, it does nothing."
            value={draft.prompt}
            onChange={(e) => set({ prompt: e.target.value })}
            error={errors.prompt}
          />
          <div className={styles.agentTriggers}>
            {TRIGGERS.map((t) => {
              const prompt = draft.triggers[t.key] ?? ''
              const error = errors[`triggers.${t.key}`] ?? (t.key === 'menu' ? errors.menuLabel : undefined)
              return (
                <details key={t.key} className={styles.agentTrigger} open={prompt !== '' || !!error}>
                  <summary>
                    <span className={styles.agentTriggerName}>
                      <span className={styles.agentTriggerChevron}>
                        <ChevronIcon />
                      </span>
                      {t.label}
                    </span>
                    <span className={styles.muted}>{prompt.trim() ? 'On' : 'Off'}</span>
                  </summary>
                  {t.key === 'menu' && (
                    <Field
                      label="Command name"
                      placeholder="Fix typos"
                      autoComplete="off"
                      maxLength={40}
                      value={draft.menuLabel}
                      onChange={(e) => set({ menuLabel: e.target.value })}
                      error={errors.menuLabel}
                    />
                  )}
                  <TextArea
                    label={`${t.label} prompt`}
                    hint={t.when}
                    value={prompt}
                    onChange={(e) => setTrigger(t.key, e.target.value)}
                    error={errors[`triggers.${t.key}`]}
                  />
                </details>
              )
            })}
          </div>
        </>
      )}
    </fieldset>
  )
}

/** Whether Space info offers to turn the space's agent on: once AI is set up, or to resume one paused. */
export function useAgentOffered(): (space: Space) => boolean {
  const configs = useAgentConfigs()
  const ai = useAiSettings()
  return (space) => !space.shared && configs !== null && ai !== null && (ai.configured || configs.has(space.id))
}

/**
 * The agent trait's part of Space info (§7.8, §7.11), on or off: turned on, changed and turned off in place,
 * saved with its own Save.
 */
export function AgentSection({ space }: { space: Space }) {
  const headingId = useId()
  const configs = useAgentConfigs()
  const ai = useAiSettings()
  const saved = configs?.get(space.id)
  const initial = draftOf(saved)
  const [draft, setDraft] = useState<AgentDraft | null>(null)
  const [errors, setErrors] = useState<AgentErrors>({})
  const [formError, setFormError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const current = draft ?? initial
  const dirty = draft !== null && !sameAgent(draft, initial)
  useUnsaved('the agent', dirty)

  function cancel() {
    setDraft(null)
    setErrors({})
    setFormError(undefined)
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    setErrors({})
    setFormError(undefined)
    setBusy(true)
    try {
      await saveAgentConfig(space.id, current)
      // Its trait is the server's: the list brings it.
      notesSource().sync?.now()
      void reloadSpaces()
      // Edits made while it saved stay, still to be saved.
      setDraft((cur) => (cur === current ? null : cur))
    } catch (err) {
      const fields = agentFieldError(err)
      if (fields) setErrors(fields)
      else if (err instanceof ApiError && err.code === 'not_found')
        setFormError('This space hasn’t reached the server yet. Try again once it has synced.')
      else setFormError(genericMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className={info.section} aria-labelledby={headingId}>
      <h3 id={headingId} className={info.caption}>
        Agent
      </h3>
      <form className={info.section} onSubmit={(e) => void submit(e)} noValidate>
        <div className={`${info.group} ${styles.agentGroup}`}>
          {configs === null || ai === null ? (
            <p className={styles.muted}>Loading…</p>
          ) : (
            <AgentFields
              draft={current}
              onChange={setDraft}
              errors={errors}
              provider={ai.configured ? providerHost(ai.baseUrl) : null}
              isNew={!saved}
            />
          )}
        </div>
        {formError && (
          <p role="alert" className={info.error}>
            {formError}
          </p>
        )}
        {(saved || dirty) && (
          <div className={styles.dialogActions}>
            {saved && (
              // Over Space info, which it comes back to as it was.
              <Button size="md" className={dirty ? styles.leading : undefined} onClick={() => openOver('/activity', { space: space.id })}>
                Activity
              </Button>
            )}
            {dirty && (
              <>
                <Button size="md" onClick={cancel} disabled={busy}>
                  Cancel
                </Button>
                <Button variant="solid" size="md" type="submit" busy={busy}>
                  Save
                </Button>
              </>
            )}
          </div>
        )}
      </form>
    </section>
  )
}
