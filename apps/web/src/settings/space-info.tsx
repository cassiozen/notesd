import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import { ApiError } from '../api/client'
import type { Space } from '../api/types'
import { genericMessage } from '../auth/errors'
import { SpaceIcon, SpaceIconPicker } from '../notes/space-icon'
import { themeKey } from '../notes/space-theme'
import { DEFAULT_SPACE, deleteSpace, putSpace, reloadSpaces, useSpaces } from '../notes/spaces'
import { traitRows, UnknownGlyph, useOffered, type TraitPage, type TraitRow } from '../notes/traits'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { InlineConfirm } from '../ui/inline-confirm'
import { SectionRow } from './section-row'
import settings from './settings.module.css'
import styles from './space-info.module.css'
import { UnsavedProvider, useUnsavedSections } from './unsaved'

export const noteCount = (n: number) => `${n} ${n === 1 ? 'note' : 'notes'}`

/**
 * Space info (§7.8): a space's space-level settings. Its icon (pressed, the picker opens under it) and name (edited
 * where it's shown) with its note count; one list, a row per trait saying how it's set; then Delete. A row opens its
 * section's page, shown in this dialog in place of the rest.
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
  const offered = useOffered()
  // A section's unsaved changes (an agent's draft) are asked about under Done; deleted, there's nothing to keep.
  const { sections, unsaved } = useUnsavedSections()
  const [discarding, setDiscarding] = useState<string[] | null>(null)
  // The trait whose page is shown instead of the rest.
  const [page, setPage] = useState<string | null>(null)
  const [slot, setSlot] = useState<HTMLDivElement | null>(null)
  const [actions, setActions] = useState<HTMLDivElement | null>(null)
  const pageHeadingId = useId()
  const frame = useRef<HTMLDivElement>(null)
  // What had focus when a page opened, to take it again on Back.
  const opener = useRef<HTMLElement | null>(null)

  // Deleted, here or on another device: nothing left to show.
  useEffect(() => {
    if (spaces && !space) onClose()
  }, [spaces, space, onClose])

  /** Synchronously, so the new page can be measured and focused straight after. */
  const swap = useCallback((next: string | null) => {
    const el = frame.current
    // Swapped again while the last swap still moves: measured as it is at rest, not mid-way.
    if (typeof el?.getAnimations === 'function') for (const a of el.getAnimations({ subtree: true })) a.cancel()
    const before = el?.offsetHeight ?? 0
    flushSync(() => setPage(next))
    if (el) settle(el, before, next === null ? -1 : 1)
  }, [])

  const back = useCallback(() => {
    const to = opener.current
    opener.current = null
    swap(null)
    if (to?.isConnected) to.focus()
  }, [swap])

  // The open page's section gone (its trait off and no longer offered, say): back to the rest, not a blank page.
  const rows = space ? traitRows(space, offered) : []
  const pageGone = page !== null && !rows.some((r) => r.kind !== 'unknown' && r.entry.Section && r.entry.key === page)
  useEffect(() => {
    // After the commit: going back swaps pages synchronously, which React can't do from inside an effect.
    if (pageGone) queueMicrotask(back)
  }, [pageGone, back])

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

  function showPage(key: string) {
    opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
    swap(key)
    document.getElementById(headingFor(key))?.focus()
  }

  // One per page: every section's page stays mounted, so they can't share one.
  const headingFor = (key: string) => `${pageHeadingId}-${key}`
  const pageFor = (key: string): TraitPage => ({ slot, actions, open: page === key, headingId: headingFor(key), show: () => showPage(key), back })

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

  function close() {
    const what = unsaved()
    if (what.length === 0) return onClose()
    setDiscarding(what)
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

  // Asked under the Done that was pressed: on a section's page, where its changes are (the agent's).
  const discardAsk = discarding && (
    <InlineConfirm title="Discard your changes?" action="Discard" onConfirm={onClose} onCancel={() => setDiscarding(null)}>
      Your changes to {discarding.join(' and ')} aren’t saved.
    </InlineConfirm>
  )

  return (
    <Dialog labelledBy={page ? headingFor(page) : titleId} onClose={page ? back : close} space={themeKey(shownIcon)} wide focusSelf>
      <UnsavedProvider value={sections}>
      <div ref={frame}>
        <div className={styles.page} hidden={page !== null}>
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
              <span className={styles.meta}>{noteCount(space.noteCount)}</span>
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

          {rows.length > 0 && (
            <ul className={styles.list} aria-label={`${space.name}’s settings`}>
              {rows.map((row) => {
                const key = row.kind === 'off' ? row.entry.key : row.trait.key
                const Section = row.kind === 'unknown' ? undefined : row.entry.Section
                return Section ? <Section key={key} space={space} page={pageFor(key)} /> : <TraitItem key={key} row={row} />
              })}
            </ul>
          )}

          <div className={styles.end}>
            {page === null && discardAsk}
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
                <>
                  <Button
                    size="md"
                    variant="danger-text"
                    className={styles.delete}
                    disabled={why !== null || deleting !== null}
                    aria-describedby={why ? deleteWhyId : undefined}
                    aria-expanded={deleting !== null}
                    onClick={() => setDeleting('asking')}
                  >
                    Delete space…
                  </Button>
                  {why && (
                    <span id={deleteWhyId} className={styles.why}>
                      {why}
                    </span>
                  )}
                </>
              )}
              <Button size="md" className={styles.done} aria-expanded={discarding !== null} onClick={close}>
                Done
              </Button>
            </div>
          </div>
        </div>
        <div className={styles.page} hidden={page === null}>
          <Button size="sm" variant="ghost" className={styles.back} aria-label={`Back to ${space.name}`} onClick={back}>
            <span className={styles.backChevron} aria-hidden="true" />
            {space.name}
          </Button>
          <div ref={setSlot} className={styles.slot} />
          {page !== null && discardAsk}
          <div className={settings.dialogActions}>
            <div ref={setActions} className={styles.actions} />
            <Button size="md" onClick={close}>
              Done
            </Button>
          </div>
        </div>
      </div>
      </UnsavedProvider>
    </Dialog>
  )
}

/**
 * After a page swap, the dialog grows or shrinks to the new page rather than jumping, and the page slides in from
 * the side it's opened toward (dir 1) or gone back to (-1).
 */
function settle(el: HTMLElement, from: number, dir: 1 | -1) {
  if (typeof el.animate !== 'function' || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  const spring = getComputedStyle(el).getPropertyValue('--spring').trim()
  // linear() is newer than the rest: a browser without it settles without the overshoot.
  const easing = spring && CSS.supports('transition-timing-function', spring) ? spring : 'ease-out'
  const to = el.offsetHeight
  if (from !== to) {
    // Clipped while it moves: the spring overshoots, and the page's content is already at its final size.
    el.style.overflow = 'hidden'
    const grow = el.animate([{ height: `${from}px` }, { height: `${to}px` }], { duration: 450, easing })
    const done = () => el.style.removeProperty('overflow')
    grow.addEventListener('finish', done)
    grow.addEventListener('cancel', done)
  }
  el.querySelector(':scope > :not([hidden])')?.animate(
    [
      { opacity: 0, transform: `translateX(${dir * 16}px)` },
      { opacity: 1, transform: 'none' },
    ],
    { duration: 300, easing },
  )
}

/**
 * The name, edited where it's shown: saved on Enter or on leaving the field, put back with Escape (a second
 * Escape closes the page). An empty name is put back too.
 */
function NameField({ space, onError }: { space: Space; onError: (message: string) => void }) {
  // What's typed; null until a key changes it, so a rename from elsewhere while it's focused isn't saved over.
  const [draft, setDraft] = useState<string | null>(null)
  // Saved and on its way: shown until the list has it, rather than the old name in between.
  const [pending, setPending] = useState<string | null>(null)
  const value = draft ?? pending ?? space.name

  async function commit() {
    if (draft === null) return
    const name = draft.trim().replace(/\s+/g, ' ')
    setDraft(null)
    if (!name || name === space.name) return
    setPending(name)
    try {
      // No icon: the server keeps its own, so a pick still on its way isn't undone by a rename.
      await putSpace(space.id, name)
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
      onChange={(e) => setDraft(e.target.value)}
      onBlur={() => void commit()}
      onKeyDown={(e) => {
        if (e.key === 'Enter') {
          e.preventDefault()
          e.currentTarget.blur()
        } else if (e.key === 'Escape' && draft !== null) {
          e.preventDefault()
          e.stopPropagation()
          setDraft(null)
        }
      }}
    />
  )
}

/** A trait without settings here: the server's label and summary, or its problem. */
function TraitItem({ row }: { row: TraitRow }) {
  const Glyph = row.kind === 'unknown' ? UnknownGlyph : row.entry.Glyph
  if (row.kind === 'off') return <SectionRow Glyph={Glyph} label={row.entry.label} status="Off" />
  const { label, problem, summary } = row.trait
  // One this app doesn't know says to update the app after its problem too, with no separator when there's nothing before it.
  const status = [problem ?? summary, row.kind === 'unknown' && 'Update the app to change this.'].filter(Boolean).join(' · ')
  return <SectionRow Glyph={Glyph} label={label} status={status} problem={problem !== undefined} on />
}
