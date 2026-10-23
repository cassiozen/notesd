import { createContext, use, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { notes as api } from '../api/notes'
import type { ListItem, TagCount } from '../api/types'
import { useAuth } from '../auth/auth-context'
import { lockSession, useLockStatus } from '../locks/session'
import { preview as previewOf } from './plaintext'
import { NoteSaver, timing, type Fields } from './saver'
import { DEFAULT_SPACE, reloadSpaces } from './spaces'
import { hasTag, tags, tagText } from './tags'

/** What the list shows: every note, one tag (by key, with its nested tags), notes without tags, or locked notes. */
export type ListFilter = { tag: string; name: string } | { untagged: true } | { locked: true } | null

const FILTER_KEY = 'notes.filter'

/**
 * The list last shown in this browser, so the app reopens on it. Storage may
 * be missing or throw. The app opens in the default space (§7.7), so a filter
 * picked in another one isn't brought back; nor is someone else's, which
 * would name one of their tags.
 */
function savedFilter(username: string): ListFilter {
  try {
    const saved = JSON.parse(localStorage.getItem(FILTER_KEY) ?? 'null') as {
      username?: string
      space?: string
      filter?: ListFilter
    } | null
    if (saved?.space !== DEFAULT_SPACE || saved.username?.toLowerCase() !== username.toLowerCase()) return null
    const f = saved.filter
    if (f && 'tag' in f && typeof f.tag === 'string' && typeof f.name === 'string') return { tag: f.tag, name: f.name }
    if (f && 'untagged' in f && f.untagged === true) return { untagged: true }
    if (f && 'locked' in f && f.locked === true) return { locked: true }
  } catch {
    // Starts on All notes.
  }
  return null
}

function saveFilter(username: string, space: string, filter: ListFilter) {
  try {
    localStorage.setItem(FILTER_KEY, JSON.stringify({ username, space, filter }))
  } catch {
    // Not remembered; nothing else depends on it.
  }
}

function matches(filter: ListFilter, fields: Fields, locked: boolean): boolean {
  if (!filter) return true
  if ('locked' in filter) return locked
  // A locked body is ciphertext on the server: only the title's tags count (§10.1).
  const noteTags = tags(fields.title, locked ? '' : fields.content)
  return 'tag' in filter ? hasTag(noteTags, filter.tag) : noteTags.length === 0
}

type ListState = {
  items: ListItem[]
  status: 'loading' | 'ready' | 'error'
  hasMore: boolean
  /** Notes on the server, adjusted for local creates and deletes. */
  total: number
  /** Deleted here but still undoable: not shown, not yet sent. */
  hidden: string[]
  /** Bumped on every saved change, so derived views (search) can refresh. */
  version: number
}

export type DeleteOutcome = 'deleted' | 'undone' | 'changed' | 'failed'

type NotesContextValue = Omit<ListState, 'hidden'> & {
  /** The space the list, tags and search show (§7.7); this tab's own, never synced. */
  space: string
  /** Shows another space, from All notes. */
  setSpace: (space: string) => void
  /** Every tag in the space's live notes, refreshed after saves. */
  tags: TagCount[]
  /** Live locked notes, refreshed with the tags. */
  lockedCount: number
  filter: ListFilter
  /** Shows another slice of the notes, loading it from the start. */
  setFilter: (filter: ListFilter) => void
  loadMore: () => void
  refresh: () => void
  /** The saver for a note, creating one from server data if needed. */
  saverFor: (init: ConstructorParameters<typeof NoteSaver>[0]) => NoteSaver
  existingSaver: (id: string) => NoteSaver | undefined
  /** A new note, listed right away but sent to the server only once it has text (more than the selected tag). */
  newDraft: () => NoteSaver
  /** Drop a saver that has nothing left to do (closed editor). */
  release: (id: string) => void
  removed: (id: string) => void
  /** Live title and preview while typing, before the save lands. */
  touch: (id: string, summary: { title: string; preview: string }) => void
  /** Moves a note to another space: it leaves this space's list now. */
  move: (saver: NoteSaver, spaceId: string) => void
  /** Hides the note now and deletes it once the undo window passes. */
  remove: (id: string) => Promise<DeleteOutcome>
  undoRemove: (id: string) => void
}

const NotesContext = createContext<NotesContextValue | null>(null)

/** Other devices may have changed things; refetch on focus after this long. */
const STALE_MS = 30_000

/** Saves come in bursts while typing; refetch the filters once they settle. */
const FILTERS_DEBOUNCE_MS = 500

type PendingDelete = { timer: ReturnType<typeof setTimeout>; resolve: (outcome: DeleteOutcome) => void }

const counted = (item: ListItem | undefined) => (item && item.revision > 0 ? 1 : 0)

export function NotesProvider({ children }: { children: ReactNode }) {
  const [list, setList] = useState<ListState>({
    items: [],
    status: 'loading',
    hasMore: false,
    total: 0,
    hidden: [],
    version: 0,
  })
  const { state: auth } = useAuth()
  const username = 'username' in auth ? auth.username : ''
  const [filter, setFilterState] = useState<ListFilter>(() => savedFilter(username))
  const [space, setSpaceState] = useState(DEFAULT_SPACE)
  const spaceRef = useRef(DEFAULT_SPACE)
  const [tagList, setTagList] = useState<TagCount[]>([])
  const [lockedCount, setLockedCount] = useState(0)
  const filterRef = useRef<ListFilter>(filter)
  /** Bumped per filter change: pages for an earlier filter are dropped. */
  const generation = useRef(0)
  const cursor = useRef('')
  const loadedAt = useRef(0)
  /** The generation being loaded, if any. */
  const loading = useRef<number | null>(null)
  const savers = useRef(new Map<string, NoteSaver>())
  const deletes = useRef(new Map<string, PendingDelete>())

  const fetchPage = useCallback(async (reset: boolean) => {
    const gen = generation.current
    if (loading.current === gen) return
    loading.current = gen
    try {
      const page = await api.list(filterRef.current, spaceRef.current, reset ? undefined : cursor.current)
      if (gen !== generation.current) return
      cursor.current = page.nextCursor
      loadedAt.current = Date.now()
      setList((prev) => {
        const seen = new Set(page.notes.map((n) => n.id))
        // Keep notes saved locally that the page doesn't include yet.
        const kept = reset ? prev.items.filter((n) => !seen.has(n.id) && savers.current.has(n.id)) : prev.items
        const items = reset ? [...kept, ...page.notes] : [...kept, ...page.notes.filter((n) => !kept.some((k) => k.id === n.id))]
        items.sort((a, b) => b.updatedAt - a.updatedAt)
        const hiddenOnServer = prev.hidden.reduce((sum, id) => sum + counted(items.find((n) => n.id === id)), 0)
        return { ...prev, items, status: 'ready', hasMore: page.hasMore, total: page.total - hiddenOnServer }
      })
    } catch {
      if (gen !== generation.current) return
      setList((prev) => (prev.status === 'loading' ? { ...prev, status: 'error' } : prev))
    } finally {
      if (loading.current === gen) loading.current = null
    }
  }, [])

  const setFilter = useCallback(
    (next: ListFilter) => {
      filterRef.current = next
      saveFilter(username, spaceRef.current, next)
      generation.current++
      cursor.current = ''
      setFilterState(next)
      setList((prev) => ({ ...prev, items: [], status: 'loading', hasMore: false, total: 0 }))
      void fetchPage(true)
    },
    [fetchPage, username],
  )

  useEffect(() => {
    void fetchPage(true)
  }, [fetchPage])

  /** Bumped per space change: filters fetched for an earlier space are dropped. */
  const filtersFor = useRef(0)
  const fetchFilters = useCallback((signal?: AbortSignal) => {
    const asked = filtersFor.current
    api.filters(spaceRef.current, signal).then(
      ({ tags: all, locked }) => {
        if (asked !== filtersFor.current) return
        setTagList(all)
        setLockedCount(locked)
      },
      () => undefined, // the list shows connection problems
    )
  }, [])

  const setSpace = useCallback(
    (next: string) => {
      if (next === spaceRef.current) return
      spaceRef.current = next
      setSpaceState(next)
      filtersFor.current++
      setTagList([])
      setLockedCount(0)
      setFilter(null)
      fetchFilters()
    },
    [setFilter, fetchFilters],
  )

  const hooks = useMemo(
    () => ({
      saved: (
        id: string,
        fields: Fields,
        r: { updatedAt: number; revision: number; status: string },
        locked: boolean,
        spaceId: string,
      ) =>
        setList((prev) => {
          const old = prev.items.find((n) => n.id === id)
          const hidden = prev.hidden.includes(id)
          const slice = filterRef.current
          if (spaceId !== spaceRef.current || !matches(slice, fields, locked)) {
            // No longer in this slice (a tag removed, moved to another space): it leaves the list.
            const items = prev.items.filter((n) => n.id !== id)
            return { ...prev, items, total: prev.total - (hidden ? 0 : counted(old)), version: prev.version + 1 }
          }
          // Typing since this save went out: the row already shows newer text.
          const live = old && savers.current.get(id)?.hasUnsentEdits()
          const item: ListItem = {
            id,
            title: live ? old.title : fields.title,
            preview: locked ? '' : live ? old.preview : previewOf(fields.content),
            locked,
            revision: r.revision,
            updatedAt: r.updatedAt,
            createdAt: old?.createdAt ?? r.updatedAt,
            spaceId,
          }
          // Filtered, a note can join the slice by gaining the tag, not just by being created.
          const joined = slice ? !counted(old) : r.status === 'created'
          return {
            ...prev,
            items: [item, ...prev.items.filter((n) => n.id !== id)],
            total: prev.total + (joined && !hidden ? 1 : 0),
            version: prev.version + 1,
          }
        }),
      copied: (copy: NoteSaver) => savers.current.set(copy.id, copy),
    }),
    [],
  )

  const saverFor = useCallback<NotesContextValue['saverFor']>(
    (init) => {
      let s = savers.current.get(init.id)
      if (!s) {
        s = new NoteSaver(init, hooks)
        savers.current.set(init.id, s)
      }
      return s
    },
    [hooks],
  )

  const existingSaver = useCallback((id: string) => savers.current.get(id), [])

  const newDraft = useCallback(() => {
    const f = filterRef.current
    const fields = { title: '', content: f && 'tag' in f ? tagText(f.name) : '' }
    const spaceId = spaceRef.current
    const s = new NoteSaver({ id: crypto.randomUUID(), fields, blank: fields, spaceId }, hooks)
    savers.current.set(s.id, s)
    const now = Date.now()
    const row: ListItem = { id: s.id, title: '', preview: '', locked: false, revision: 0, createdAt: now, updatedAt: now, spaceId }
    setList((prev) => ({ ...prev, items: [row, ...prev.items] }))
    return s
  }, [hooks])

  const removed = useCallback((id: string) => {
    savers.current.get(id)?.dispose()
    savers.current.delete(id)
    setList((prev) => {
      const item = prev.items.find((n) => n.id === id)
      const hidden = prev.hidden.includes(id)
      return {
        ...prev,
        items: prev.items.filter((n) => n.id !== id),
        hidden: prev.hidden.filter((h) => h !== id),
        total: prev.total - (hidden ? 0 : counted(item)),
        version: prev.version + 1,
      }
    })
  }, [])

  const release = useCallback(
    (id: string) => {
      const s = savers.current.get(id)
      if (!s || s.isAttached() || deletes.current.has(id)) return
      if (s.isBlankDraft()) return removed(id)
      // Anything still saving (or stuck) stays in memory.
      if (!s.hasUnsavedWork()) {
        s.dispose()
        savers.current.delete(id)
      }
    },
    [removed],
  )

  const move = useCallback<NotesContextValue['move']>((saver, spaceId) => {
    saver.moveTo(spaceId)
    if (spaceId === spaceRef.current) return
    setList((prev) => {
      const item = prev.items.find((n) => n.id === saver.id)
      const hidden = prev.hidden.includes(saver.id)
      return {
        ...prev,
        items: prev.items.filter((n) => n.id !== saver.id),
        total: prev.total - (hidden ? 0 : counted(item)),
        version: prev.version + 1,
      }
    })
  }, [])

  const touch = useCallback<NotesContextValue['touch']>((id, { title, preview }) => {
    setList((prev) => {
      const old = prev.items.find((n) => n.id === id)
      if (!old) return prev
      const item = { ...old, title, preview, updatedAt: Date.now() }
      return { ...prev, items: [item, ...prev.items.filter((n) => n.id !== id)] }
    })
  }, [])

  const setHidden = useCallback((id: string, hide: boolean) => {
    setList((prev) => {
      if (prev.hidden.includes(id) === hide) return prev
      const delta = counted(prev.items.find((n) => n.id === id))
      return {
        ...prev,
        hidden: hide ? [...prev.hidden, id] : prev.hidden.filter((h) => h !== id),
        total: prev.total + (hide ? -delta : delta),
      }
    })
  }, [])

  const commit = useCallback(
    (id: string, keepalive = false) => {
      const entry = deletes.current.get(id)
      if (!entry) return
      deletes.current.delete(id)
      clearTimeout(entry.timer)
      const saver = savers.current.get(id)
      if (!saver) {
        setHidden(id, false)
        entry.resolve('failed')
        return
      }
      saver.delete(keepalive).then(
        (outcome) => {
          if (outcome === 'deleted') removed(id)
          else setHidden(id, false)
          entry.resolve(outcome)
        },
        () => {
          setHidden(id, false)
          entry.resolve('failed')
        },
      )
    },
    [removed, setHidden],
  )

  const remove = useCallback<NotesContextValue['remove']>(
    (id) => {
      // One undo at a time: an earlier delete goes through now.
      for (const other of deletes.current.keys()) commit(other)
      setHidden(id, true)
      return new Promise((resolve) => {
        const timer = setTimeout(() => commit(id), timing.undoDeleteMs)
        deletes.current.set(id, { timer, resolve })
      })
    },
    [commit, setHidden],
  )

  const undoRemove = useCallback(
    (id: string) => {
      const entry = deletes.current.get(id)
      if (!entry) return
      deletes.current.delete(id)
      clearTimeout(entry.timer)
      setHidden(id, false)
      entry.resolve('undone')
    },
    [setHidden],
  )

  const { version: savedVersion } = list
  useEffect(() => {
    const controller = new AbortController()
    const timer = setTimeout(() => fetchFilters(controller.signal), savedVersion === 0 ? 0 : FILTERS_DEBOUNCE_MS)
    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [savedVersion, fetchFilters])

  // Locked notes (§10.1): the keyring is fetched up front, edits to locked
  // notes are saved before the key goes, unlocking sends what waited, and
  // signing out forgets the key.
  const lockStatus = useLockStatus()
  useEffect(() => {
    void lockSession.load().catch(() => undefined)
    const off = lockSession.onBeforeLock(async () => {
      await Promise.all([...savers.current.values()].filter((s) => s.isLocked()).map((s) => s.flush()))
    })
    return () => {
      off()
      lockSession.forget()
    }
  }, [])
  useEffect(() => {
    if (lockStatus === 'unlocked') for (const s of savers.current.values()) s.resume()
    if (lockStatus !== 'locked') return
    // Plain text of locked notes leaves memory once nothing needs it; open editors close themselves.
    for (const [id, s] of savers.current) {
      if (s.isLocked() && !s.isAttached() && !s.hasUnsavedWork()) {
        s.dispose()
        savers.current.delete(id)
      }
    }
  }, [lockStatus])

  // Signed in again after the session ended: send what was waiting.
  const authStatus = auth.status
  useEffect(() => {
    if (authStatus !== 'signed-in') return
    for (const s of savers.current.values()) s.resume()
  }, [authStatus])

  useEffect(() => {
    const all = () => [...savers.current.values()]
    const onFocus = () => {
      for (const s of all()) s.resume()
      if (Date.now() - loadedAt.current > STALE_MS) {
        void fetchPage(true)
        fetchFilters()
        void reloadSpaces()
      }
      // Every time, not only when the list is stale: a reset elsewhere must lock this
      // tab before anything new is locked with the forgotten key. It's one small request.
      void lockSession.load(true).catch(() => undefined)
    }
    const onOnline = () => {
      for (const s of all()) s.resume()
      void fetchPage(true)
      fetchFilters()
      void reloadSpaces()
      void lockSession.load(true).catch(() => undefined)
    }
    const onHidden = () => {
      if (document.visibilityState === 'hidden') for (const s of all()) void s.flush()
    }
    const onUnload = (e: BeforeUnloadEvent) => {
      for (const id of deletes.current.keys()) commit(id, true)
      const pending = all().filter((s) => s.hasUnsavedWork())
      for (const s of pending) s.flushOnUnload()
      if (pending.length > 0) e.preventDefault()
    }
    window.addEventListener('focus', onFocus)
    window.addEventListener('online', onOnline)
    document.addEventListener('visibilitychange', onHidden)
    window.addEventListener('beforeunload', onUnload)
    return () => {
      window.removeEventListener('focus', onFocus)
      window.removeEventListener('online', onOnline)
      document.removeEventListener('visibilitychange', onHidden)
      window.removeEventListener('beforeunload', onUnload)
    }
  }, [fetchPage, fetchFilters, commit])

  // Signing out unmounts the notes: deletes waiting on undo go through.
  useEffect(
    () => () => {
      for (const id of deletes.current.keys()) commit(id)
    },
    [commit],
  )

  const { hidden, items, status, hasMore, total, version } = list
  const visible = useMemo(() => (hidden.length ? items.filter((n) => !hidden.includes(n.id)) : items), [items, hidden])

  const value = useMemo<NotesContextValue>(
    () => ({
      items: visible,
      space,
      setSpace,
      tags: tagList,
      lockedCount,
      filter,
      setFilter,
      status,
      hasMore,
      total,
      version,
      loadMore: () => void fetchPage(false),
      refresh: () => {
        void fetchPage(true)
        fetchFilters()
      },
      saverFor,
      existingSaver,
      newDraft,
      release,
      removed,
      touch,
      move,
      remove,
      undoRemove,
    }),
    [visible, space, setSpace, tagList, lockedCount, filter, setFilter, status, hasMore, total, version, fetchPage, fetchFilters, saverFor, existingSaver, newDraft, release, removed, touch, move, remove, undoRemove],
  )
  return <NotesContext value={value}>{children}</NotesContext>
}

export function useNotes(): NotesContextValue {
  const ctx = use(NotesContext)
  if (!ctx) throw new Error('useNotes outside NotesProvider')
  return ctx
}
