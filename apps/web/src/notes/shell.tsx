import { useCallback, useEffect, useRef, useState } from 'react'
import { navigate, parseRoute, usePathname } from '../router'
import { SettingsSheet } from '../settings/settings'
import { Backdrop } from '../ui/backdrop'
import { PHONE, WIDE, useMediaQuery } from '../ui/media'
import { Toast } from '../ui/toast'
import { EmptyPane } from './empty'
import { NoteList, searchPlaceholder } from './note-list'
import { NotePane } from './note-pane'
import type { NoteSaver } from './saver'
import { useSearch } from './search'
import { DEFAULT_SPACE, onSpacesChange, spaceName, useSpaces } from './spaces'
import { NotesProvider, useNotes, type ListFilter } from './store'
import { TagsPanel } from './tags-panel'
import { TopBar } from './top-bar'
import styles from './shell.module.css'

export function Shell({ username }: { username: string }) {
  return (
    <NotesProvider>
      <ShellView username={username} />
    </NotesProvider>
  )
}

type Notice = { id: string; text: string; undo: boolean }

/** Messages after a delete that didn't go through stay up this long. */
const NOTICE_MS = 5000

const SIDEBAR_KEY = 'notes.sidebar.hidden'

/** Back after this long away, the app starts over in the default space (§7.7). */
export const SPACE_RESET_MS = 30 * 60_000

export function loadSidebarHidden(): boolean {
  try {
    return localStorage.getItem(SIDEBAR_KEY) === 'true'
  } catch {
    return false
  }
}

function ShellView({ username }: { username: string }) {
  const pathname = usePathname()
  const route = parseRoute(pathname)
  const mobile = useMediaQuery(PHONE)
  // Wide: the tags sit in a sidebar. Narrower, they're a screen of their own (/tags) in the list's place.
  const wide = useMediaQuery(WIDE)
  const [sidebarHidden, setSidebarHidden] = useState(loadSidebarHidden)
  const {
    items,
    status,
    newDraft,
    remove,
    undoRemove,
    removed,
    tags,
    lockedCount,
    filter,
    setFilter,
    space,
    setSpace,
    move,
    version,
  } = useNotes()
  const spaces = useSpaces()
  // A note the server doesn't have yet: open, but not in the URL until it's saved.
  const [draft, setDraft] = useState<NoteSaver | null>(null)
  const [query, setQuery] = useState('')
  // Searched here, not in the list: the top bar's Enter opens the first result.
  const search = useSearch(query, filter, { space }, version)
  const [notice, setNotice] = useState<Notice | null>(null)

  // Settings and the tags screen open over the app; the note behind them stays open.
  const over = route.name === 'settings' || route.name === 'tags'
  const routeNote = route.name === 'note' ? route.id : null
  const [behind, setBehind] = useState(routeNote)
  if (!over && routeNote !== behind) setBehind(routeNote)
  const noteId = over ? behind : routeNote
  const selectedId = draft?.id ?? noteId
  // Closing settings goes back to where it was opened, the tags screen included.
  const [settingsFrom, setSettingsFrom] = useState(pathname)
  if (route.name !== 'settings' && pathname !== settingsFrom) setSettingsFrom(pathname)
  const listPath = behind ? `/notes/${behind}` : '/'

  // Grown wide enough for the sidebar: the tags screen has nothing left to show.
  useEffect(() => {
    if (wide && route.name === 'tags') navigate(listPath, { replace: true })
  }, [wide, route.name, listPath])

  // Back/forward replaces whatever draft was open.
  useEffect(() => {
    const onPop = () => setDraft(null)
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])

  // Side by side, the pane always shows a note when there is one.
  const first = items[0]?.id
  useEffect(() => {
    if (mobile || draft || route.name !== 'notes' || status !== 'ready' || !first) return
    navigate(`/notes/${first}`, { replace: true })
  }, [mobile, draft, route.name, status, first])

  useEffect(() => {
    if (!notice || notice.undo) return
    const t = setTimeout(() => setNotice(null), NOTICE_MS)
    return () => clearTimeout(t)
  }, [notice])

  const select = useCallback(
    (id: string) => {
      if (draft?.id === id) return
      setDraft(null)
      navigate(`/notes/${id}`)
    },
    [draft],
  )

  /** Shows another space: its list from the top, without the note that was open. */
  const switchSpace = useCallback(
    (id: string) => {
      // The one shown: nothing to reset, and the open note stays.
      if (id === space) return
      setDraft(null)
      setSpace(id)
      navigate('/')
    },
    [space, setSpace],
  )

  // The space shown was deleted (in Settings, or on another device): back to the default.
  const shown = useRef(space)
  const open = useRef(selectedId !== null)
  useEffect(() => {
    shown.current = space
    open.current = selectedId !== null
  })
  useEffect(
    () =>
      onSpacesChange((list) => {
        if (!list.some((s) => s.id === shown.current)) switchSpace(DEFAULT_SPACE)
      }),
    [switchSpace],
  )

  // Away for a while with no note open: start over in the default space.
  useEffect(() => {
    let hiddenAt = document.visibilityState === 'hidden' ? Date.now() : null
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') {
        hiddenAt = Date.now()
        return
      }
      if (hiddenAt !== null && Date.now() - hiddenAt > SPACE_RESET_MS && !open.current) setSpace(DEFAULT_SPACE)
      hiddenAt = null
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => document.removeEventListener('visibilitychange', onVisibility)
  }, [setSpace])

  const startNote = () => {
    setQuery('')
    setDraft(newDraft())
  }

  const created = useCallback((id: string) => {
    setDraft(null)
    // No transition: it runs the URL change async, so the draft's pane would
    // unmount first (selectedId drops back to the old route) and the editor lose focus.
    navigate(`/notes/${id}`, { animate: false })
  }, [])

  /** Where to go when the open note goes away: the next one down, or the list on a phone. */
  const leave = (id: string) => {
    const rest = items.filter((n) => n.id !== id)
    const at = items.findIndex((n) => n.id === id)
    const next = mobile ? undefined : (rest[at] ?? rest[at - 1])
    setDraft(null)
    navigate(next ? `/notes/${next.id}` : '/')
  }

  const deleteNote = (saver: NoteSaver) => {
    const id = saver.id
    leave(id)
    // An untouched new note just disappears (the pane releases it).
    if (saver.isBlankDraft()) return
    setNotice({ id, text: 'Note deleted', undo: true })
    void remove(id).then((outcome) => {
      if (outcome === 'changed') setNotice({ id, text: 'Not deleted: it changed on another device.', undo: false })
      else if (outcome === 'failed') setNotice({ id, text: 'Couldn’t delete the note. Check your connection.', undo: false })
      else setNotice((n) => (n?.id === id ? null : n))
    })
  }

  const moveNote = (saver: NoteSaver, spaceId: string) => {
    leave(saver.id)
    move(saver, spaceId)
    setNotice({ id: saver.id, text: `Moved to ${spaceName(spaces, spaceId)}`, undo: false })
  }

  const undo = (id: string) => {
    undoRemove(id)
    select(id)
  }

  const hideSidebar = (hidden: boolean) => {
    setSidebarHidden(hidden)
    try {
      localStorage.setItem(SIDEBAR_KEY, String(hidden))
    } catch {
      // Not remembered; nothing else depends on it.
    }
  }

  const showFilter = (next: ListFilter) => {
    setFilter(next)
    // On a phone the list replaces the tags screen; side by side, the note stays open beside it.
    if (!wide) navigate(mobile ? '/' : listPath)
  }

  const tagsScreen = !wide && route.name === 'tags'
  const sidebar = wide && !sidebarHidden
  const showList = !tagsScreen && (!mobile || selectedId === null)
  const showPane = !mobile || (!tagsScreen && selectedId !== null)
  const tagsPanel = (docked: boolean) => (
    <TagsPanel
      tags={tags}
      lockedCount={lockedCount}
      filter={filter}
      onFilter={showFilter}
      // Wider than a phone, Settings is in the top bar.
      onSettings={mobile ? () => navigate('/settings') : undefined}
      spaces={spaces}
      space={space}
      onSpace={switchSpace}
      docked={docked}
    />
  )

  // The top bar's toggle: the docked sidebar when wide, else the tags screen in the list's place.
  const sidebarOpen = wide ? !sidebarHidden : tagsScreen
  const toggleSidebar = () => {
    if (wide) hideSidebar(!sidebarHidden)
    else navigate(tagsScreen ? listPath : '/tags')
  }
  const onQuery = (q: string) => {
    setQuery(q)
    // The results show in the list: bring it back from under the tags screen.
    if (q && tagsScreen) navigate(listPath)
  }

  const columns = (
    <div className={[styles.app, mobile ? '' : styles.underBar].join(' ')}>
      {sidebar && tagsPanel(true)}
      {tagsScreen && tagsPanel(false)}
      {showList && (
        <NoteList
          selectedId={selectedId}
          query={query}
          onQuery={onQuery}
          search={search}
          onSelect={select}
          onSelectElsewhere={(id, spaceId) => {
            setSpace(spaceId)
            select(id)
          }}
          onSpace={switchSpace}
          onNew={startNote}
          onTags={() => navigate('/tags')}
          mobile={mobile}
        />
      )}
      {showPane &&
        (selectedId ? (
          <NotePane
            key={selectedId}
            id={draft ? undefined : selectedId}
            draft={draft ?? undefined}
            mobile={mobile}
            onBack={() => {
              setDraft(null)
              navigate('/')
            }}
            onNew={startNote}
            onCreated={created}
            onOpenDraft={setDraft}
            onDelete={deleteNote}
            onMove={moveNote}
            onGone={(id) => {
              leave(id)
              removed(id)
            }}
          />
        ) : (
          <EmptyPane hasNotes={items.length > 0} onNew={startNote} />
        ))}
    </div>
  )

  return (
    <>
      <Backdrop />
      {/* One frame at every width, the columns always its second child: crossing the phone
          breakpoint mustn't remount the list and the editor (focus, caret, scroll). */}
      <div className={styles.frame}>
        {!mobile && (
          <TopBar
            sidebarOpen={sidebarOpen}
            onSidebar={toggleSidebar}
            query={query}
            onQuery={onQuery}
            placeholder={searchPlaceholder(filter, spaces, space)}
            onOpenFirst={() => {
              const top = search.results[0]
              if (top) select(top.id)
            }}
            onNew={startNote}
            onSettings={() => navigate('/settings')}
          />
        )}
        {columns}
      </div>

      {notice && <Toast text={notice.text} onUndo={notice.undo ? () => undo(notice.id) : undefined} />}

      {route.name === 'settings' && (
        <SettingsSheet
          username={username}
          onSpaceCreated={switchSpace}
          onClose={() => navigate(settingsFrom === '/settings' ? listPath : settingsFrom)}
        />
      )}
    </>
  )
}
