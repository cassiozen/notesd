import { useEffect, useId, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react'
import type { Space } from '../api/types'
import { ChevronIcon } from '../ui/icons'
import { SpaceIcon } from './space-icon'
import styles from './space-menu.module.css'

/**
 * The switcher between spaces (§7.7): the current space's name, opening a
 * menu of every space. Only shown once there is more than one.
 */
export function SpaceMenu({
  spaces,
  current,
  onSwitch,
  block = false,
}: {
  spaces: Space[]
  current: string
  onSwitch: (id: string) => void
  /** Fill the row, the menu as wide as it: in the sidebar, which clips anything wider. */
  block?: boolean
}) {
  const [open, setOpen] = useState(false)
  const menuId = useId()
  const anchor = useRef<HTMLSpanElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const items = useRef<(HTMLButtonElement | null)[]>([])
  const space = spaces.find((s) => s.id === current)
  const name = space?.name ?? 'Notes'

  useEffect(() => {
    if (!open) return
    const close = (e: Event) => {
      if (!anchor.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [open])

  // Opened, focus goes to the space shown (as a menu's keyboard users expect), else the first.
  useEffect(() => {
    if (!open) return
    items.current[Math.max(0, spaces.findIndex((s) => s.id === current))]?.focus()
  }, [open, spaces, current])

  /** Closes the menu, focus back on the button that opened it rather than lost with the items. */
  const close = () => {
    setOpen(false)
    trigger.current?.focus()
  }

  const onMenuKey = (e: ReactKeyboardEvent) => {
    const list = items.current.filter((el): el is HTMLButtonElement => el !== null)
    const at = list.indexOf(document.activeElement as HTMLButtonElement)
    const go = (i: number) => {
      e.preventDefault()
      list[(i + list.length) % list.length]?.focus()
    }
    if (e.key === 'ArrowDown') go(at + 1)
    else if (e.key === 'ArrowUp') go(at - 1)
    else if (e.key === 'Home') go(0)
    else if (e.key === 'End') go(list.length - 1)
    else if (e.key === 'Escape') {
      e.preventDefault()
      close()
    } else if (e.key === 'Tab') setOpen(false)
  }

  return (
    <span ref={anchor} className={[styles.anchor, block ? styles.block : ''].join(' ')}>
      <button
        ref={trigger}
        type="button"
        className={styles.button}
        aria-label={`Space: ${name}`}
        title={name}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={() => setOpen((v) => !v)}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault()
            setOpen(true)
          } else if (e.key === 'Escape' && open) close()
        }}
      >
        <SpaceIcon space={space ?? { icon: '' }} size={block ? 20 : 22} />
        <span className={styles.name}>{name}</span>
        <span className={styles.chevron}>
          <ChevronIcon />
        </span>
      </button>
      {open && (
        <div id={menuId} role="menu" aria-label="Spaces" className={styles.menu} onKeyDown={onMenuKey}>
          {spaces.map((s, i) => (
            <button
              key={s.id}
              ref={(el) => {
                items.current[i] = el
              }}
              type="button"
              role="menuitemradio"
              aria-checked={s.id === current}
              tabIndex={-1}
              className={styles.item}
              onClick={() => {
                close()
                onSwitch(s.id)
              }}
            >
              <SpaceIcon space={s} />
              <span className={styles.itemName} title={s.name}>
                {s.name}
              </span>
              {s.id === current && (
                <span className={styles.check} aria-hidden="true">
                  ✓
                </span>
              )}
            </button>
          ))}
        </div>
      )}
    </span>
  )
}
