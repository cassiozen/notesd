import { useCallback, useEffect, useRef, useState } from 'react'
import { navigate, parseRoute, usePathname } from '../router'
import { SettingsSheet } from '../settings/settings'
import { NewSpaceDialog } from '../settings/spaces'
import { Backdrop } from '../ui/backdrop'
import { PHONE, WIDE, useMediaQuery } from '../ui/media'
import { Toast } from '../ui/toast'
import { EmptyPane } from './empty'
import { NoteList } from './note-list'
import { NotePane } from './note-pane'
import type { NoteSaver } from './saver'
import { DEFAULT_SPACE, onSpacesChange, spaceName, useSpaces } from './spaces'
import { NotesProvider, useNotes, type ListFilter } from './store'
import { TagsPanel } from './tags-panel'
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
  const { items, status, newDraft, remove, undoRemove, removed, tags, lockedCount, filter, setFilter, space, setSpace, move } =
    useNotes()
  const spaces = useSpaces()
  // A note the server doesn't have yet: open, but not in the URL until it's saved.
  const [draft, setDraft] = useState<NoteSaver | null>(null)
  const [query, setQuery] = useState('')
  const [notice, setNotice] = useState<Notice | null>(null)
  // New space…, from the sidebar's switcher.
  const [newSpace, setNewSpace] = useState(false)

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
  const tagsPanel = (onHide?: () => void) => (
    <TagsPanel
      tags={tags}
      lockedCount={lockedCount}
      filter={filter}
      onFilter={showFilter}
      onSettings={() => navigate('/settings')}
      spaces={spaces}
      space={space}
      onSpace={switchSpace}
      onNewSpace={() => setNewSpace(true)}
      onHide={onHide}
    />
  )

  return (
    <>
      <Backdrop />
      <div className={styles.app}>
        {sidebar && tagsPanel(() => hideSidebar(true))}
        {tagsScreen && tagsPanel()}
        {showList && (
          <NoteList
            selectedId={selectedId}
            query={query}
            onQuery={setQuery}
            onSelect={select}
            onSelectElsewhere={(id, spaceId) => {
              setSpace(spaceId)
              select(id)
            }}
            onNew={startNote}
            tags={!wide ? 'back' : sidebarHidden ? 'show' : null}
            onTags={() => (wide ? hideSidebar(false) : navigate('/tags'))}
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

      {notice && <Toast text={notice.text} onUndo={notice.undo ? () => undo(notice.id) : undefined} />}

      {route.name === 'settings' && (
        <SettingsSheet
          username={username}
          onSpaceCreated={switchSpace}
          onClose={() => navigate(settingsFrom === '/settings' ? listPath : settingsFrom)}
        />
      )}

      {newSpace && (
        <NewSpaceDialog
          onClose={() => setNewSpace(false)}
          onCreated={(id) => {
            setNewSpace(false)
            switchSpace(id)
          }}
        />
      )}
    </>
  )
}
