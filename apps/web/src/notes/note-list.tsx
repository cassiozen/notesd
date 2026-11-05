import { memo, useEffect, useId, useMemo, useRef, useState, type MouseEvent, type PointerEvent, type ReactNode, type Ref } from 'react'
import { dragRegion } from '../desktop'
import { Button } from '../ui/button'
import { LockIcon, PlusIcon, SearchIcon, SidebarIcon } from '../ui/icons'
import { groupByDate, listTime, useMidnight } from './list-time'
import { useSearch } from './search'
import { SpaceIcon } from './space-icon'
import { spaceName, useSpaces } from './spaces'
import { useNotes } from './store'
import styles from './note-list.module.css'

type Props = {
  selectedId: string | null
  query: string
  onQuery: (q: string) => void
  onSelect: (id: string) => void
  /** A search result from another space: switch to it, then open the note. */
  onSelectElsewhere: (id: string, spaceId: string) => void
  onNew: () => void
  /**
   * The way to the sidebar: a back link when it's a screen of its own, a
   * button when the docked sidebar is hidden, nothing when it's showing.
   */
  tags: 'back' | 'show' | null
  onTags: () => void
  /** The Show sidebar button, for focus to land on when the sidebar goes. */
  showTagsRef?: Ref<HTMLButtonElement>
  mobile: boolean
}

export function NoteList({ selectedId, query, onQuery, onSelect, onSelectElsewhere, onNew, tags, onTags, showTagsRef, mobile }: Props) {
  const { items, status, hasMore, total, loadMore, refresh, version, filter, setFilter, space } = useNotes()
  const spaces = useSpaces()
  const many = (spaces?.length ?? 0) > 1
  const search = useSearch(query, filter, { space }, version)
  // The other spaces' matches, listed once asked for, for these words in this space.
  const q = query.trim()
  const [otherFor, setOtherFor] = useState<string | null>(null)
  const showOther = q !== '' && otherFor === `${space}\u0000${q}`
  const other = useSearch(query, null, { exceptSpace: space }, version, showOther)
  const scope = !filter ? null : 'tag' in filter ? `#${filter.name}` : 'locked' in filter ? 'Locked' : 'Untagged'
  const searching = q !== ''
  const n = search.results.length
  const name = spaceName(spaces, space)
  // With two spaces, the other one by name.
  const others = spaces?.filter((s) => s.id !== space) ?? []
  const elsewhere = others.length === 1 && others[0] ? others[0].name : 'other spaces'
  const current = spaces?.find((s) => s.id === space) ?? { icon: '' }
  // The sidebar beside the list, showing.
  const docked = tags === null

  const midnight = useMidnight()
  // midnight isn't read: it's there so the sections move on with the day, not only when the notes change.
  // oxlint-disable-next-line react-hooks/exhaustive-deps, react/memo-dependencies
  const groups = useMemo(() => groupByDate(items), [items, midnight])

  const count = searching
    ? search.status === 'loading'
      ? 'Searching…'
      : `${n}${search.hasMore ? '+' : ''} ${n === 1 ? 'result' : 'results'}`
    : status === 'ready'
      ? `${total} ${total === 1 ? 'note' : 'notes'}`
      : ''

  return (
    <aside className={[styles.list, tags === 'show' ? styles.sidebarHidden : ''].join(' ')} aria-label="Notes" {...dragRegion('self')}>
      <div className={styles.header} {...dragRegion('deep')}>
        {tags === 'back' && (
          <button type="button" className={styles.back} onClick={onTags} aria-label="Sidebar" title="Sidebar">
            <span className={styles.chevron} aria-hidden="true">
              ‹
            </span>
          </button>
        )}
        <div className={styles.titleRow}>
          {/* The space's icon, so the space shows with the sidebar away; beside it, the sidebar's shows it. */}
          {tags === 'back' ? (
            <SpaceIcon space={current} size={mobile ? 28 : 22} />
          ) : (
            // Docked sidebar: always here, grown in as the sidebar goes, so the title slides over instead of jumping.
            <span className={[styles.spaceMark, styles.reveal].join(' ')} data-shown={docked ? undefined : ''}>
              <SpaceIcon space={current} size={22} />
            </span>
          )}
          {/* What the list shows: All Notes, or the filter, which × clears. */}
          <h1 className={[styles.heading, styles.scoped].join(' ')}>
            <span className={styles.scope} title={scope ?? undefined}>
              {scope ?? 'All Notes'}
            </span>
            {scope && (
              <button type="button" className={styles.clearScope} onClick={() => setFilter(null)} aria-label="Show all notes">
                ×
              </button>
            )}
          </h1>
          {tags !== 'back' && (
            <button
              ref={showTagsRef}
              type="button"
              className={[styles.showTags, styles.reveal].join(' ')}
              data-shown={docked ? undefined : ''}
              inert={docked}
              onClick={onTags}
              aria-label="Show sidebar"
              title="Show sidebar"
            >
              <SidebarIcon />
            </button>
          )}
          {!mobile && (
            <Button variant="accent" size="sm" className={styles.newNote} onClick={onNew}>
              <PlusIcon />
              New note
            </Button>
          )}
        </div>
        <div className={styles.search}>
          <span className={styles.searchIcon}>
            <SearchIcon />
          </span>
          <label htmlFor="search" className="visually-hidden">
            Search notes
          </label>
          <input
            id="search"
            type="search"
            className={styles.searchInput}
            placeholder={scope ? `Search in ${scope}` : many ? `Search in ${name}` : 'Search all notes'}
            spellCheck={false}
            autoComplete="off"
            value={query}
            onChange={(e) => onQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Escape') onQuery('')
              // Not a result for the words typed yet.
              const first = search.stale ? undefined : search.results[0]
              if (e.key === 'Enter' && first) onSelect(first.id)
            }}
          />
          {query && (
            <button type="button" className={styles.clear} onClick={() => onQuery('')} aria-label="Clear search">
              ×
            </button>
          )}
        </div>
      </div>

      <div className={styles.scroll}>
        {searching ? (
          <>
            {search.status === 'error' && (
              <Message title="Search didn’t work.">Check your connection and try again.</Message>
            )}
            {search.status === 'ready' && n === 0 && (
              <Message title="No results">
                {/* Matches elsewhere follow: this space is the one with none. */}
                {search.otherSpaces > 0 ? `Nothing in ${name} matches` : 'Nothing matches'} “{query.trim()}”.
              </Message>
            )}
            {n > 0 && (
              <Section label="Top results" listLabel="Search results" stale={search.stale}>
                {search.results.map((r) => (
                  <Row
                    key={r.id}
                    id={r.id}
                    title={r.title}
                    time={r.updatedAt}
                    selected={r.id === selectedId}
                    midnight={midnight}
                    onSelect={onSelect}
                    locked={r.locked}
                    excerpt={r.excerpt}
                  />
                ))}
              </Section>
            )}
            {search.hasMore && <LoadMore onVisible={search.loadMore} />}
            {search.status === 'ready' && search.otherSpaces > 0 && !showOther && (
              <div className={styles.more}>
                <Button size="sm" onClick={() => setOtherFor(`${space}\u0000${q}`)}>
                  {search.otherSpaces} more in {elsewhere}
                </Button>
              </div>
            )}
            {showOther && other.results.length > 0 && (
              <Section label="Other spaces" listLabel="Results in other spaces">
                {other.results.map((r) => (
                  <Row
                    key={r.id}
                    id={r.id}
                    title={r.title}
                    time={r.updatedAt}
                    selected={r.id === selectedId}
                    midnight={midnight}
                    onSelect={(id) => onSelectElsewhere(id, r.spaceId)}
                    badge={spaceName(spaces, r.spaceId)}
                    locked={r.locked}
                    excerpt={r.excerpt}
                  />
                ))}
              </Section>
            )}
            {showOther && other.hasMore && <LoadMore onVisible={other.loadMore} />}
            {showOther && other.status === 'error' && (
              <Message title="Couldn’t search the other spaces.">Check your connection and try again.</Message>
            )}
          </>
        ) : (
          <>
            {status === 'error' && (
              <Message title="Couldn’t load your notes.">
                Check that the server is running.{' '}
                <Button size="sm" onClick={refresh}>
                  Try again
                </Button>
              </Message>
            )}
            {status === 'ready' && items.length === 0 && (
              <Message>{scope ? `No notes in ${scope}.` : many ? `No notes in ${name} yet.` : 'No notes yet.'}</Message>
            )}
            {groups.map((g) => (
              <Section key={g.label} label={g.label}>
                {g.items.map((note) => (
                  <Row
                    key={note.id}
                    id={note.id}
                    title={note.title}
                    time={note.updatedAt}
                    selected={note.id === selectedId}
                    midnight={midnight}
                    onSelect={onSelect}
                    locked={note.locked}
                    preview={note.preview}
                  />
                ))}
              </Section>
            ))}
            {hasMore && <LoadMore onVisible={loadMore} />}
          </>
        )}
      </div>

      <div className={styles.footer}>
        <span className={styles.count} aria-live="polite">
          {count}
        </span>
        {mobile && (
          <button type="button" className={styles.fab} onClick={onNew} aria-label="New note" title="New note">
            <PlusIcon size={18} />
          </button>
        )}
      </div>
    </aside>
  )
}

function Section({ label, listLabel, stale, children }: { label: string; listLabel?: string; stale?: boolean; children: ReactNode }) {
  const id = useId()
  return (
    <section className={styles.group} aria-labelledby={id}>
      <h2 id={id} className={styles.groupLabel}>
        {label}
      </h2>
      <ul className={styles.rows} aria-label={listLabel} aria-busy={stale || undefined} data-stale={stale ? '' : undefined}>
        {children}
      </ul>
    </section>
  )
}

type RowProps = {
  id: string
  title: string
  time: number
  /** The space a result from another space is in. */
  badge?: string
  locked: boolean
  /** A listed note's first lines… */
  preview?: string
  /** …or a search result's excerpt, as HTML. */
  excerpt?: string
  selected: boolean
  onSelect: (id: string) => void
  /** Unread: changes at midnight, so a memoized row re-renders and its time label (a clock time, Yesterday…) moves on. */
  midnight: number
}

const plainClick = (e: MouseEvent) => e.button === 0 && !e.metaKey && !e.ctrlKey && !e.shiftKey

/** Plain props, so a list change re-renders only the rows it touched; typing in a note changes the list every few keystrokes. */
const Row = memo(function Row({ id, title, time, badge, locked, preview, excerpt, selected, onSelect }: RowProps) {
  const href = `/notes/${id}`
  return (
    <li>
      <a
        href={href}
        className={styles.row}
        aria-current={selected ? 'page' : undefined}
        // Opens on press, as native lists do: a click's release comes ~100ms later. Touch waits for the
        // click, or starting a scroll on a row would open it.
        onPointerDown={(e: PointerEvent) => {
          if (e.pointerType === 'mouse' && plainClick(e)) onSelect(id)
        }}
        onClick={(e: MouseEvent) => {
          if (!plainClick(e)) return
          e.preventDefault()
          onSelect(id)
        }}
      >
        <span className={styles.rowTitle}>{title.trim() || 'New note'}</span>
        <span className={styles.rowMeta}>
          {badge && <span className={styles.rowSpace}>{badge}</span>}
          <span className={styles.rowTime}>{listTime(time)}</span>
          <span className={styles.rowPreview}>
            {locked ? (
              <Locked />
            ) : excerpt !== undefined ? (
              // The server escapes the note text and adds only <mark>: the one place raw HTML is allowed.
              <span dangerouslySetInnerHTML={{ __html: excerpt }} />
            ) : preview ? (
              preview.split('\n').join(' · ')
            ) : (
              'No additional text'
            )}
          </span>
        </span>
      </a>
    </li>
  )
})

/** A locked note's preview: its body is encrypted. */
function Locked() {
  return (
    <span className={styles.locked}>
      <LockIcon size={11} />
      Locked
    </span>
  )
}

function Message({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <div className={styles.message} role={title ? 'status' : undefined}>
      {title && <span className={styles.messageTitle}>{title}</span>}
      <span>{children}</span>
    </div>
  )
}

/** Infinite scroll: loads the next page when this scrolls into view. */
function LoadMore({ onVisible }: { onVisible: () => void }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const io = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) onVisible()
    })
    io.observe(el)
    return () => io.disconnect()
  }, [onVisible])
  return (
    <div ref={ref} className={styles.more}>
      <Button size="sm" onClick={onVisible}>
        Load more
      </Button>
    </div>
  )
}
