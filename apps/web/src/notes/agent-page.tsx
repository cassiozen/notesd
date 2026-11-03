import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import type { AgentTrigger } from '../api/agent'
import { ApiError } from '../api/client'
import { genericMessage } from '../auth/errors'
import { navigate } from '../router'
import { Button } from '../ui/button'
import { Field } from '../ui/field'
import { Kicker } from '../ui/kicker'
import { Sheet } from '../ui/sheet'
import { Surface } from '../ui/surface'
import field from '../ui/field.module.css'
import { saveAgentConfig, useAgentConfigs } from './agent-config'
import { EXAMPLES } from './agent-examples'
import {
  GROUPS,
  SUGGESTED_MENU_LABEL,
  TRIGGERS,
  agentFieldError,
  draftOf,
  rulesOf,
  sameAgent,
  type AgentDraft,
  type AgentErrors,
} from './agent-rules'
import { providerHost, useAiSettings } from './ai-settings'
import { spaceName, useSpaces } from './spaces'
import styles from './agent-page.module.css'

const MAX_REFERENCES = 5

/** A space's agent: on or off, what the space is for, its rules, and the notes it always reads. */
export function AgentSheet({ spaceId, onClose }: { spaceId: string; onClose: () => void }) {
  const configs = useAgentConfigs()
  const ai = useAiSettings()
  const spaces = useSpaces()
  const saved = configs?.get(spaceId)
  const [edited, setEdited] = useState<AgentDraft | null>(null)
  const [errors, setErrors] = useState<AgentErrors>({})
  const [formError, setFormError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [savedNote, setSavedNote] = useState(false)
  const titleId = useId()
  const draft = edited ?? draftOf(saved)
  const dirty = edited !== null && !sameAgent(edited, draftOf(saved))
  const name = spaceName(spaces, spaceId)
  const loading = configs === null || ai === null

  const change = (patch: Partial<AgentDraft>) => {
    setEdited({ ...draft, ...patch })
    setSavedNote(false)
  }

  const close = () => {
    if (dirty && !window.confirm('Discard your changes to this agent?')) return
    onClose()
  }

  async function save(e?: FormEvent) {
    e?.preventDefault()
    setErrors({})
    setFormError(undefined)
    setBusy(true)
    try {
      await saveAgentConfig(spaceId, draft)
      setEdited(null)
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

  return (
    <Sheet labelledBy={titleId} onClose={close}>
      <form className={styles.form} onSubmit={(e) => void save(e)} noValidate>
        <div className={styles.top}>
          <h2 id={titleId}>{name} agent</h2>
          <div className={styles.actions}>
            {saved && !dirty && (
              <Button size="md" onClick={() => navigate('/activity', { state: { space: spaceId } })}>
                Activity…
              </Button>
            )}
            {dirty ? (
              <>
                <Button size="md" onClick={() => setEdited(null)} disabled={busy}>
                  Cancel
                </Button>
                <Button size="md" variant="solid" type="submit" busy={busy}>
                  Save
                </Button>
              </>
            ) : (
              <Button size="md" variant="solid" onClick={onClose}>
                Done
              </Button>
            )}
          </div>
        </div>
        <p role="status" className="visually-hidden">
          {savedNote ? 'Saved' : ''}
        </p>
        {loading ? null : !ai.configured && !saved ? (
          <Surface className={styles.card}>
            <p className={styles.lead}>AI isn’t set up on this server yet.</p>
            <p className={styles.muted}>An admin can set it up in Settings → Integrations. Then this space can have an agent.</p>
          </Surface>
        ) : (
          <div className={styles.page}>
            <OnOff draft={draft} provider={ai.configured ? providerHost(ai.baseUrl) : null} onChange={change} />
            {formError && (
              <p role="alert" className={styles.error}>
                {formError}
              </p>
            )}
            {rulesOf(draft).length === 0 && !draft.prompt.trim() && <Examples onPick={(d) => change(d)} />}
            <Purpose draft={draft} error={errors.prompt} onChange={change} />
            <Rules draft={draft} errors={errors} onChange={change} />
            <References draft={draft} error={errors.referenceNotes} onChange={change} />
          </div>
        )}
      </form>
    </Sheet>
  )
}

function OnOff({ draft, provider, onChange }: { draft: AgentDraft; provider: string | null; onChange: (p: Partial<AgentDraft>) => void }) {
  const why = useId()
  return (
    <Surface className={[styles.card, styles.onOff].join(' ')}>
      <div>
        <p className={styles.lead}>{draft.enabled ? 'On' : 'Off'}</p>
        <p id={why} className={styles.muted}>
          {provider === null
            ? 'Paused: AI isn’t set up on this server. Everything here is kept.'
            : draft.enabled
              ? `It acts on this space’s notes as your rules say. Notes go to ${provider}; locked notes never do. Everything it does is in Activity, and can be undone.`
              : 'Turn it on to have it act on this space’s notes as your rules say.'}
        </p>
      </div>
      <input
        type="checkbox"
        role="switch"
        className={styles.switch}
        aria-label="Agent"
        aria-describedby={why}
        checked={draft.enabled}
        onChange={(e) => onChange({ enabled: e.target.checked })}
      />
    </Surface>
  )
}

function Examples({ onPick }: { onPick: (d: AgentDraft) => void }) {
  return (
    <section className={styles.section} aria-labelledby="agent-examples">
      <div className={styles.sectionHead}>
        <Kicker id="agent-examples">Start from an example</Kicker>
      </div>
      <div className={styles.examples}>
        {EXAMPLES.map((ex) => (
          <button key={ex.name} type="button" className={styles.example} onClick={() => onPick(ex.draft)}>
            <span className={styles.exampleName}>{ex.name}</span>
            <span className={styles.muted}>{ex.blurb}</span>
          </button>
        ))}
      </div>
    </section>
  )
}

function Purpose({ draft, error, onChange }: { draft: AgentDraft; error: string | undefined; onChange: (p: Partial<AgentDraft>) => void }) {
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

function Rules({ draft, errors, onChange }: { draft: AgentDraft; errors: AgentErrors; onChange: (p: Partial<AgentDraft>) => void }) {
  const [picking, setPicking] = useState(false)
  const [focusOn, setFocusOn] = useState<AgentTrigger | null>(null)
  const rules = rulesOf(draft)
  const unused = TRIGGERS.filter((t) => !rules.includes(t.key))
  const setPrompt = (key: AgentTrigger, prompt: string | undefined) => {
    const triggers = { ...draft.triggers }
    if (prompt === undefined) delete triggers[key]
    else triggers[key] = prompt
    onChange({ triggers, ...(key === 'menu' && prompt === undefined ? { menuLabel: '' } : {}) })
  }

  return (
    <section className={styles.section} aria-labelledby="agent-rules">
      <div className={styles.sectionHead}>
        <Kicker id="agent-rules">Rules</Kicker>
        <p className={styles.muted}>Each rule is a moment, and what the agent does then. Without rules, it does nothing.</p>
      </div>
      {rules.map((key) => (
        <Rule
          key={key}
          trigger={key}
          prompt={draft.triggers[key] ?? ''}
          menuLabel={draft.menuLabel}
          errors={errors}
          autoFocus={focusOn === key}
          onPrompt={(p) => setPrompt(key, p)}
          onMenuLabel={(menuLabel) => onChange({ menuLabel })}
          onRemove={() => setPrompt(key, undefined)}
        />
      ))}
      {picking ? (
        <Surface className={[styles.card, styles.picker].join(' ')} role="group" aria-label="Add a rule">
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
                      onChange({
                        triggers: { ...draft.triggers, [t.key]: t.suggestion },
                        ...(t.key === 'menu' && !draft.menuLabel.trim() ? { menuLabel: SUGGESTED_MENU_LABEL } : {}),
                      })
                      setFocusOn(t.key)
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
        </Surface>
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
  prompt: string
  menuLabel: string
  errors: AgentErrors
  autoFocus: boolean
  onPrompt: (p: string) => void
  onMenuLabel: (l: string) => void
  onRemove: () => void
}) {
  const { trigger, prompt, menuLabel, errors, autoFocus, onPrompt, onMenuLabel, onRemove } = props
  const t = TRIGGERS.find((x) => x.key === trigger)
  const id = useId()
  const box = useRef<HTMLTextAreaElement>(null)
  const error = errors[`triggers.${trigger}`]
  // A rule just added starts from a suggestion: selected, so typing replaces it.
  useEffect(() => {
    if (!autoFocus) return
    box.current?.focus()
    box.current?.select()
  }, [autoFocus])
  if (!t) return null
  return (
    <Surface as="article" className={[styles.card, styles.rule].join(' ')} aria-labelledby={`${id}-title`}>
      <div className={styles.ruleHead}>
        <div>
          <h3 id={`${id}-title`} className={styles.ruleTitle}>
            {t.rule}
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
          value={menuLabel}
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
    </Surface>
  )
}

function References({ draft, error, onChange }: { draft: AgentDraft; error: string | undefined; onChange: (p: Partial<AgentDraft>) => void }) {
  const [title, setTitle] = useState('')
  const refs = draft.referenceNotes
  const add = () => {
    const t = title.trim()
    if (!t || refs.includes(t) || refs.length >= MAX_REFERENCES) return
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
      {refs.length < MAX_REFERENCES && (
        <div className={styles.refAdd}>
          <Field
            label="Note title"
            hideLabel
            placeholder="Note title"
            autoComplete="off"
            maxLength={200}
            value={title}
            error={error}
            onChange={(e) => setTitle(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                add()
              }
            }}
          />
          <Button size="md" onClick={add}>
            Add
          </Button>
        </div>
      )}
    </section>
  )
}
