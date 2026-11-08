import { useEffect, useId, useState, type ComponentType, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import type { Space } from '../api/types'
import { genericMessage } from '../auth/errors'
import { SpaceIcon, SpaceIconPicker } from '../notes/space-icon'
import { themeKey } from '../notes/space-theme'
import { DEFAULT_SPACE, deleteSpace, putSpace, reloadSpaces, useSpaces } from '../notes/spaces'
import { traitRows, UnknownGlyph, type TraitFlowProps, type TraitRow } from '../notes/traits'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { Field } from '../ui/field'
import { ChevronIcon } from '../ui/icons'
import settings from './settings.module.css'
import styles from './space-info.module.css'

export const noteCount = (n: number) => `${n} ${n === 1 ? 'note' : 'notes'}`

/**
 * Space info (§7.8): a space's one page of space-level settings. Its icon,
 * name and note count; its traits; then Name and icon, and Delete.
 */
export function SpaceInfo({ id, onClose }: { id: string; onClose: () => void }) {
  const spaces = useSpaces()
  const space = spaces?.find((s) => s.id === id)
  const titleId = useId()
  const deleteWhyId = useId()
  const [editing, setEditing] = useState(false)
  // A trait's settings or Turn on, over this page.
  const [flow, setFlow] = useState<ComponentType<TraitFlowProps> | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState<string>()

  // Deleted, here or on another device: nothing left to show.
  useEffect(() => {
    if (spaces && !space) onClose()
  }, [spaces, space, onClose])

  if (!space) return null
  const rows = traitRows(space)
  const why =
    space.id === DEFAULT_SPACE
      ? 'Where new installs start. It can be renamed, not deleted.'
      : space.noteCount > 0
        ? 'Move or delete its notes to delete it.'
        : null

  async function remove() {
    setDeleting(true)
    setError(undefined)
    try {
      await deleteSpace(id)
      // Not left to the list's refresh, which can fail and would keep the deleted space here.
      onClose()
    } catch (err) {
      const notEmpty = err instanceof ApiError && err.code === 'space_not_empty'
      const busy = err instanceof ApiError && err.code === 'space_busy'
      setError(
        notEmpty
          ? 'Notes arrived in this space from another device. Move or delete them first.'
          : busy
            ? 'Someone is using this space right now. Try again in a moment.'
            : genericMessage(err),
      )
      // Its count then shows those notes, and Delete turns off.
      if (notEmpty) void reloadSpaces()
      setDeleting(false)
    }
  }

  const Flow = flow
  return (
    <>
      <Dialog labelledBy={titleId} onClose={onClose} space={themeKey(space.icon)}>
        <div className={styles.page}>
          <div className={styles.head}>
            <SpaceIcon space={space} size={44} />
            <h2 id={titleId} className={styles.name}>
              {space.name}
            </h2>
            <span className={settings.muted}>{noteCount(space.noteCount)}</span>
          </div>
          {rows.length > 0 && (
            <ul className={styles.group} aria-label="Features">
              {rows.map((row) => (
                <TraitItem key={row.kind === 'off' ? row.entry.key : row.trait.key} row={row} onOpen={(c) => setFlow(() => c)} />
              ))}
            </ul>
          )}
          {/* A shared space's name, icon and existence are its owner's (§7.9). */}
          {space.shared?.role !== 'member' && (
            <div className={styles.group}>
              <button type="button" className={styles.action} onClick={() => setEditing(true)}>
                Name and icon
                <span className={styles.chevron} aria-hidden="true">
                  <ChevronIcon />
                </span>
              </button>
              <button
                type="button"
                className={styles.action}
                data-danger=""
                disabled={why !== null || deleting}
                aria-busy={deleting || undefined}
                aria-describedby={why ? deleteWhyId : undefined}
                onClick={() => void remove()}
              >
                Delete space
              </button>
              {why && (
                <p id={deleteWhyId} className={styles.why}>
                  {why}
                </p>
              )}
            </div>
          )}
          {error && (
            <p role="alert" className={settings.error}>
              {error}
            </p>
          )}
          <div className={settings.dialogActions}>
            <Button size="md" onClick={onClose}>
              Done
            </Button>
          </div>
        </div>
      </Dialog>
      {/* Beside the page, not inside it: a nested dialog's Escape would close both. */}
      {editing && <EditDialog space={space} onClose={() => setEditing(false)} />}
      {Flow && <Flow space={space} onClose={() => setFlow(null)} />}
    </>
  )
}

function TraitItem({ row, onOpen }: { row: TraitRow; onOpen: (flow: ComponentType<TraitFlowProps>) => void }) {
  const Glyph = row.kind === 'unknown' ? UnknownGlyph : row.entry.Glyph
  const label = row.kind === 'off' ? row.entry.label : row.trait.label
  const problem = row.kind === 'off' ? undefined : row.trait.problem
  const Settings = row.kind === 'on' ? row.entry.Settings : undefined
  const TurnOn = row.kind === 'off' ? row.entry.turnOn : undefined
  return (
    <li className={styles.trait} data-off={row.kind === 'off' ? '' : undefined} data-trait={row.kind === 'off' ? row.entry.key : row.trait.key}>
      <span className={styles.tile} aria-hidden="true">
        <Glyph size={16} />
      </span>
      <span className={styles.traitText}>
        <span className={styles.traitLabel}>{label}</span>
        {problem ? (
          <span className={styles.problem}>{problem}</span>
        ) : (
          <span className={styles.summary}>{row.kind === 'off' ? 'Off' : row.trait.summary}</span>
        )}
        {row.kind === 'unknown' && <span className={styles.summary}>Update the app to change this.</span>}
      </span>
      {Settings && (
        <button type="button" className={styles.manage} onClick={() => onOpen(Settings)} aria-label={`Manage ${label}`}>
          Manage
          <ChevronIcon />
        </button>
      )}
      {TurnOn && (
        <Button variant="accent" size="sm" onClick={() => onOpen(TurnOn)} aria-label={`Turn on ${label}`}>
          Turn on
        </Button>
      )}
    </li>
  )
}

/** A space's name and icon. */
function EditDialog({ space, onClose }: { space: Space; onClose: () => void }) {
  const titleId = useId()
  const [name, setName] = useState(space.name)
  const [icon, setIcon] = useState(space.icon)
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
    // The icon picked brings its colours: the dialog shows them before they're saved.
    <Dialog labelledBy={titleId} onClose={onClose} space={themeKey(icon)}>
      <form className={settings.dialog} onSubmit={(e) => void submit(e)} noValidate>
        <h2 id={titleId} className={settings.dialogTitle}>
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
        <div className={settings.dialogActions}>
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
