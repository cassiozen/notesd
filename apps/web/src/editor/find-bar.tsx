import type { Editor } from '@tiptap/core'
import { useEditorState } from '@tiptap/react'
import { useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react'
import { ChevronIcon, CloseIcon } from '../ui/icons'
import { endSearch, findState, replaceAll, replaceCurrent, search, step } from './find'
import styles from './find.module.css'

const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)

/** Shown next to the menu items, the way the system menus show theirs. */
export const findKeys = {
  find: { label: isMac ? '⌘F' : 'Ctrl+F', aria: isMac ? 'Meta+F' : 'Control+F' },
  replace: { label: isMac ? '⌥⌘F' : 'Ctrl+H', aria: isMac ? 'Alt+Meta+F' : 'Control+H' },
}

export type FindCommand = 'find' | 'replace' | 'next' | 'prev'

/** The find shortcuts of the platform's own editors: ⌘F, ⌥⌘F, ⌘G, ⇧⌘G on a Mac; Ctrl+F, Ctrl+H, F3 elsewhere. */
export function findShortcut(e: KeyboardEvent): FindCommand | null {
  if (e.key === 'F3' && !e.metaKey && !e.ctrlKey && !e.altKey) return e.shiftKey ? 'prev' : 'next'
  const mod = isMac ? e.metaKey && !e.ctrlKey : e.ctrlKey && !e.metaKey
  if (!mod) return null
  // code, not key: ⌥ turns F into ƒ on a Mac.
  if (e.code === 'KeyF' && !e.shiftKey) return e.altKey ? (isMac ? 'replace' : null) : 'find'
  if (e.code === 'KeyH' && !isMac && !e.shiftKey && !e.altKey) return 'replace'
  if (e.code === 'KeyG' && !e.altKey) return e.shiftKey ? 'prev' : 'next'
  return null
}

/** Each open (menu or shortcut) is a new request, even with the bar already up: it refocuses the field. */
export type FindRequest = {
  /** The selected text, to search for instead of the last query. */
  seed: string | null
  /** Which field takes focus. */
  field: 'find' | 'replace'
}

/** What one line of selection reads as a search; anything else leaves the last query. */
export function seedFrom(editor: Editor): string | null {
  const { from, to, empty } = editor.state.selection
  if (empty) return null
  const text = editor.state.doc.textBetween(from, to, '\n')
  return text.trim() && !text.includes('\n') && text.length <= 200 ? text : null
}

// The last search, for the next note: find again without retyping, like a browser's find.
let lastQuery = ''
let lastReplacement = ''

/** Tests: each starts with no last search. */
export function forgetFind() {
  lastQuery = ''
  lastReplacement = ''
}

/** Find, and replace when asked, over the open note. Desktop only: phones have no keyboard shortcuts or room for it. */
export function FindBar({
  editor,
  request,
  replace,
  onReplaceChange,
  onClose,
}: {
  editor: Editor
  request: FindRequest
  replace: boolean
  onReplaceChange: (replace: boolean) => void
  onClose: () => void
}) {
  const [query, setQuery] = useState(() => request.seed ?? lastQuery)
  const [replacement, setReplacement] = useState(lastReplacement)
  const [replaced, setReplaced] = useState<number | null>(null)
  const findInput = useRef<HTMLInputElement>(null)
  const replaceInput = useRef<HTMLInputElement>(null)
  const replaceId = useId()
  const { total, current } = useEditorState({
    editor,
    selector: ({ editor: e }) => {
      const s = findState(e)
      return { total: s.total, current: s.current }
    },
  })

  // A new request (⌘F again, with the bar up) takes the new selection as the query.
  const [seen, setSeen] = useState(request)
  if (seen !== request) {
    setSeen(request)
    setQuery(request.seed ?? lastQuery)
    setReplaced(null)
  }

  useLayoutEffect(() => {
    // lastQuery is this bar's query too: typing keeps it current.
    const q = request.seed ?? lastQuery
    lastQuery = q
    if (q) search(editor, q)
    const input = request.field === 'replace' && q ? replaceInput.current : findInput.current
    input?.focus()
    input?.select()
  }, [editor, request])

  useEffect(() => () => endSearch(editor), [editor])

  const close = () => {
    endSearch(editor)
    // Back in the note with the match selected: type over it, or carry on from there.
    editor.commands.focus()
    onClose()
  }

  const onKeyDown = (e: ReactKeyboardEvent) => {
    if (e.key === 'Escape') {
      e.preventDefault()
      e.stopPropagation()
      close()
      return
    }
    if (e.key !== 'Enter' || e.nativeEvent.isComposing) return
    e.preventDefault()
    if (e.target === replaceInput.current) {
      if (isMac ? e.metaKey : e.ctrlKey) doReplaceAll()
      else doReplace()
    } else {
      step(editor, e.shiftKey ? -1 : 1)
    }
  }

  // Counted so the bar can say "Replaced 5" once none are left, rather than a bare "No results".
  const doReplace = () => {
    if (total === 0) return
    replaceCurrent(editor, replacement)
    setReplaced((n) => (n ?? 0) + 1)
    keepFocus()
  }
  const doReplaceAll = () => {
    const n = replaceAll(editor, replacement)
    if (n > 0) setReplaced((m) => (m ?? 0) + n)
    keepFocus()
  }
  // The button just pressed is disabled once nothing's left to replace, and would drop focus
  // out of the bar (and Escape with it).
  const keepFocus = () => {
    if (findState(editor).total === 0) replaceInput.current?.focus()
  }

  const status = !query ? '' : total > 0 ? `${current + 1} of ${total}` : replaced ? `Replaced ${replaced}` : 'No results'

  return (
    <div className={styles.bar} role="search" aria-label="Find in note" onKeyDown={onKeyDown}>
      <div className={styles.row}>
        <button
          type="button"
          className={[styles.icon, styles.toggle].join(' ')}
          aria-label="Find and replace"
          title={replace ? 'Hide replace' : 'Show replace'}
          aria-expanded={replace}
          aria-controls={replaceId}
          onClick={() => {
            onReplaceChange(!replace)
            // The field it opened, or back to the search.
            requestAnimationFrame(() => (replace ? findInput.current : replaceInput.current)?.focus())
          }}
        >
          <ChevronIcon size={11} />
        </button>
        <div className={styles.field}>
          <input
            ref={findInput}
            className={styles.input}
            type="text"
            aria-label="Find"
            placeholder="Find"
            spellCheck={false}
            autoComplete="off"
            enterKeyHint="search"
            value={query}
            onChange={(e) => {
              const q = e.target.value
              setQuery(q)
              setReplaced(null)
              lastQuery = q
              search(editor, q)
            }}
          />
          <span
            role="status"
            className={[styles.count, query && total === 0 && !replaced && styles.none].filter(Boolean).join(' ')}
          >
            {status}
          </span>
        </div>
        <button
          type="button"
          className={styles.icon}
          aria-label="Previous match"
          title={`Previous match (${isMac ? '⇧⌘G' : 'Shift+F3'})`}
          disabled={total === 0}
          onClick={() => step(editor, -1)}
        >
          <span className={styles.up}>
            <ChevronIcon size={12} />
          </span>
        </button>
        <button
          type="button"
          className={styles.icon}
          aria-label="Next match"
          title={`Next match (${isMac ? '⌘G' : 'F3'})`}
          disabled={total === 0}
          onClick={() => step(editor, 1)}
        >
          <span className={styles.down}>
            <ChevronIcon size={12} />
          </span>
        </button>
        <button type="button" className={styles.icon} aria-label="Close find" title="Close (Esc)" onClick={close}>
          <CloseIcon size={13} />
        </button>
      </div>
      {replace && (
        <div id={replaceId} className={[styles.row, styles.replaceRow].join(' ')}>
          <div className={styles.field}>
            <input
              ref={replaceInput}
              className={styles.input}
              type="text"
              aria-label="Replace with"
              placeholder="Replace with"
              spellCheck={false}
              autoComplete="off"
              value={replacement}
              onChange={(e) => {
                setReplacement(e.target.value)
                lastReplacement = e.target.value
              }}
            />
          </div>
          <button
            type="button"
            className={styles.action}
            title="Replace this match and go to the next (Enter)"
            disabled={total === 0}
            onClick={doReplace}
          >
            Replace
          </button>
          <button
            type="button"
            className={styles.action}
            title={`Replace all matches (${isMac ? '⌘' : 'Ctrl+'}Enter)`}
            disabled={total === 0}
            onClick={doReplaceAll}
          >
            Replace all
          </button>
        </div>
      )}
    </div>
  )
}
