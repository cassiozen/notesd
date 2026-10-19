import { useEffect, useId, useRef, useState } from 'react'
import type { Space } from '../api/types'
import { ChevronIcon } from '../ui/icons'
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
  const name = spaces.find((s) => s.id === current)?.name ?? 'Notes'

  useEffect(() => {
    if (!open) return
    const close = (e: Event) => {
      if (!anchor.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <span ref={anchor} className={[styles.anchor, block ? styles.block : ''].join(' ')}>
      <button
        type="button"
        className={styles.button}
        aria-label={`Space: ${name}`}
        title={name}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={() => setOpen((v) => !v)}
      >
        <span className={styles.name}>{name}</span>
        <span className={styles.chevron}>
          <ChevronIcon />
        </span>
      </button>
      {open && (
        <div id={menuId} role="menu" aria-label="Spaces" className={styles.menu}>
          {spaces.map((s) => (
            <button
              key={s.id}
              type="button"
              role="menuitemradio"
              aria-checked={s.id === current}
              className={styles.item}
              onClick={() => {
                setOpen(false)
                onSwitch(s.id)
              }}
            >
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
