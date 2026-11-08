import { useEffect, useId, useRef, useState, type ComponentType } from 'react'
import { ApiError } from '../api/client'
import type { Space, SpaceTrait } from '../api/types'
import { genericMessage } from '../auth/errors'
import { SpaceIcon, SpaceIconPicker } from '../notes/space-icon'
import { themeKey } from '../notes/space-theme'
import { DEFAULT_SPACE, deleteSpace, putSpace, reloadSpaces, useSpaces } from '../notes/spaces'
import { traitGlyph, traitRows, traitsLabel, UnknownGlyph, type TraitRow } from '../notes/traits'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { InlineConfirm } from '../ui/inline-confirm'
import settings from './settings.module.css'
import styles from './space-info.module.css'

export const noteCount = (n: number) => `${n} ${n === 1 ? 'note' : 'notes'}`

/**
 * Space info (§7.8): a space's one page of space-level settings, changed in place with nothing opened over it.
 * Its icon (pressed, the picker opens under it) and name (edited where it's shown) with its note count; a section
 * per trait; then Delete.
 */
export function SpaceInfo({ id, onClose }: { id: string; onClose: () => void }) {
  const spaces = useSpaces()
  const space = spaces?.find((s) => s.id === id)
  const titleId = useId()
  const deleteWhyId = useId()
  const [picking, setPicking] = useState(false)
  // Picked and on its way to the server: the page shows it, and its colours, meanwhile.
  const [icon, setIcon] = useState<string | null>(null)
  const picks = useRef(0)
  const [deleting, setDeleting] = useState<'asking' | 'busy' | null>(null)
  const [error, setError] = useState<string>()

  // Deleted, here or on another device: nothing left to show.
  useEffect(() => {
    if (spaces && !space) onClose()
  }, [spaces, space, onClose])

  if (!space) return null
  const name = space.name
  // A shared space's name, icon and existence are its owner's (§7.9).
  const own = space.shared?.role !== 'member'
  const shownIcon = icon ?? space.icon
  const why =
    space.id === DEFAULT_SPACE
      ? 'Where new installs start. It can be renamed, not deleted.'
      : space.noteCount > 0
        ? 'Move or delete its notes to delete it.'
        : null

  async function pickIcon(key: string) {
    const n = ++picks.current
    setIcon(key)
    setError(undefined)
    try {
      await putSpace(id, name, key)
    } catch (err) {
      setError(genericMessage(err))
    } finally {
      // A later pick still on its way keeps showing.
      if (n === picks.current) setIcon(null)
    }
  }

  async function remove() {
    setDeleting('busy')
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
      setDeleting(null)
    }
  }

  return (
    <Dialog labelledBy={titleId} onClose={onClose} space={themeKey(shownIcon)} wide>
      <div className={styles.page}>
        <div className={styles.head}>
          {own ? (
            <button
              type="button"
              className={styles.iconButton}
              aria-label="Change icon"
              aria-expanded={picking}
              title="Change icon"
              onClick={() => setPicking((v) => !v)}
            >
              <SpaceIcon space={{ icon: shownIcon }} size={56} />
            </button>
          ) : (
            <SpaceIcon space={space} size={56} />
          )}
          <div className={styles.headText}>
            <h2 id={titleId} className={own ? 'visually-hidden' : styles.name}>
              {space.name}
            </h2>
            {own && <NameField space={space} onError={setError} />}
            <span className={styles.meta}>
              {noteCount(space.noteCount)}
              <HeadGlyphs traits={space.traits} />
            </span>
          </div>
        </div>
        {own && picking && (
          <div className={styles.picker}>
            <SpaceIconPicker value={shownIcon} onChange={(key) => void pickIcon(key)} />
          </div>
        )}
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}

        {blocks(traitRows(space)).map((block) =>
          block.kind === 'section' ? (
            <block.Section key={block.key} space={space} />
          ) : (
            <ul key={block.key} className={styles.group} aria-label="Features">
              {block.rows.map((row) => (
                <TraitItem key={row.kind === 'off' ? row.entry.key : row.trait.key} row={row} />
              ))}
            </ul>
          ),
        )}

        <div className={styles.end}>
          {own && deleting && (
            <InlineConfirm
              title={`Delete ${space.name}?`}
              action="Delete"
              busy={deleting === 'busy'}
              onConfirm={() => void remove()}
              onCancel={() => setDeleting(null)}
            >
              It has no notes: only the space itself goes.
            </InlineConfirm>
          )}
          <div className={settings.dialogActions}>
            {own && (
              <Button
                size="md"
                variant="danger-text"
                className={settings.leading}
                disabled={why !== null || deleting !== null}
                aria-describedby={why ? deleteWhyId : undefined}
                aria-expanded={deleting !== null}
                onClick={() => setDeleting('asking')}
              >
                Delete space…
              </Button>
            )}
            <Button size="md" onClick={onClose}>
              Done
            </Button>
          </div>
          {own && why && (
            <p id={deleteWhyId} className={styles.why}>
              {why}
            </p>
          )}
        </div>
      </div>
    </Dialog>
  )
}

/**
 * The name, edited where it's shown: saved on Enter or on leaving the field, put back with Escape (a second
 * Escape closes the page). An empty name is put back too.
 */
function NameField({ space, onError }: { space: Space; onError: (message: string) => void }) {
  // Typing; null when the field isn't being edited.
  const [draft, setDraft] = useState<string | null>(null)
  // Saved and on its way: shown until the list has it, rather than the old name in between.
  const [pending, setPending] = useState<string | null>(null)
  const value = draft ?? pending ?? space.name

  async function commit() {
    const name = (draft ?? '').trim().replace(/\s+/g, ' ')
    setDraft(null)
    if (!name || name === space.name) return
    setPending(name)
    try {
      await putSpace(space.id, name, space.icon)
    } catch (err) {
      onError(genericMessage(err))
    } finally {
      setPending(null)
    }
  }

  return (
    <input
      className={styles.nameInput}
      aria-label="Name"
      autoComplete="off"
      spellCheck={false}
      maxLength={64}
      value={value}
      onFocus={() => setDraft(value)}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={() => void commit()}
      onKeyDown={(e) => {
        if (e.key === 'Enter') {
          e.preventDefault()
          e.currentTarget.blur()
        } else if (e.key === 'Escape' && draft !== null && draft !== space.name) {
          e.preventDefault()
          e.stopPropagation()
          setDraft(space.name)
        }
      }}
    />
  )
}

/**
 * The space's trait glyphs after its count. One that turns on while the page is open arrives with a little
 * movement: seen as it starts to mean something (sharing it here, say), it's read later.
 */
function HeadGlyphs({ traits }: { traits: readonly SpaceTrait[] }) {
  const [initial] = useState(() => new Set(traits.map((t) => t.key)))
  if (traits.length === 0) return null
  return (
    <span role="img" aria-label={traitsLabel(traits)} className={styles.glyphs}>
      {traits.map((t) => {
        const Glyph = traitGlyph(t.key)
        return (
          <span key={t.key} data-trait={t.key} data-new={initial.has(t.key) ? undefined : ''} data-problem={t.problem ? '' : undefined}>
            <Glyph size={14} />
          </span>
        )
      })}
    </span>
  )
}

type Block =
  | { kind: 'section'; key: string; Section: ComponentType<{ space: Space }> }
  | { kind: 'rows'; key: string; rows: TraitRow[] }

/** A trait with a section of its own gets it; the others, in a row each, share a group between sections. */
function blocks(rows: TraitRow[]): Block[] {
  const out: Block[] = []
  for (const row of rows) {
    const Section = row.kind === 'unknown' ? undefined : row.entry.Section
    const key = row.kind === 'off' ? row.entry.key : row.trait.key
    const last = out.at(-1)
    if (Section) out.push({ kind: 'section', key, Section })
    else if (last?.kind === 'rows') last.rows.push(row)
    else out.push({ kind: 'rows', key, rows: [row] })
  }
  return out
}

/** A trait without settings here: the server's label and summary, or its problem. */
function TraitItem({ row }: { row: TraitRow }) {
  const Glyph = row.kind === 'unknown' ? UnknownGlyph : row.entry.Glyph
  const label = row.kind === 'off' ? row.entry.label : row.trait.label
  const problem = row.kind === 'off' ? undefined : row.trait.problem
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
    </li>
  )
}
