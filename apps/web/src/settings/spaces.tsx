import { useEffect, useId, useState, type FormEvent } from 'react'
import { genericMessage } from '../auth/errors'
import { SpaceIcon, SpaceIconPicker } from '../notes/space-icon'
import { SpaceIdentity } from '../notes/space-identity'
import { themeKey } from '../notes/space-theme'
import { putSpace, reloadSpaces, useSpaces } from '../notes/spaces'
import { traitsLabel } from '../notes/traits'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { Field } from '../ui/field'
import { ChevronIcon } from '../ui/icons'
import { Surface } from '../ui/surface'
import { noteCount, SpaceInfo } from './space-info'
import styles from './settings.module.css'

/** Spaces (§7.7): the list, each opening its Space info (§7.8), and New space. The sidebar's switcher can make one too. */
export function SpacesCard({ onCreated }: { onCreated: (id: string) => void }) {
  const spaces = useSpaces()
  const [creating, setCreating] = useState(false)
  const [info, setInfo] = useState<string | null>(null)
  // Fresh each time: another device may have added notes or spaces.
  useEffect(() => void reloadSpaces(), [])

  return (
    <Surface as="section" className={styles.devices} aria-label="Spaces">
      <div className={styles.devicesHead}>
        <span className={styles.muted}>{spaces && (spaces.length === 1 ? '1 space' : `${spaces.length} spaces`)}</span>
        <Button size="sm" onClick={() => setCreating(true)} disabled={!spaces}>
          New space
        </Button>
      </div>
      <p className={styles.deviceNote}>
        Keep separate contexts, like Work and Personal, apart. Each space has its own notes and tags, and new notes go to
        the one you’re in.
      </p>
      <ul className={styles.deviceList} aria-label="Spaces">
        {spaces?.map((s) => (
          <li key={s.id}>
            <button
              type="button"
              className={[styles.device, styles.spaceRow].join(' ')}
              aria-label={[s.name, traitsLabel(s.traits), noteCount(s.noteCount)].filter(Boolean).join(', ')}
              onClick={() => setInfo(s.id)}
            >
              <SpaceIcon space={s} size={28} />
              <span className={styles.deviceText}>
                <span className={styles.spaceLine}>
                  <SpaceIdentity space={s} icon={false} nameClassName={styles.deviceName} glyphSize={14} />
                </span>
                <span className={styles.muted}>{noteCount(s.noteCount)}</span>
              </span>
              <span className={styles.rowChevron} aria-hidden="true">
                <ChevronIcon />
              </span>
            </button>
          </li>
        ))}
      </ul>
      {creating && spaces && (
        <NewSpaceDialog
          onClose={() => setCreating(false)}
          onCreated={(id) => {
            setCreating(false)
            onCreated(id)
          }}
        />
      )}
      {info && <SpaceInfo id={info} onClose={() => setInfo(null)} />}
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

  async function submit(e: FormEvent) {
    e.preventDefault()
    const found = {
      ...(!name.trim() && { name: 'Name the new space.' }),
      ...(icon === null && { icon: 'Choose an icon.' }),
    }
    setErrors(found)
    if (Object.keys(found).length > 0 || icon === null) return
    setBusy(true)
    try {
      const id = crypto.randomUUID()
      await putSpace(id, name.trim(), icon)
      onCreated(id)
    } catch (err) {
      setErrors({ form: genericMessage(err) })
      setBusy(false)
    }
  }

  // The icon picked brings its colours: the dialog shows them before the space exists.
  return (
    <Dialog labelledBy={titleId} onClose={onClose} space={icon === null ? undefined : themeKey(icon)}>
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
