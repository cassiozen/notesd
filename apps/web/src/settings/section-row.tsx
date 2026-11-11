import { useId, type ComponentType, type ReactNode } from 'react'
import type { GlyphProps } from '../notes/trait-glyphs'
import styles from './space-info.module.css'

/**
 * One line of Space info's list, the same for every section: what it is, and how it's set now. With `onOpen`, it
 * opens the section's page; without, there's nothing to change here.
 */
export function SectionRow({
  Glyph,
  label,
  status,
  on = false,
  problem = false,
  live = false,
  end,
  onOpen,
}: {
  Glyph: ComponentType<GlyphProps>
  label: string
  status: ReactNode
  on?: boolean
  /** The status is something wrong, said in the danger colour. */
  problem?: boolean
  /** Reachable by anyone now (a site that's up): a dot before the status. */
  live?: boolean
  /** Before the chevron: who's in it, say. */
  end?: ReactNode
  onOpen?: () => void
}) {
  const labelId = useId()
  const statusId = useId()
  const inner = (
    <>
      <span className={styles.tile} aria-hidden="true">
        <Glyph size={16} />
      </span>
      <span className={styles.rowText}>
        <span id={labelId} className={styles.rowLabel}>
          {label}
        </span>
        <span id={statusId} className={styles.rowStatus} data-problem={problem ? '' : undefined}>
          {live && !problem && <span className={styles.live} aria-hidden="true" />}
          <span className={styles.rowStatusText}>{status}</span>
        </span>
      </span>
      {end}
    </>
  )
  return (
    <li className={styles.item} data-on={on ? '' : undefined}>
      {onOpen ? (
        <button type="button" className={styles.row} aria-labelledby={labelId} aria-describedby={statusId} onClick={onOpen}>
          {inner}
          <span className={styles.chevron} aria-hidden="true" />
        </button>
      ) : (
        <div className={styles.row}>{inner}</div>
      )}
    </li>
  )
}

/** A section's page: its title, which takes focus as it opens, and what it's about in a line or two. */
export function PageHead({ id, title, aside, children }: { id: string; title: string; aside?: ReactNode; children?: ReactNode }) {
  return (
    <div className={styles.pageHead}>
      <div className={styles.pageTitleLine}>
        <h2 id={id} tabIndex={-1} className={styles.pageTitle}>
          {title}
        </h2>
        {aside}
      </div>
      {children && <p className={styles.pageLead}>{children}</p>}
    </div>
  )
}
