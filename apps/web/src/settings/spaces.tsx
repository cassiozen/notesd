import { useEffect, useId, useState, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import { spaces as spacesApi } from '../api/spaces'
import type { Space } from '../api/types'
import { genericMessage } from '../auth/errors'
import { saveAgentConfig, useAgentConfigs } from '../notes/agent-config'
import { providerHost, useAiSettings } from '../notes/ai-settings'
import { notesSource } from '../notes/source'
import { SpaceIcon, SpaceIconPicker } from '../notes/space-icon'
import { DEFAULT_SPACE, deleteSpace, putSpace, reloadSpaces, useSpaces } from '../notes/spaces'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { Field } from '../ui/field'
import { Kicker } from '../ui/kicker'
import { Surface } from '../ui/surface'
import {
  AgentSection,
  agentFieldError,
  draftOf,
  emptyAgent,
  hasAgent,
  sameAgent,
  type AgentDraft,
  type AgentErrors,
} from './agent-section'
import styles from './settings.module.css'

const notes = (n: number) => `${n} ${n === 1 ? 'note' : 'notes'}`

/** Spaces (§7.7): create, edit and delete them. The sidebar's switcher can make one too. */
export function SpacesCard({ onCreated }: { onCreated: (id: string) => void }) {
  const spaces = useSpaces()
  const [editing, setEditing] = useState<Space | 'new' | null>(null)
  const [deleting, setDeleting] = useState<string | null>(null)
  const [error, setError] = useState<string>()
  // Fresh each time: another device may have added notes or spaces.
  useEffect(() => void reloadSpaces(), [])

  async function remove(id: string) {
    setDeleting(id)
    setError(undefined)
    try {
      await deleteSpace(id)
    } catch (err) {
      const notEmpty = err instanceof ApiError && err.code === 'space_not_empty'
      setError(notEmpty ? 'Notes arrived in that space from another device. Move or delete them first.' : genericMessage(err))
      // Its row then shows those notes, and Delete turns off.
      if (notEmpty) void reloadSpaces()
    } finally {
      setDeleting(null)
    }
  }

  return (
    <Surface as="section" className={styles.devices} aria-labelledby="spaces-kicker">
      <div className={styles.devicesHead}>
        <Kicker id="spaces-kicker">Spaces</Kicker>
        <Button size="sm" onClick={() => setEditing('new')} disabled={!spaces}>
          New space
        </Button>
      </div>
      <p className={styles.deviceNote}>
        Keep separate contexts, like Work and Personal, apart. Each space has its own notes and tags, and new notes go to
        the one you’re in.
      </p>
      {error && (
        <p role="alert" className={[styles.deviceNote, styles.error].join(' ')}>
          {error}
        </p>
      )}
      <ul className={styles.deviceList} aria-label="Spaces">
        {spaces?.map((s) => {
          const why =
            s.id === DEFAULT_SPACE
              ? 'Where new installs start. It can be renamed, not deleted.'
              : s.noteCount > 0
                ? 'Move or delete its notes to delete it.'
                : null
          return (
            <li key={s.id} className={styles.device}>
              <SpaceIcon space={s} size={28} />
              <span className={styles.deviceText}>
                <span className={styles.deviceName}>{s.name}</span>
                <span className={styles.muted}>
                  {notes(s.noteCount)}
                  {why && ` · ${why}`}
                </span>
              </span>
              <Button size="sm" onClick={() => setEditing(s)} aria-label={`Edit ${s.name}`}>
                Edit
              </Button>
              {s.id !== DEFAULT_SPACE && (
                <Button
                  size="sm"
                  variant="danger-text"
                  busy={deleting === s.id}
                  disabled={s.noteCount > 0}
                  onClick={() => void remove(s.id)}
                  aria-label={`Delete ${s.name}`}
                >
                  Delete
                </Button>
              )}
            </li>
          )
        })}
      </ul>
      {editing === 'new' && spaces && (
        <NewSpaceDialog
          onClose={() => setEditing(null)}
          onCreated={(id) => {
            setEditing(null)
            onCreated(id)
          }}
        />
      )}
      {editing && editing !== 'new' && <EditDialog space={editing} onClose={() => setEditing(null)} />}
    </Surface>
  )
}

/** A new space, with a name and an icon. */
export function NewSpaceDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (id: string) => void }) {
  const titleId = useId()
  const [name, setName] = useState('')
  const [icon, setIcon] = useState<string | null>(null)
  const [errors, setErrors] = useState<{ name?: string; icon?: string; form?: string }>({})
  const [busy, setBusy] = useState(false)
  const agent = useAgentSetup(undefined)
  // Once, so a failed agent save doesn't make a second space.
  const [id] = useState(() => crypto.randomUUID())
  const [created, setCreated] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const found = {
      ...(!name.trim() && { name: 'Name the new space.' }),
      ...(icon === null && { icon: 'Choose an icon.' }),
    }
    setErrors(found)
    agent.setErrors({})
    if (Object.keys(found).length > 0 || icon === null) return
    setBusy(true)
    try {
      if (!created) {
        if (hasAgent(agent.draft) && notesSource().sync) {
          // Notes kept on this device create the space there and send it on the next sync; the agent's setup
          // needs it on the server now. Same id and name, so that sync changes nothing.
          await spacesApi.put(id, name.trim(), icon)
        }
        await putSpace(id, name.trim(), icon)
        setCreated(true)
      }
      if (hasAgent(agent.draft)) await saveAgentConfig(id, agent.draft)
      onCreated(id)
    } catch (err) {
      const fields = agentFieldError(err)
      if (fields) agent.setErrors(fields)
      else setErrors({ form: genericMessage(err) })
      setBusy(false)
    }
  }

  return (
    <Dialog labelledBy={titleId} onClose={onClose}>
      <form className={styles.dialog} onSubmit={(e) => void submit(e)} noValidate>
        <h2 id={titleId} className={styles.dialogTitle}>
          New space
        </h2>
        <Field
          label="Name"
          placeholder="Work"
          autoComplete="off"
          maxLength={64}
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={errors.name}
          autoFocus
        />
        <SpaceIconPicker value={icon} onChange={setIcon} error={errors.icon} />
        {agent.shown && (
          <AgentSection
            draft={agent.draft}
            onChange={agent.setDraft}
            errors={agent.errors}
            provider={agent.provider}
            isNew
          />
        )}
        {errors.form && (
          <p role="alert" className={styles.error}>
            {errors.form}
          </p>
        )}
        <div className={styles.dialogActions}>
          <Button size="md" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button variant="solid" size="md" type="submit" busy={busy}>
            Create
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

/** A space's name and icon. */
function EditDialog({ space, onClose }: { space: Space; onClose: () => void }) {
  const titleId = useId()
  const [name, setName] = useState(space.name)
  const [icon, setIcon] = useState(space.icon)
  const [error, setError] = useState<string>()
  const [formError, setFormError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const agent = useAgentSetup(space.id)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!name.trim()) {
      setError('Enter a name.')
      return
    }
    setError(undefined)
    setFormError(undefined)
    agent.setErrors({})
    setBusy(true)
    try {
      if (name.trim() !== space.name || icon !== space.icon) await putSpace(space.id, name.trim(), icon)
      if (agent.changed) await saveAgentConfig(space.id, agent.draft)
      onClose()
    } catch (err) {
      const fields = agentFieldError(err)
      if (fields) agent.setErrors(fields)
      else if (err instanceof ApiError && err.code === 'not_found')
        setFormError('This space hasn’t reached the server yet. Try again once it has synced.')
      else setFormError(genericMessage(err))
      setBusy(false)
    }
  }

  return (
    <Dialog labelledBy={titleId} onClose={onClose}>
      <form className={styles.dialog} onSubmit={(e) => void submit(e)} noValidate>
        <h2 id={titleId} className={styles.dialogTitle}>
          Edit space
        </h2>
        <Field
          label="Name"
          autoComplete="off"
          maxLength={64}
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={error}
          autoFocus
        />
        <SpaceIconPicker value={icon} onChange={setIcon} />
        {agent.shown && (
          <AgentSection
            draft={agent.draft}
            onChange={agent.setDraft}
            errors={agent.errors}
            provider={agent.provider}
            isNew={false}
          />
        )}
        {formError && (
          <p role="alert" className={styles.error}>
            {formError}
          </p>
        )}
        <div className={styles.dialogActions}>
          <Button size="md" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button variant="solid" size="md" type="submit" busy={busy}>
            Save
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

/**
 * A space's agent as a dialog edits it. Shown once the agents have loaded (they need the server) and either AI is
 * set up or the space already has an agent, which then shows as paused.
 */
function useAgentSetup(spaceId: string | undefined) {
  const configs = useAgentConfigs()
  const ai = useAiSettings()
  const saved = spaceId ? configs?.get(spaceId) : undefined
  const initial = draftOf(saved)
  const [draft, setDraft] = useState<AgentDraft | null>(null)
  const [errors, setErrors] = useState<AgentErrors>({})
  const current = draft ?? initial
  return {
    shown: configs !== null && ai !== null && (ai.configured || (saved !== undefined && hasAgent(initial))),
    draft: current,
    setDraft,
    changed: draft !== null && !sameAgent(current, saved ? initial : emptyAgent),
    errors,
    setErrors,
    provider: ai?.configured ? providerHost(ai.baseUrl) : null,
  }
}
