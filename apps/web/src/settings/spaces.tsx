import { useEffect, useId, useState, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import { spaces as spacesApi } from '../api/spaces'
import type { Space } from '../api/types'
import auth from '../auth/auth.module.css'
import { genericMessage } from '../auth/errors'
import { useAgentConfigs } from '../notes/agent-config'
import { rulesOf, draftOf } from '../notes/agent-rules'
import { useAiSettings } from '../notes/ai-settings'
import { notesSource } from '../notes/source'
import { SpaceIcon, SpaceIconPicker } from '../notes/space-icon'
import { DEFAULT_SPACE, deleteSpace, putSpace, reloadSpaces, useSpaces } from '../notes/spaces'
import { agentPath, openOver } from '../router'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { Field } from '../ui/field'
import { Surface } from '../ui/surface'
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
    <Surface as="section" className={styles.devices} aria-label="Spaces">
      <div className={styles.devicesHead}>
        <span className={styles.muted}>{spaces && (spaces.length === 1 ? '1 space' : `${spaces.length} spaces`)}</span>
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
  const offerAgent = useAgentRow(undefined).shown
  const [agentNext, setAgentNext] = useState(false)
  const [id] = useState(() => crypto.randomUUID())

  async function submit(e: FormEvent) {
    e.preventDefault()
    const found = {
      ...(!name.trim() && { name: 'Name the new space.' }),
      ...(icon === null && { icon: 'Choose an icon.' }),
    }
    setErrors(found)
    if (Object.keys(found).length > 0 || icon === null) return
    const withAgent = offerAgent && agentNext
    setBusy(true)
    try {
      if (withAgent && notesSource().sync) {
        // Notes kept on this device create the space there and send it on the next sync; the agent's setup
        // needs it on the server now. Same id and name, so that sync changes nothing.
        await spacesApi.put(id, name.trim(), icon)
      }
      await putSpace(id, name.trim(), icon)
      onCreated(id)
      if (withAgent) openOver(agentPath(id))
    } catch (err) {
      setErrors({ form: genericMessage(err) })
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
        {offerAgent && (
          <label className={auth.keep}>
            <input type="checkbox" checked={agentNext} onChange={(e) => setAgentNext(e.target.checked)} />
            <span>
              Set up an agent next
              <span className={auth.keepWhy}>It can tag, title and tidy this space’s notes, as you ask it to.</span>
            </span>
          </label>
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
  const agent = useAgentRow(space.id)
  const leaveHint = useId()
  // The agent's page and Activity replace this dialog, which would lose the changes.
  const unsaved = name.trim() !== space.name || icon !== space.icon

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!name.trim()) {
      setError('Enter a name.')
      return
    }
    setError(undefined)
    setFormError(undefined)
    setBusy(true)
    try {
      if (unsaved) await putSpace(space.id, name.trim(), icon)
      onClose()
    } catch (err) {
      setFormError(genericMessage(err))
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
          <div className={styles.agentRow}>
            <div>
              <span className={styles.agentRowName}>Agent</span>
              <span className={styles.muted}>{agent.status}</span>
            </div>
            <div className={styles.agentRowActions}>
              {agent.saved && (
                <Button
                  size="sm"
                  disabled={unsaved}
                  aria-describedby={unsaved ? leaveHint : undefined}
                  onClick={() => openOver('/activity', { space: space.id })}
                >
                  Activity…
                </Button>
              )}
              <Button
                size="sm"
                disabled={unsaved}
                aria-describedby={unsaved ? leaveHint : undefined}
                onClick={() => openOver(agentPath(space.id))}
              >
                {agent.saved ? 'Edit agent…' : 'Set up an agent…'}
              </Button>
            </div>
            {unsaved && (
              <p id={leaveHint} className={styles.muted}>
                Save or cancel first.
              </p>
            )}
          </div>
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
 * A space's agent as its dialog sums it up. Shown once the agents have loaded (they need the server) and either AI
 * is set up or the space already has an agent, which then shows as paused.
 */
function useAgentRow(spaceId: string | undefined) {
  const configs = useAgentConfigs()
  const ai = useAiSettings()
  const saved = spaceId ? configs?.get(spaceId) : undefined
  const rules = rulesOf(draftOf(saved)).length
  const status = !saved
    ? 'Not set up'
    : !ai?.configured
      ? 'Paused: AI isn’t set up on this server'
      : !saved.enabled
        ? 'Off'
        : `On · ${rules} ${rules === 1 ? 'rule' : 'rules'}`
  return {
    shown: configs !== null && ai !== null && (ai.configured || saved !== undefined),
    /** The space has an agent set up already: it may have done something to see in Activity. */
    saved: saved !== undefined,
    status,
  }
}
