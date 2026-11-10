import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { createPortal } from 'react-dom'
import type { AgentConfig, AgentTrigger } from '../api/agent'
import { ApiError } from '../api/client'
import type { Space } from '../api/types'
import { genericMessage } from '../auth/errors'
import { openOver } from '../router'
import { Button } from '../ui/button'
import { Field } from '../ui/field'
import { Kicker } from '../ui/kicker'
import field from '../ui/field.module.css'
import { retryAgentConfigs, saveAgentConfig, useAgentConfigs, useAgentConfigsFailed } from './agent-config'
import { EXAMPLES, isBlank, withExample, type AgentExample } from './agent-examples'
import {
  GROUPS,
  MAX_REFERENCES,
  TRIGGERS,
  agentFieldError,
  draftOf,
  emptyRules,
  rulesOf,
  sameAgent,
  type AgentDraft,
  type AgentErrors,
  type Idea,
} from './agent-rules'
import { providerHost, retryAiSettings, useAiSettings, useAiSettingsFailed } from './ai-settings'
import { titleOf, useFoundTitles, useTitleMatches } from './note-titles'
import { notesSource } from './source'
import { reloadSpaces } from './spaces'
import type { TraitPage } from './traits'
import info from '../settings/space-info.module.css'
import { useUnsaved } from '../settings/unsaved'
import styles from './agent-page.module.css'

type Change = (p: Partial<AgentDraft>) => void
/** What to focus once a rule renders: its prompt (after it's added) or its moment (after it moved). */
type FocusOn = { key: AgentTrigger; what: 'prompt' | 'moment' }

/**
 * Whether Space info offers to turn the space's agent on: once AI is set up, or to resume one paused. When
 * either couldn't load, it's offered too: the section says so, with Try again, rather than hiding.
 */
export function useAgentOffered(): (space: Space) => boolean {
  const configs = useAgentConfigs()
  const configsFailed = useAgentConfigsFailed()
  const ai = useAiSettings()
  const aiFailed = useAiSettingsFailed()
  const loaded = configs !== null && ai !== null
  // Kept from a failure until both load: Try again clears it while asking, and the section would go meanwhile.
  const [unsure, setUnsure] = useState(false)
  if ((configsFailed || aiFailed) && !unsure) setUnsure(true)
  else if (loaded && unsure) setUnsure(false)
  return (space) => {
    if (configs === null || ai === null) return unsure || configsFailed || aiFailed
    return ai.configured || configs.has(space.id)
  }
}

/**
 * A space's agent (§7.8, §7.11): in Space info, a row saying how it's set that opens its page, and Activity; on the
 * page, on or off, what the space is for, its rules, and the notes it always reads, saved with its own Save. The page
 * stays mounted while closed, so its draft outlasts Back.
 */
export function AgentSection({ space, page }: { space: Space; page: TraitPage }) {
  const spaceId = space.id
  const configs = useAgentConfigs()
  const configsFailed = useAgentConfigsFailed()
  const ai = useAiSettings()
  const aiFailed = useAiSettingsFailed()
  const saved = configs?.get(spaceId)
  const [edited, setEdited] = useState<AgentDraft | null>(null)
  const [errors, setErrors] = useState<AgentErrors>({})
  const [formError, setFormError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [savedNote, setSavedNote] = useState(false)
  const headingId = useId()
  const rowLabelId = useId()
  const rowSummaryId = useId()
  const draft = edited ?? draftOf(saved)
  const dirty = edited !== null && !sameAgent(edited, draftOf(saved))
  useUnsaved('the agent', dirty)

  const change: Change = (patch) => {
    setEdited({ ...draft, ...patch })
    setSavedNote(false)
  }

  async function save(e?: FormEvent) {
    e?.preventDefault()
    const empty = emptyRules(draft)
    if (empty.length > 0) {
      setErrors(Object.fromEntries(empty.map((k) => [`triggers.${k}`, 'Say what the agent does then, or remove the rule.'])))
      return
    }
    setErrors({})
    setFormError(undefined)
    setBusy(true)
    try {
      await saveAgentConfig(spaceId, draft)
      // Its trait is the server's: the list brings it.
      notesSource().sync?.now()
      void reloadSpaces()
      // Edits made while it saved stay, still to be saved.
      setEdited((cur) => (cur === draft ? null : cur))
      setSavedNote(true)
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

  function cancel() {
    setEdited(null)
    setErrors({})
    setFormError(undefined)
  }

  const loaded = configs !== null && ai !== null
  const row = loaded ? rowText(saved, ai.configured, dirty) : null

  return (
    <section className={info.section} aria-labelledby={headingId}>
      <div className={styles.head}>
        <h3 id={headingId} className={info.caption}>
          Agent
        </h3>
        {saved && !dirty && (
          // Over Space info, which it comes back to.
          <Button size="sm" variant="ghost" onClick={() => openOver('/activity', { space: spaceId })}>
            Activity…
          </Button>
        )}
      </div>
      {row ? (
        <div className={info.group}>
          <button
            type="button"
            className={info.pageRow}
            aria-labelledby={`${headingId} ${rowLabelId}`}
            aria-describedby={rowSummaryId}
            onClick={page.show}
          >
            <span className={info.pageRowText}>
              <span id={rowLabelId} className={info.pageRowLabel}>
                {row.label}
              </span>
              <span id={rowSummaryId} className={info.pageRowSummary}>
                {row.summary}
              </span>
            </span>
            <span className={info.pageRowChevron} aria-hidden="true" />
          </button>
        </div>
      ) : (
        <div className={styles.card}>
          {configsFailed || aiFailed ? (
            <>
              <p role="alert" className={info.error}>
                Couldn’t load the agent.
              </p>
              <div>
                <Button
                  size="sm"
                  onClick={() => {
                    if (configsFailed) retryAgentConfigs()
                    if (aiFailed) retryAiSettings()
                  }}
                >
                  Try again
                </Button>
              </div>
            </>
          ) : (
            <span className={styles.muted}>Loading…</span>
          )}
        </div>
      )}
      {loaded &&
        page.slot &&
        createPortal(
          <div className={info.traitPage} hidden={!page.open}>
            <form className={info.traitPage} onSubmit={(e) => void save(e)} noValidate>
              <div className={styles.head}>
                <h2 id={page.headingId} tabIndex={-1} className={info.pageTitle}>
                  Agent
                </h2>
                {dirty && (
                  <div className={styles.actions}>
                    <Button size="sm" onClick={cancel} disabled={busy}>
                      Cancel
                    </Button>
                    <Button size="sm" variant="solid" type="submit" busy={busy}>
                      Save
                    </Button>
                  </div>
                )}
              </div>
              <p role="status" className="visually-hidden">
                {savedNote ? 'Saved' : ''}
              </p>
              <OnOff draft={draft} provider={ai.configured ? providerHost(ai.baseUrl) : null} onChange={change} />
              {formError && (
                <p role="alert" className={info.error}>
                  {formError}
                </p>
              )}
              {/* Never set up, it's one line until turned on; one turned off keeps its rules in view, to change. */}
              {(draft.enabled || saved) && (
                <div className={styles.parts}>
                  {isBlank(draft) && <Examples onPick={(ex) => change(withExample(draft, ex))} />}
                  <Purpose draft={draft} error={errors.prompt} onChange={change} />
                  <Rules draft={draft} errors={errors} onChange={change} />
                  <References spaceId={spaceId} draft={draft} error={errors.referenceNotes} onChange={change} />
                </div>
              )}
            </form>
          </div>,
          page.slot,
        )}
    </section>
  )
}

/** Space info's row for the page: the agent as saved, or that there's a draft to come back to. */
function rowText(saved: AgentConfig | undefined, aiSetUp: boolean, dirty: boolean): { label: string; summary: string } {
  if (!saved) return { label: 'Set up', summary: dirty ? 'Unsaved changes' : 'Give it a purpose and rules, then turn it on.' }
  const label = !saved.enabled ? 'Off' : aiSetUp ? 'On' : 'Paused'
  if (dirty) return { label, summary: 'Unsaved changes' }
  const n = rulesOf(draftOf(saved)).length
  return { label, summary: n === 0 ? 'No rules yet' : `${n} ${n === 1 ? 'rule' : 'rules'}` }
}

function OnOff({ draft, provider, onChange }: { draft: AgentDraft; provider: string | null; onChange: Change }) {
  const why = useId()
  return (
    <div className={styles.card}>
      <label className={styles.onOff}>
        <input
          type="checkbox"
          className={styles.check}
          aria-label="Agent"
          aria-describedby={why}
          checked={draft.enabled}
          onChange={(e) => onChange({ enabled: e.target.checked })}
        />
        <span className={styles.lead}>{draft.enabled ? 'On' : 'Off'}</span>
        <span id={why} className={styles.muted}>
          {provider === null
            ? 'Paused: AI isn’t set up on this server. Everything here is kept.'
            : draft.enabled
              ? `It acts on this space’s notes as your rules say. Notes go to ${provider}; locked notes never do. Everything it does is in Activity, and can be undone.`
              : 'Turn it on to have it act on this space’s notes as your rules say.'}
        </span>
      </label>
    </div>
  )
}

/** While nothing's written: an example's purpose and rules, to start from. Never turns it on, or saves. */
function Examples({ onPick }: { onPick: (ex: AgentExample) => void }) {
  return (
    <div className={styles.examples} role="group" aria-labelledby="agent-examples">
      <span id="agent-examples" className={styles.muted}>
        Start from an example
      </span>
      {EXAMPLES.map((ex) => (
        <button key={ex.name} type="button" className={styles.chip} onClick={() => onPick(ex)}>
          {ex.name}
        </button>
      ))}
    </div>
  )
}

function Purpose(props: { draft: AgentDraft; error: string | undefined; onChange: Change }) {
  const { draft, error, onChange } = props
  const id = useId()
  return (
    <section className={styles.section}>
      <div className={styles.sectionHead}>
        <label htmlFor={id}>
          <Kicker>What this space is for</Kicker>
        </label>
        <p id={`${id}-hint`} className={styles.muted}>
          Goes with every rule: what the notes are, and how the agent should treat them.
        </p>
      </div>
      <textarea
        id={id}
        className={[field.input, field.area, styles.prompt].join(' ')}
        placeholder="Recipes. I cook mostly vegetarian, and measure in grams."
        value={draft.prompt}
        aria-describedby={[`${id}-hint`, error && `${id}-error`].filter(Boolean).join(' ')}
        aria-invalid={error ? true : undefined}
        onChange={(e) => onChange({ prompt: e.target.value })}
      />
      {error && (
        <p id={`${id}-error`} className={field.error}>
          {error}
        </p>
      )}
    </section>
  )
}

function Rules(props: { draft: AgentDraft; errors: AgentErrors; onChange: Change }) {
  const { draft, errors, onChange } = props
  const [picking, setPicking] = useState(false)
  const [focusOn, setFocusOn] = useState<FocusOn | null>(null)
  const rules = rulesOf(draft)
  const unused = TRIGGERS.filter((t) => !rules.includes(t.key))
  const remove = (key: AgentTrigger) => {
    const triggers = { ...draft.triggers }
    delete triggers[key]
    onChange({ triggers, ...(key === 'menu' ? { menuLabel: '' } : {}) })
  }

  return (
    <section className={styles.section} aria-labelledby="agent-rules">
      <div className={styles.sectionHead}>
        <Kicker id="agent-rules">Rules</Kicker>
        <p className={styles.muted}>Each rule is a moment, and what the agent does then.</p>
      </div>
      {rules.length === 0 && <p className={styles.empty}>No rules yet. Without one, the agent does nothing.</p>}
      {TRIGGERS.filter((t) => rules.includes(t.key)).map((t) => (
        <Rule
          key={t.key}
          trigger={t.key}
          draft={draft}
          errors={errors}
          focus={focusOn?.key === t.key ? focusOn : null}
          onPrompt={(p) => onChange({ triggers: { ...draft.triggers, [t.key]: p } })}
          onMenuLabel={(menuLabel) => onChange({ menuLabel })}
          onIdea={(idea) => {
            onChange({ triggers: { ...draft.triggers, [t.key]: idea.prompt } })
            setFocusOn({ key: t.key, what: 'prompt' })
          }}
          onMove={(to) => {
            const triggers = { ...draft.triggers, [to]: draft.triggers[t.key] ?? '' }
            delete triggers[t.key]
            onChange({ triggers, ...(t.key === 'menu' ? { menuLabel: '' } : {}) })
            setFocusOn({ key: to, what: 'moment' })
          }}
          onRemove={() => remove(t.key)}
        />
      ))}
      {picking ? (
        <div className={[styles.card, styles.picker].join(' ')} role="group" aria-label="Add a rule">
          {GROUPS.map((g) => {
            const options = unused.filter((t) => t.group === g.key)
            if (options.length === 0) return null
            return (
              <div key={g.key} className={styles.pickerGroup}>
                <p className={styles.pickerTitle}>{g.title}</p>
                {options.map((t) => (
                  <button
                    key={t.key}
                    type="button"
                    className={styles.pick}
                    onClick={() => {
                      onChange({ triggers: { ...draft.triggers, [t.key]: '' } })
                      setFocusOn({ key: t.key, what: 'prompt' })
                      setPicking(false)
                    }}
                  >
                    <span className={styles.pickName}>{t.rule}</span>
                    <span className={styles.muted}>{t.when}</span>
                  </button>
                ))}
              </div>
            )
          })}
          <div>
            <Button size="sm" onClick={() => setPicking(false)}>
              Close
            </Button>
          </div>
        </div>
      ) : (
        unused.length > 0 && (
          <div>
            <Button size="md" onClick={() => setPicking(true)}>
              + Add rule
            </Button>
          </div>
        )
      )}
    </section>
  )
}

function Rule(props: {
  trigger: AgentTrigger
  draft: AgentDraft
  errors: AgentErrors
  /** A new object each time it's to be focused. */
  focus: FocusOn | null
  onPrompt: (p: string) => void
  onMenuLabel: (l: string) => void
  onIdea: (idea: Idea) => void
  onMove: (to: AgentTrigger) => void
  onRemove: () => void
}) {
  const { trigger, draft, errors, focus, onPrompt, onMenuLabel, onIdea, onMove, onRemove } = props
  const t = TRIGGERS.find((x) => x.key === trigger)
  const prompt = draft.triggers[trigger] ?? ''
  const id = useId()
  const box = useRef<HTMLTextAreaElement>(null)
  const moment = useRef<HTMLSelectElement>(null)
  const error = errors[`triggers.${trigger}`]
  const rules = rulesOf(draft)
  useEffect(() => {
    if (focus?.what === 'moment') moment.current?.focus()
    const el = box.current
    if (focus?.what !== 'prompt' || !el) return
    el.focus()
    el.setSelectionRange(el.value.length, el.value.length)
  }, [focus])
  if (!t) return null
  const idea = prompt.trim() ? undefined : t.idea
  return (
    <article className={[styles.card, styles.rule].join(' ')} aria-label={t.rule}>
      <div className={styles.ruleHead}>
        <div>
          <h3 className={styles.ruleTitle}>
            <span aria-hidden>When</span>
            <select
              ref={moment}
              className={styles.moment}
              aria-label="When"
              value={trigger}
              onChange={(e) => onMove(e.target.value as AgentTrigger)}
            >
              {GROUPS.map((g) => (
                <optgroup key={g.key} label={g.title}>
                  {TRIGGERS.filter((o) => o.group === g.key && (o.key === trigger || !rules.includes(o.key))).map((o) => (
                    <option key={o.key} value={o.key}>
                      {o.rule.replace(/^When /, '')}
                    </option>
                  ))}
                </optgroup>
              ))}
            </select>
          </h3>
          <p className={styles.muted}>{t.when}</p>
        </div>
        <Button size="sm" variant="ghost" onClick={onRemove} aria-label={`Remove rule: ${t.rule}`}>
          Remove
        </Button>
      </div>
      {trigger === 'menu' && (
        <Field
          label="Command name, as the menu shows it"
          placeholder="Fix typos"
          autoComplete="off"
          maxLength={40}
          value={draft.menuLabel}
          onChange={(e) => onMenuLabel(e.target.value)}
          error={errors.menuLabel}
        />
      )}
      <label htmlFor={id} className="visually-hidden">
        {t.rule}: what the agent does
      </label>
      <textarea
        id={id}
        ref={box}
        className={[field.input, field.area, styles.prompt].join(' ')}
        placeholder="What should the agent do?"
        value={prompt}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : undefined}
        onChange={(e) => onPrompt(e.target.value)}
      />
      {error && (
        <p id={`${id}-error`} className={field.error}>
          {error}
        </p>
      )}
      {idea && (
        <div className={styles.ideas}>
          <span id={`${id}-idea`} className={styles.muted}>
            Idea
          </span>
          <button type="button" className={styles.chip} title={idea.prompt} aria-describedby={`${id}-idea`} onClick={() => onIdea(idea)}>
            {idea.title}
          </button>
        </div>
      )}
    </article>
  )
}

function References(props: { spaceId: string; draft: AgentDraft; error: string | undefined; onChange: Change }) {
  const { spaceId, draft, error, onChange } = props
  const [title, setTitle] = useState('')
  const refs = draft.referenceNotes
  const listId = useId()
  const matches = useTitleMatches(spaceId, title, refs)
  const found = useFoundTitles(spaceId, refs)
  const room = refs.length < MAX_REFERENCES
  const add = (t: string) => {
    t = titleOf(t)
    if (!t || refs.includes(t) || !room) return
    onChange({ referenceNotes: [...refs, t] })
    setTitle('')
  }
  return (
    <section className={styles.section} aria-labelledby="agent-refs">
      <div className={styles.sectionHead}>
        <Kicker id="agent-refs">Notes it always reads</Kicker>
        <p className={styles.muted}>
          Notes the agent gets with every rule, by title, such as a Digest it keeps. It acts faster when it needn’t look for
          them. Up to {MAX_REFERENCES}.
        </p>
      </div>
      {refs.length > 0 && (
        <ul className={styles.refs} aria-label="Notes it always reads">
          {refs.map((r) => (
            <li key={r} className={styles.ref}>
              {r}
              {found.get(r) === false && <span className={styles.refMissing}>not here yet</span>}
              <button
                type="button"
                className={styles.refRemove}
                aria-label={`Stop reading ${r}`}
                onClick={() => onChange({ referenceNotes: refs.filter((x) => x !== r) })}
              >
                ×
              </button>
            </li>
          ))}
        </ul>
      )}
      {[...found.values()].includes(false) && (
        <p className={[styles.muted, styles.hint].join(' ')}>
          A note that’s not here yet is read once there is one, such as one a rule has the agent make.
        </p>
      )}
      {room && (
        <div className={styles.refAdd}>
          <Field
            label="Note title"
            hideLabel
            placeholder="Note title"
            autoComplete="off"
            maxLength={200}
            list={listId}
            value={title}
            error={error}
            onChange={(e) => setTitle(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                add(title)
              }
            }}
          />
          <datalist id={listId}>
            {matches.map((m) => (
              <option key={m} value={m} />
            ))}
          </datalist>
          <Button size="md" onClick={() => add(title)}>
            Add
          </Button>
        </div>
      )}
    </section>
  )
}
