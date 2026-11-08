import { useId, useMemo, useState, type CSSProperties, type KeyboardEvent, type Ref } from 'react'
import type { Space, TagCount } from '../api/types'
import { dragRegion, useWindowButtons } from '../desktop'
import { ChevronIcon, HashIcon, LockIcon, SettingsIcon, SidebarIcon } from '../ui/icons'
import { loadLocal, saveLocal } from './local-storage'
import { SpaceMenu, type SpaceInvite } from './space-menu'
import { DEFAULT_SPACE } from './spaces'
import type { ListFilter } from './store'
import { buildTree, type TagNode } from './tag-tree'
import styles from './tags-panel.module.css'

type Props = {
  tags: TagCount[]
  /** Hides the Locked row while there are none. */
  lockedCount: number
  filter: ListFilter
  onFilter: (filter: ListFilter) => void
  onSettings: () => void
  /** Every space, and the one shown: the header switches between them, and makes new ones. */
  spaces: Space[] | null
  space: string
  onSpace: (id: string) => void
  onNewSpace: () => void
  /** A space's Space info. */
  onSpaceInfo: (id: string) => void
  /** Invitations, and what others changed in each space (§7.9), for the switcher. */
  sharing?: {
    invites: readonly SpaceInvite[]
    changes: Readonly<Record<string, string>>
    onJoin: (id: string) => void
    onDecline: (id: string) => void
  }
  /** Docked beside the list (wide screens), it can be hidden; otherwise it's a screen of its own. */
  onHide?: () => void
  /** Docked and hidden: kept in place, out of reach, so hiding and showing can animate. */
  hidden?: boolean
  /** The Hide button, for focus to land on when the sidebar comes back. */
  hideRef?: Ref<HTMLButtonElement>
}

const EXPANDED_KEY = 'notes.tags.expanded'

const DEFAULT: Space = { id: DEFAULT_SPACE, name: 'Notes', icon: '', createdAt: 0, noteCount: 0, traits: [] }

/** Where the list can point: All notes, Untagged, Locked and the tag tree. Settings live here too. */
export function TagsPanel({
  tags,
  lockedCount,
  filter,
  onFilter,
  onSettings,
  spaces,
  space,
  onSpace,
  onNewSpace,
  onSpaceInfo,
  sharing,
  onHide,
  hidden = false,
  hideRef,
}: Props) {
  const tagsId = useId()
  const tree = useMemo(() => buildTree(tags), [tags])
  const [expanded, setExpanded] = useState(() => new Set(loadLocal<string[]>(EXPANDED_KEY, [])))

  const setNodeOpen = (key: string, on: boolean) => {
    const next = new Set(expanded)
    if (on) next.add(key)
    else next.delete(key)
    setExpanded(next)
    saveLocal(EXPANDED_KEY, [...next])
  }

  const selected = !filter ? 'all' : 'tag' in filter ? `#${filter.tag}` : 'locked' in filter ? 'locked' : 'untagged'

  // Up and down move between rows; right and left open and close a parent.
  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const rows = [...e.currentTarget.querySelectorAll<HTMLButtonElement>('[data-row]')]
    const at = rows.indexOf(document.activeElement as HTMLButtonElement)
    if (at < 0) return
    const row = rows[at]
    const key = row?.dataset.key
    const step = e.key === 'ArrowDown' ? 1 : e.key === 'ArrowUp' ? -1 : 0
    if (step) {
      e.preventDefault()
      rows[Math.min(rows.length - 1, Math.max(0, at + step))]?.focus()
    } else if (key && row?.dataset.parent && (e.key === 'ArrowRight' || e.key === 'ArrowLeft')) {
      e.preventDefault()
      setNodeOpen(key, e.key === 'ArrowRight')
    }
  }

  const renderNode = (node: TagNode, depth: number) => {
    const isOpen = expanded.has(node.key)
    const parent = node.children.length > 0
    return (
      <li key={node.key}>
        <div className={styles.item} style={{ '--depth': depth } as CSSProperties}>
          {parent ? (
            <button
              type="button"
              className={styles.expander}
              aria-expanded={isOpen}
              aria-label={`${isOpen ? 'Collapse' : 'Expand'} ${node.name}`}
              tabIndex={-1}
              onClick={() => setNodeOpen(node.key, !isOpen)}
            >
              <ChevronIcon />
            </button>
          ) : (
            <span className={styles.expander} aria-hidden="true" />
          )}
          <button
            type="button"
            className={styles.row}
            data-row=""
            data-key={node.key}
            data-parent={parent ? '' : undefined}
            aria-current={selected === `#${node.key}` ? 'true' : undefined}
            aria-label={`#${node.name}, ${node.count} ${node.count === 1 ? 'note' : 'notes'}`}
            onClick={() => onFilter({ tag: node.key, name: node.name })}
          >
            <HashIcon />
            <span className={styles.label}>{node.label}</span>
            <span className={styles.count} aria-hidden="true">
              {node.count}
            </span>
          </button>
        </div>
        {parent && isOpen && <ul className={styles.list}>{node.children.map((c) => renderNode(c, depth + 1))}</ul>}
      </li>
    )
  }

  // Docked, the switcher gets the top row to itself, level with the list's title; the tools go to the bottom,
  // level with the list's count. The macOS app keeps them up top, beside the window buttons.
  const windowButtons = useWindowButtons()
  const footer = !!onHide && !windowButtons
  const hideButton = onHide && (
    <button ref={hideRef} type="button" className={[styles.tool, styles.hide].join(' ')} onClick={onHide} aria-label="Hide sidebar" title="Hide sidebar">
      <SidebarIcon />
    </button>
  )

  return (
    <nav
      className={[styles.panel, onHide ? styles.docked : '', hidden ? styles.collapsed : ''].join(' ')}
      aria-label="Sidebar"
      inert={hidden}
      {...dragRegion('self')}
    >
      <div className={styles.inner}>
        <div className={styles.header} {...dragRegion('deep')}>
          {/* Before the list loads, the default space: the switcher keeps its place. */}
          <h1 className={styles.title}>
            <SpaceMenu spaces={spaces ?? [DEFAULT]} current={space} onSwitch={onSpace} onCreate={onNewSpace} onInfo={onSpaceInfo} {...sharing} />
          </h1>
          {!footer && hideButton}
          {!footer && (
            <button type="button" className={styles.tool} onClick={onSettings} aria-label="Settings" title="Settings">
              <SettingsIcon size={onHide ? 16 : 17} />
            </button>
          )}
        </div>
        <div className={styles.scroll} onKeyDown={onKeyDown}>
          <ul className={styles.list} aria-label="Show">
            <li>
              <div className={styles.item}>
                <span className={styles.expander} aria-hidden="true" />
                <button
                  type="button"
                  className={styles.row}
                  data-row=""
                  aria-current={selected === 'all' ? 'true' : undefined}
                  onClick={() => onFilter(null)}
                >
                  <span className={styles.label}>All notes</span>
                </button>
              </div>
            </li>
            <li>
              <div className={styles.item}>
                <span className={styles.expander} aria-hidden="true" />
                <button
                  type="button"
                  className={styles.row}
                  data-row=""
                  aria-current={selected === 'untagged' ? 'true' : undefined}
                  onClick={() => onFilter({ untagged: true })}
                >
                  <span className={styles.label}>Untagged</span>
                </button>
              </div>
            </li>
            {/* Kept while selected, so the last note unlocked doesn't pull the row from under the list. */}
            {(lockedCount > 0 || selected === 'locked') && (
              <li>
                <div className={styles.item}>
                  <span className={styles.expander} aria-hidden="true" />
                  <button
                    type="button"
                    className={styles.row}
                    data-row=""
                    aria-current={selected === 'locked' ? 'true' : undefined}
                    onClick={() => onFilter({ locked: true })}
                  >
                    <LockIcon />
                    <span className={styles.label}>Locked</span>
                  </button>
                </div>
              </li>
            )}
          </ul>
          <section aria-labelledby={tagsId}>
            <h2 id={tagsId} className={styles.section}>
              Tags
            </h2>
            {tree.length > 0 ? (
              <ul className={styles.list}>{tree.map((n) => renderNode(n, 0))}</ul>
            ) : (
              <p className={styles.hint}>
                Type <code>#tag</code> in a note to group it here. <code>#parent/child</code> nests.
              </p>
            )}
          </section>
        </div>
        {footer && (
          <div className={styles.footer}>
            <button type="button" className={styles.settings} onClick={onSettings}>
              <SettingsIcon />
              Settings
            </button>
            {hideButton}
          </div>
        )}
      </div>
    </nav>
  )
}
