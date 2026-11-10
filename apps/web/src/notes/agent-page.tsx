import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react'
import type { AgentTrigger } from '../api/agent'
import { ApiError } from '../api/client'
import type { Space } from '../api/types'
import { genericMessage } from '../auth/errors'
import { openOver } from '../router'
import { Button } from '../ui/button'
import { Field } from '../ui/field'
import { Kicker } from '../ui/kicker'
import field from '../ui/field.module.css'
import { retryAgentConfigs, saveAgentConfig, useAgentConfigs, useAgentConfigsFailed } from './agent-config'
import {
  EXAMPLES,
  hasExample,
  hasIdea,
  ideasFor,
  readsNeeded,
  readsOf,
  withExample,
  withIdea,
  type AgentExample,
  type Idea,
} from './agent-examples'
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
} from './agent-rules'
import { providerHost, retryAiSettings, useAiSettings, useAiSettingsFailed } from './ai-settings'
import { titleOf, useFoundTitles, useTitleMatches } from './note-titles'
import { notesSource } from './source'
import { reloadSpaces } from './spaces'
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
 * A space's agent: on or off, what the space is for, its rules, and the notes it always reads. The agent
 * trait's part of Space info (§7.8, §7.11), on or off: changed in place, saved with its own Save.
 */
export function AgentSection({ space }: { space: Space }) {
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
  const [showing, setShowing] = useState<AgentExample | null>(null)
  const headingId = useId()
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

  return (
    <section aria-labelledby={headingId}>
      <form className={info.section} onSubmit={(e) => void save(e)} noValidate>
        <div className={styles.head}>
          <h3 id={headingId} className={info.caption}>
            Agent
          </h3>
          <div className={styles.actions}>
            {saved && !dirty && (
              // Over Space info, which it comes back to.
              <Button size="sm" variant="ghost" onClick={() => openOver('/activity', { space: spaceId })}>
                Activity…
              </Button>
            )}
            {dirty && (
              <>
                <Button size="sm" onClick={cancel} disabled={busy}>
                  Cancel
                </Button>
                <Button size="sm" variant="solid" type="submit" busy={busy}>
                  Save
                </Button>
              </>
            )}
          </div>
        </div>
        <p role="status" className="visually-hidden">
          {savedNote ? 'Saved' : ''}
        </p>
        {(configs === null || ai === null) && (
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
        {configs !== null && ai !== null && (
          <>
            <OnOff draft={draft} provider={ai.configured ? providerHost(ai.baseUrl) : null} onChange={change} />
            {formError && (
              <p role="alert" className={info.error}>
                {formError}
              </p>
            )}
            {/* Never set up, it's one line until turned on; one turned off keeps its rules in view, to change. */}
            {(draft.enabled || saved) && (
              <div className={styles.parts}>
                <Examples
                  showing={showing}
                  draft={draft}
                  onShow={setShowing}
                  onUseAll={(ex) => {
                    change(withExample(draft, ex))
                    setShowing(null)
                  }}
                />
                <Purpose draft={draft} example={showing} error={errors.prompt} onChange={change} />
                <Rules draft={draft} example={showing} errors={errors} onChange={change} />
                <References spaceId={spaceId} draft={draft} example={showing} error={errors.referenceNotes} onChange={change} />
              </div>
            )}
          </>
        )}
      </form>
    </section>
  )
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

/**
 * Examples to learn from: one picked shows its parts where they'd go, each to take or leave. Taking
 * one never turns the agent on, or saves.
 */
function Examples(props: {
  showing: AgentExample | null
  draft: AgentDraft
  onShow: (ex: AgentExample | null) => void
  onUseAll: (ex: AgentExample) => void
}) {
  const { showing, draft, onShow, onUseAll } = props
  return (
    <section className={[styles.examples, showing && styles.examplesShowing].filter(Boolean).join(' ')} aria-labelledby="agent-examples">
      <div className={styles.exampleRow}>
        <span id="agent-examples" className={styles.muted}>
          Learn from an example
        </span>
        {EXAMPLES.map((ex) => (
          <button
            key={ex.name}
            type="button"
            className={styles.exampleChip}
            aria-pressed={showing?.name === ex.name}
            onClick={() => onShow(showing?.name === ex.name ? null : ex)}
          >
            {ex.name}
          </button>
        ))}
      </div>
      {showing && (
        <div className={styles.exampleBar}>
          <p className={styles.muted}>
            {hasExample(draft, showing) ? `Everything from ${showing.name} is in.` : `${showing.blurb} Take any part of it below.`}
          </p>
          <div className={styles.actions}>
            {!hasExample(draft, showing) && (
              <Button size="sm" onClick={() => onUseAll(showing)}>
                Use all
              </Button>
            )}
            <Button size="sm" variant="ghost" onClick={() => onShow(null)}>
              Close
            </Button>
          </div>
        </div>
      )}
    </section>
  )
}

/** A part of an example, beside where it would go, to take. */
function Borrow(props: { label: string; from: string; title?: string; text: string; action: string; onTake: () => void; children?: ReactNode }) {
  const { label, from, title, text, action, onTake, children } = props
  return (
    <aside className={styles.borrow} aria-label={label}>
      <div className={styles.borrowHead}>
        <span className={styles.borrowFrom}>
          {from}
          {title && <span className={styles.borrowTitle}> · {title}</span>}
        </span>
        <Button size="sm" onClick={onTake}>
          {action}
        </Button>
      </div>
      {children}
      <p className={styles.borrowText}>{text}</p>
    </aside>
  )
}

function Purpose(props: { draft: AgentDraft; example: AgentExample | null; error: string | undefined; onChange: Change }) {
  const { draft, example, error, onChange } = props
  const id = useId()
  const box = useRef<HTMLTextAreaElement>(null)
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
        ref={box}
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
      {example && example.prompt !== draft.prompt && (
        <Borrow
          label={`${example.name}’s purpose`}
          from={`${example.name}’s purpose`}
          text={example.prompt}
          action={draft.prompt.trim() ? 'Use instead' : 'Use'}
          onTake={() => {
            onChange({ prompt: example.prompt })
            box.current?.focus()
          }}
        />
      )}
    </section>
  )
}

function Rules(props: {
  draft: AgentDraft
  example: AgentExample | null
  errors: AgentErrors
  onChange: Change
}) {
  const { draft, example, errors, onChange } = props
  const [picking, setPicking] = useState(false)
  const [focusOn, setFocusOn] = useState<FocusOn | null>(null)
  const rules = rulesOf(draft)
  const unused = TRIGGERS.filter((t) => !rules.includes(t.key))
  const remove = (key: AgentTrigger) => {
    const triggers = { ...draft.triggers }
    delete triggers[key]
    onChange({ triggers, ...(key === 'menu' ? { menuLabel: '' } : {}) })
  }
  const take = (key: AgentTrigger, idea: Idea) => {
    onChange(withIdea(draft, key, idea))
    setFocusOn({ key, what: 'prompt' })
  }

  return (
    <section className={styles.section} aria-labelledby="agent-rules">
      <div className={styles.sectionHead}>
        <Kicker id="agent-rules">Rules</Kicker>
        <p className={styles.muted}>Each rule is a moment, and what the agent does then.</p>
      </div>
      {rules.length === 0 && !example && <p className={styles.empty}>No rules yet. Without one, the agent does nothing.</p>}
      {TRIGGERS.map((t) => {
        const has = rules.includes(t.key)
        const theirs = example?.rules[t.key]
        const offer = theirs && !hasIdea(draft, t.key, theirs) ? theirs : undefined
        if (!has && !offer) return null
        return (
          <div key={t.key} className={styles.ruleSlot}>
            {has && (
              <Rule
                trigger={t.key}
                draft={draft}
                errors={errors}
                focus={focusOn?.key === t.key ? focusOn : null}
                onPrompt={(p) => onChange({ triggers: { ...draft.triggers, [t.key]: p } })}
                onMenuLabel={(menuLabel) => onChange({ menuLabel })}
                onIdea={(idea) => take(t.key, idea)}
                onMove={(to) => {
                  const triggers = { ...draft.triggers, [to]: draft.triggers[t.key] ?? '' }
                  delete triggers[t.key]
                  onChange({ triggers, ...(t.key === 'menu' ? { menuLabel: '' } : {}) })
                  setFocusOn({ key: to, what: 'moment' })
                }}
                onRemove={() => remove(t.key)}
              />
            )}
            {offer && example && (
              <Borrow
                label={`${example.name}’s rule: ${t.rule}`}
                from={has ? `${example.name}’s version` : t.rule}
                title={offer.title}
                text={offer.prompt}
                action={has ? 'Use instead' : 'Add'}
                onTake={() => take(t.key, offer)}
              >
                {t.key === 'menu' && offer.menuLabel && <p className={styles.muted}>Command: {offer.menuLabel}</p>}
              </Borrow>
            )}
          </div>
        )
      })}
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
  const ideas = prompt.trim() ? [] : ideasFor(trigger)
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
      {ideas.length > 0 && (
        <div className={styles.ideas} role="group" aria-label="Ideas">
          <span className={styles.muted}>Ideas</span>
          {ideas.map(({ idea, from }) => (
            <button
              key={idea.prompt}
              type="button"
              className={styles.idea}
              title={idea.prompt}
              aria-label={from ? `${idea.title}, from ${from}` : idea.title}
              onClick={() => onIdea(idea)}
            >
              {idea.title}
              {from && <span className={styles.ideaFrom}>{from}</span>}
            </button>
          ))}
        </div>
      )}
    </article>
  )
}

function References(props: { spaceId: string; draft: AgentDraft; example: AgentExample | null; error: string | undefined; onChange: Change }) {
  const { spaceId, draft, example, error, onChange } = props
  const [title, setTitle] = useState('')
  const refs = draft.referenceNotes
  const listId = useId()
  const matches = useTitleMatches(spaceId, title, refs)
  const found = useFoundTitles(spaceId, refs)
  const shown = example ? readsOf(example) : []
  // An example's while it's shown; else one a rule taken from an example needs and was left out.
  const offers = [...new Set([...shown, ...readsNeeded(draft)])].filter((r) => !refs.includes(r))
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
      {(refs.length > 0 || (room && offers.length > 0)) && (
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
          {room &&
            offers.map((r) => {
              const from = example && shown.includes(r) ? example.name : 'a rule needs it'
              return (
                <li key={`offer-${r}`}>
                  <button type="button" className={styles.refOffer} aria-label={`Read ${r}: ${from}`} onClick={() => add(r)}>
                    + {r}
                    <span className={styles.ideaFrom}>{from}</span>
                  </button>
                </li>
              )
            })}
        </ul>
      )}
      {!room && offers.length > 0 && (
        <p role="status" className={[styles.error, styles.hint].join(' ')}>
          No room for {offers.join(', ')}, which {offers.length === 1 ? 'a rule needs' : 'rules need'}: take one off to add it.
        </p>
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
