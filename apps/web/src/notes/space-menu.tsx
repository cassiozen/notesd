import { useEffect, useId, useLayoutEffect, useRef, useState, type CSSProperties, type KeyboardEvent as ReactKeyboardEvent } from 'react'
import { createPortal } from 'react-dom'
import type { Space, SpaceTrait } from '../api/types'
import { Button } from '../ui/button'
import { ChevronIcon, InfoIcon, PlusIcon } from '../ui/icons'
import { SpaceIcon } from './space-icon'
import { TraitGlyphs, TraitGroup } from './space-identity'
import { traitGlyph, traitsLabel } from './traits'
import styles from './space-menu.module.css'

/** An invitation into someone else's space (§7.7). Nothing provides these yet: shared spaces will. */
export type SpaceInvite = { id: string; spaceName: string; spaceIcon: string; from: string }

const NO_INVITES: readonly SpaceInvite[] = []
const NO_CHANGES: Readonly<Record<string, string>> = {}

/** A trait on the switcher's card: its problem, else its label and summary. */
export function cardLine(t: SpaceTrait): string {
  if (t.problem) return t.problem
  return t.summary ? `${t.label} · ${t.summary}` : t.label
}

/**
 * The switcher between spaces (§7.7): the current space's icon and name, opening
 * a card for that space, any invitations, the other spaces and New space…. The
 * space's trait glyphs beside it are a button of their own, to Space info (§7.8).
 */
export function SpaceMenu({
  spaces,
  current,
  onSwitch,
  onCreate,
  onInfo,
  invites = NO_INVITES,
  onJoin,
  onDecline,
  changes = NO_CHANGES,
}: {
  spaces: Space[]
  current: string
  onSwitch: (id: string) => void
  onCreate: () => void
  /** A space's Space info. */
  onInfo: (id: string) => void
  invites?: readonly SpaceInvite[]
  onJoin?: (id: string) => void
  onDecline?: (id: string) => void
  /** By space: what others changed there while this account was away. */
  changes?: Readonly<Record<string, string>>
}) {
  const [open, setOpen] = useState(false)
  const [place, setPlace] = useState<CSSProperties>()
  const popId = useId()
  const anchor = useRef<HTMLSpanElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const pop = useRef<HTMLDivElement>(null)
  const space = spaces.find((s) => s.id === current)
  const name = space?.name ?? 'Notes'
  const traits = space?.traits ?? []
  const others = spaces.filter((s) => s.id !== current)

  // In a portal at a fixed place: the docked sidebar is narrower than the popover and clips.
  useLayoutEffect(() => {
    if (!open) return
    const measure = () => {
      const r = trigger.current?.getBoundingClientRect()
      if (!r) return
      const width = Math.min(300, window.innerWidth - 16)
      const left = Math.max(8, Math.min(r.left, window.innerWidth - 8 - width))
      setPlace({ top: r.bottom + 6, left, width })
    }
    measure()
    window.addEventListener('resize', measure)
    return () => window.removeEventListener('resize', measure)
  }, [open])

  useEffect(() => {
    if (!open) return
    const close = (e: Event) => {
      const t = e.target as Node
      if (!anchor.current?.contains(t) && !pop.current?.contains(t)) setOpen(false)
    }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [open])

  /** What the arrow keys move through, in order; a row's ⓘ is reached with → instead. */
  const nav = () => [...(pop.current?.querySelectorAll<HTMLElement>('[data-nav]') ?? [])]

  // Opened, focus goes to the first space to switch to: switching is what it's opened for.
  useEffect(() => {
    if (!open) return
    const p = pop.current
    ;(p?.querySelector<HTMLElement>('[data-space]') ?? p?.querySelector<HTMLElement>('[data-nav]'))?.focus()
  }, [open])

  /** Closes, focus back on the button that opened it rather than lost with the popover. */
  const close = () => {
    setOpen(false)
    trigger.current?.focus()
  }

  const onKey = (e: ReactKeyboardEvent) => {
    const list = nav()
    const el = document.activeElement as HTMLElement | null
    const row = el?.dataset.info !== undefined ? el.parentElement?.querySelector<HTMLElement>('[data-space]') : el
    const at = list.indexOf(row as HTMLElement)
    const go = (i: number) => {
      e.preventDefault()
      list[(i + list.length) % list.length]?.focus()
    }
    if (e.key === 'ArrowDown') go(at + 1)
    else if (e.key === 'ArrowUp') go(at - 1)
    else if (e.key === 'Home') go(0)
    else if (e.key === 'End') go(list.length - 1)
    else if (e.key === 'ArrowRight' && el?.dataset.space) {
      e.preventDefault()
      el.parentElement?.querySelector<HTMLElement>('[data-info]')?.focus()
    } else if (e.key === 'ArrowLeft' && el?.dataset.info !== undefined) {
      e.preventDefault()
      row?.focus()
    } else if (e.key === 'Escape' || e.key === 'Tab') {
      // In a portal, Tab would leave for the end of the page: back to the switcher instead.
      e.preventDefault()
      close()
    } else if (e.key.length === 1 && !e.altKey && !e.ctrlKey && !e.metaKey) {
      const rows = [...(pop.current?.querySelectorAll<HTMLElement>('[data-space]') ?? [])]
      const from = rows.indexOf(row as HTMLElement)
      const key = e.key.toLowerCase()
      const hit = [...rows.slice(from + 1), ...rows.slice(0, from + 1)].find((r) => r.dataset.name?.toLowerCase().startsWith(key))
      if (hit) {
        e.preventDefault()
        hit.focus()
      }
    }
  }

  const act = (f: () => void) => () => {
    close()
    f()
  }

  return (
    <span ref={anchor} className={styles.anchor}>
      <button
        ref={trigger}
        type="button"
        className={styles.button}
        aria-label={`Space: ${name}`}
        title={name}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? popId : undefined}
        onClick={() => setOpen((v) => !v)}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault()
            setOpen(true)
          } else if (e.key === 'Escape' && open) close()
        }}
      >
        <SpaceIcon space={space ?? { icon: '' }} size={24} />
        <span className={styles.name}>{name}</span>
        <span className={styles.chevron}>
          <ChevronIcon />
        </span>
      </button>
      <TraitGroup traits={traits} onOpen={act(() => onInfo(current))} />
      {open &&
        createPortal(
          <div id={popId} ref={pop} role="dialog" aria-label="Spaces" className={styles.pop} style={place} onKeyDown={onKey}>
            <section className={styles.card} aria-label={name}>
              <SpaceIcon space={space ?? { icon: '' }} size={40} />
              <span className={styles.cardName}>{name}</span>
              <button type="button" data-nav className={styles.info} aria-label="Space info" onClick={act(() => onInfo(current))}>
                Info
                <ChevronIcon size={10} />
              </button>
              <ul className={styles.cardLines}>
                {traits.length === 0 && <li>Only you</li>}
                {traits.map((t) => {
                  const Glyph = traitGlyph(t.key)
                  return (
                    <li key={t.key} className={t.problem ? styles.problem : undefined}>
                      <span className={styles.lineGlyph}>
                        <Glyph size={13} />
                      </span>
                      {cardLine(t)}
                    </li>
                  )
                })}
              </ul>
            </section>

            {invites.length > 0 && (
              <ul className={styles.invites} aria-label="Invitations">
                {invites.map((inv) => (
                  <li key={inv.id} className={styles.invite}>
                    <SpaceIcon space={{ icon: inv.spaceIcon }} size={20} />
                    <span className={styles.inviteText}>
                      <b>{inv.from}</b> invited you to <b>{inv.spaceName}</b>
                    </span>
                    <span className={styles.inviteActions}>
                      <Button size="sm" variant="solid" data-nav aria-label={`Join ${inv.spaceName}`} onClick={act(() => onJoin?.(inv.id))}>
                        Join
                      </Button>
                      <Button size="sm" data-nav aria-label={`Decline ${inv.spaceName}`} onClick={act(() => onDecline?.(inv.id))}>
                        Decline
                      </Button>
                    </span>
                  </li>
                ))}
              </ul>
            )}

            {others.length > 0 && (
              <>
                <h3 className={styles.caption}>Switch to</h3>
                <ul className={styles.rows}>
                  {others.map((s) => (
                    <SpaceRow
                      key={s.id}
                      space={s}
                      change={changes[s.id]}
                      onSwitch={act(() => onSwitch(s.id))}
                      onInfo={act(() => onInfo(s.id))}
                    />
                  ))}
                </ul>
              </>
            )}

            <div role="separator" className={styles.separator} />
            <button type="button" data-nav className={styles.create} onClick={act(onCreate)}>
              <span className={styles.plus} aria-hidden="true">
                <PlusIcon />
              </span>
              New space…
            </button>
          </div>,
          document.body,
        )}
    </span>
  )
}

/** Another space: switches on press; its ⓘ, shown on hover or focus, opens its Space info without switching. */
function SpaceRow({ space, change, onSwitch, onInfo }: { space: Space; change?: string; onSwitch: () => void; onInfo: () => void }) {
  const detail = change ?? (space.traits.length > 0 ? traitsLabel(space.traits) : 'only you')
  return (
    <li className={styles.row}>
      <button type="button" data-nav data-space={space.id} data-name={space.name} className={styles.rowMain} aria-label={`${space.name}, ${detail}`} onClick={onSwitch}>
        <SpaceIcon space={space} size={20} />
        <span className={styles.rowName}>
          <span className={styles.rowNameText}>{space.name}</span>
          {change && <span className={styles.dot} aria-hidden="true" />}
        </span>
        <span className={styles.rowLine} aria-hidden="true">
          {change ? (
            <>
              <TraitGlyphs traits={space.traits} size={12} />
              <span className={styles.change}>{change}</span>
            </>
          ) : space.traits.length > 0 ? (
            space.traits.map((t, i) => {
              const Glyph = traitGlyph(t.key)
              return (
                <span key={t.key} className={[styles.rowTrait, t.problem && styles.problem].filter(Boolean).join(' ')}>
                  {i > 0 && '· '}
                  <Glyph size={12} />
                  {t.problem ?? t.label}
                </span>
              )
            })
          ) : (
            'Only you'
          )}
        </span>
      </button>
      <button type="button" data-info tabIndex={-1} className={styles.rowInfo} aria-label={`Space info: ${space.name}`} title="Space info" onClick={onInfo}>
        <InfoIcon />
      </button>
    </li>
  )
}
