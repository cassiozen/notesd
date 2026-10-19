import { Button } from '../ui/button'
import { PlusIcon, SearchIcon, SettingsIcon, SidebarIcon } from '../ui/icons'
import styles from './top-bar.module.css'

/**
 * Across the top beside a phone's width: the sidebar's toggle, search
 * (centred, its results in the list) and the app's actions.
 */
export function TopBar({
  sidebarOpen,
  onSidebar,
  query,
  onQuery,
  placeholder,
  onOpenFirst,
  onNew,
  onSettings,
}: {
  sidebarOpen: boolean
  onSidebar: () => void
  query: string
  onQuery: (q: string) => void
  placeholder: string
  /** Enter in the search field: open the first result. */
  onOpenFirst: () => void
  onNew: () => void
  onSettings: () => void
}) {
  const sidebar = sidebarOpen ? 'Hide sidebar' : 'Show sidebar'
  return (
    <header className={styles.bar}>
      <div className={styles.start}>
        <button
          type="button"
          className={styles.tool}
          onClick={onSidebar}
          aria-label={sidebar}
          aria-pressed={sidebarOpen}
          title={sidebar}
        >
          <SidebarIcon />
        </button>
      </div>
      <div className={styles.search} role="search">
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
          placeholder={placeholder}
          spellCheck={false}
          autoComplete="off"
          value={query}
          onChange={(e) => onQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Escape') onQuery('')
            if (e.key === 'Enter') onOpenFirst()
          }}
        />
        {query && (
          <button type="button" className={styles.clear} onClick={() => onQuery('')} aria-label="Clear search">
            ×
          </button>
        )}
      </div>
      <div className={styles.end}>
        <Button variant="accent" size="sm" className={styles.newNote} onClick={onNew}>
          <PlusIcon />
          New note
        </Button>
        <button type="button" className={styles.tool} onClick={onSettings} aria-label="Settings" title="Settings">
          <SettingsIcon />
        </button>
      </div>
    </header>
  )
}
