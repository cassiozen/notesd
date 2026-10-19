import { useEffect, useId, useRef, useState, type MouseEvent, type ReactNode } from 'react'
import type { SearchResult, Space } from '../api/types'
import { Button } from '../ui/button'
import { LockIcon, PlusIcon, SearchIcon } from '../ui/icons'
import { groupByDate, listTime } from './list-time'
import { useSearch } from './search'
import { SpaceMenu } from './space-menu'
import { spaceName, useSpaces } from './spaces'
import { useNotes, type ListFilter } from './store'
import styles from './note-list.module.css'

type Props = {
  selectedId: string | null
  query: string
  onQuery: (q: string) => void
  /** The current space's matches for query: searched by the shell, whose top bar opens the first. */
  search: Search
  onSelect: (id: string) => void
  /** A search result from another space: switch to it, then open the note. */
  onSelectElsewhere: (id: string, spaceId: string) => void
  onSpace: (id: string) => void
  onNew: () => void
  /** On a phone: back to the sidebar, a screen of its own there. */
  onTags: () => void
  /**
   * A phone: the list carries its own search, the switcher and New note. Wider, they're in the
   * top bar and the sidebar, and the list has only its title.
   */
  mobile: boolean
}

export type Search = ReturnType<typeof useSearch>

/** What the search field says it searches: the tag or other filter, else the space. */
export function searchPlaceholder(filter: ListFilter, spaces: Space[] | null, space: string): string {
  if (filter) return `Search in ${filterName(filter)}`
  return (spaces?.length ?? 0) > 1 ? `Search in ${spaceName(spaces, space)}` : 'Search all notes'
}

function filterName(filter: NonNullable<ListFilter>): string {
  return 'tag' in filter ? `#${filter.name}` : 'locked' in filter ? 'Locked' : 'Untagged'
}

export function NoteList({ selectedId, query, onQuery, search, onSelect, onSelectElsewhere, onSpace, onNew, onTags, mobile }: Props) {
  const { items, status, hasMore, total, loadMore, refresh, version, filter, setFilter, space } = useNotes()
  const spaces = useSpaces()
  const many = (spaces?.length ?? 0) > 1
  // The other spaces' matches, listed once asked for, for these words in this space.
  const q = query.trim()
  const [otherFor, setOtherFor] = useState<string | null>(null)
  const showOther = q !== '' && otherFor === `${space}\u0000${q}`
  const other = useSearch(query, null, { exceptSpace: space }, version, showOther)
  const scope = filter ? filterName(filter) : null
  const searching = q !== ''
  const n = search.results.length
  const name = spaceName(spaces, space)
  // A phone's title is the switcher; wider, it sits in the sidebar only.
  const switcher = mobile && many && spaces !== null && !scope
  // With two spaces, the other one by name.
  const others = spaces?.filter((s) => s.id !== space) ?? []
  const elsewhere = others.length === 1 && others[0] ? others[0].name : 'other spaces'

  const count = searching
    ? search.status === 'loading' && n === 0
      ? 'Searching…'
      : `${n}${search.hasMore ? '+' : ''} ${n === 1 ? 'result' : 'results'}`
    : status === 'ready'
      ? `${total} ${total === 1 ? 'note' : 'notes'}`
      : ''

  return (
    <aside className={styles.list} aria-label="Notes">
      <div className={styles.header}>
        {mobile && (
          <button type="button" className={styles.back} onClick={onTags} aria-label="Sidebar" title="Sidebar">
            <span className={styles.chevron} aria-hidden="true">
              ‹
            </span>
          </button>
        )}
        <div className={styles.titleRow}>
          {/* With a switcher, its icon is the mark. */}
          {mobile && !switcher && <span className={styles.mark} aria-hidden="true" />}
          {scope ? (
            <h1 className={[styles.heading, styles.scoped].join(' ')}>
              <span className={styles.scope}>{scope}</span>
              <button type="button" className={styles.clearScope} onClick={() => setFilter(null)} aria-label="Show all notes">
                ×
              </button>
            </h1>
          ) : (
            <h1 className={many ? [styles.heading, styles.scoped].join(' ') : styles.heading}>
              {switcher && spaces ? (
                <SpaceMenu spaces={spaces} current={space} onSwitch={onSpace} />
              ) : many ? (
                <span className={styles.scope} title={name}>
                  {name}
                </span>
              ) : (
                'Notes'
              )}
            </h1>
          )}
        </div>
        {mobile && (
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
              placeholder={searchPlaceholder(filter, spaces, space)}
              spellCheck={false}
              autoComplete="off"
              value={query}
              onChange={(e) => onQuery(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Escape') onQuery('')
                const first = search.results[0]
                if (e.key === 'Enter' && first) onSelect(first.id)
              }}
            />
            {query && (
              <button type="button" className={styles.clear} onClick={() => onQuery('')} aria-label="Clear search">
                ×
              </button>
            )}
          </div>
        )}
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
              <Section label="Top results" listLabel="Search results">
                {search.results.map((r) => (
                  <Row
                    key={r.id}
                    id={r.id}
                    title={r.title}
                    time={r.updatedAt}
                    selected={r.id === selectedId}
                    onSelect={onSelect}
                    preview={excerpt(r)}
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
                    onSelect={(id) => onSelectElsewhere(id, r.spaceId)}
                    badge={spaceName(spaces, r.spaceId)}
                    preview={excerpt(r)}
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
            {groupByDate(items).map((g) => (
              <Section key={g.label} label={g.label}>
                {g.items.map((note) => (
                  <Row
                    key={note.id}
                    id={note.id}
                    title={note.title}
                    time={note.updatedAt}
                    selected={note.id === selectedId}
                    onSelect={onSelect}
                    preview={
                      note.locked ? <Locked /> : note.preview ? note.preview.split('\n').join(' · ') : 'No additional text'
                    }
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

function Section({ label, listLabel, children }: { label: string; listLabel?: string; children: ReactNode }) {
  const id = useId()
  return (
    <section className={styles.group} aria-labelledby={id}>
      <h2 id={id} className={styles.groupLabel}>
        {label}
      </h2>
      <ul className={styles.rows} aria-label={listLabel}>
        {children}
      </ul>
    </section>
  )
}

/** A search result's excerpt. */
function excerpt(r: SearchResult): ReactNode {
  // The server escapes the note text and adds only <mark>: the one place raw HTML is allowed.
  return r.locked ? <Locked /> : <span dangerouslySetInnerHTML={{ __html: r.excerpt }} />
}

type RowProps = {
  id: string
  title: string
  time: number
  /** The space a result from another space is in. */
  badge?: string
  preview: ReactNode
  selected: boolean
  onSelect: (id: string) => void
}

function Row({ id, title, time, badge, preview, selected, onSelect }: RowProps) {
  const href = `/notes/${id}`
  return (
    <li>
      <a
        href={href}
        className={styles.row}
        aria-current={selected ? 'page' : undefined}
        onClick={(e: MouseEvent) => {
          if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
          e.preventDefault()
          onSelect(id)
        }}
      >
        <span className={styles.rowTitle}>{title.trim() || 'New note'}</span>
        <span className={styles.rowMeta}>
          {badge && <span className={styles.rowSpace}>{badge}</span>}
          <span className={styles.rowTime}>{listTime(time)}</span>
          <span className={styles.rowPreview}>{preview}</span>
        </span>
      </a>
    </li>
  )
}

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
