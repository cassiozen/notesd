import { useEffect, useId, useState, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import type { Space } from '../api/types'
import { genericMessage } from '../auth/errors'
import { SpaceIcon, SpaceIconPicker } from '../notes/space-icon'
import { DEFAULT_SPACE_ICON } from '../notes/space-icons'
import { DEFAULT_SPACE, deleteSpace, putSpace, reloadSpaces, useSpaces } from '../notes/spaces'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { Field } from '../ui/field'
import { Kicker } from '../ui/kicker'
import { Surface } from '../ui/surface'
import styles from './settings.module.css'

const notes = (n: number) => `${n} ${n === 1 ? 'note' : 'notes'}`

/**
 * Spaces (§7.7): create, edit and delete them. The only place to make one,
 * so people who never need spaces never see them anywhere else.
 */
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
      setError(
        err instanceof ApiError && err.code === 'space_not_empty'
          ? 'Notes arrived in that space from another device. Move or delete them first.'
          : genericMessage(err),
      )
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
          first={spaces.length === 1 ? spaces[0] : undefined}
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

/**
 * A new space, with a name and an icon. The second one also names the first: "Notes" alone was fine,
 * beside "Work" it reads better as "Personal".
 */
function NewSpaceDialog({ first, onClose, onCreated }: { first: Space | undefined; onClose: () => void; onCreated: (id: string) => void }) {
  const titleId = useId()
  const [name, setName] = useState('')
  const [icon, setIcon] = useState<string | null>(null)
  const [firstName, setFirstName] = useState(first?.name === 'Notes' ? 'Personal' : (first?.name ?? ''))
  const [errors, setErrors] = useState<{ name?: string; icon?: string; first?: string; form?: string }>({})
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const found = {
      ...(!name.trim() && { name: 'Name the new space.' }),
      ...(!icon && { icon: 'Choose an icon.' }),
      ...(first && !firstName.trim() && { first: 'Name the space your notes are in now.' }),
    }
    setErrors(found)
    if (Object.keys(found).length > 0 || !icon) return
    setBusy(true)
    try {
      if (first && firstName.trim() !== first.name) await putSpace(first.id, firstName.trim())
      const id = crypto.randomUUID()
      await putSpace(id, name.trim(), icon)
      onCreated(id)
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
        {first && (
          <Field
            label={`Rename “${first.name}” to`}
            autoComplete="off"
            maxLength={64}
            value={firstName}
            onChange={(e) => setFirstName(e.target.value)}
            error={errors.first}
            hint="The space your notes are in now."
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
  const [icon, setIcon] = useState(space.icon || DEFAULT_SPACE_ICON)
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!name.trim()) {
      setError('Enter a name.')
      return
    }
    setBusy(true)
    try {
      await putSpace(space.id, name.trim(), icon)
      onClose()
    } catch (err) {
      setError(genericMessage(err))
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
