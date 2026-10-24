import type { Editor } from '@tiptap/core'
import type { Slice } from '@tiptap/pm/model'
import { EditorContent, useEditor, useEditorState } from '@tiptap/react'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import type { TagCount } from '../api/types'
import type { Fields } from '../notes/saver'
import { ImageView } from '../images/view'
import { ChecklistIcon, ImageIcon, IndentIcon, LinkIcon, OutdentIcon } from '../ui/icons'
import { editorExtensions, isAllowedLink } from './extensions'
import { noteContent, pastedSlice } from './note-doc'
import { Tags } from './tags'
import styles from './editor.module.css'

type Options = {
  initial: Fields
  /** The document changed (typing, formatting). Not called for replaceNote(). */
  onChange: (editor: Editor) => void
  /** ⌘K. */
  onLinkShortcut: () => void
  /** Existing tags, suggested while typing one. */
  tags: TagCount[]
  /** ⌘/Ctrl-click on a tag. */
  onOpenTag: (name: string) => void
  /** Pictures pasted or dropped in, at a document position. */
  onImages: (files: File[], pos: number) => void
}

/**
 * HTML with no formatting in it: what code editors and terminals put next to
 * the text they copy (coloured spans). Their text is the thing to paste.
 */
const unformatted = (html: string) => !/<(h[1-6]|ul|ol|li|blockquote|b|strong|i|em|s|del|a|table|img|pre|code)\b/i.test(html)

/** Markdown syntax that the dialect turns into formatting. */
const markdownish = /^\s{0,3}(#{1,6}\s|[-*+]\s|\d+[.)]\s|>)|\*\*\S|~~\S|\]\(/m

const imageFiles = (list: FileList | null | undefined) => Array.from(list ?? []).filter((f) => f.type.startsWith('image/'))

/** One editor for the whole note: the first line is the title, the rest the body. */
export function useNoteEditor(options: Options): Editor {
  const { initial } = options
  // Latest callbacks for the editor's handlers, which are bound once.
  const handlers = useRef(options)
  useEffect(() => {
    handlers.current = options
  })

  const editor = useEditor({
    extensions: [...editorExtensions.map((e) => (e.name === 'image' ? ImageView : e)), Tags],
    content: noteContent(initial),
    // Toolbars subscribe to what they need (useEditorState).
    shouldRerenderOnTransaction: false,
    editorProps: {
      attributes: {
        'aria-label': 'Note',
        'aria-multiline': 'true',
        role: 'textbox',
        class: styles.prose ?? '',
      },
      handleKeyDown: (_view, event) => {
        if ((event.metaKey || event.ctrlKey) && !event.shiftKey && event.key.toLowerCase() === 'k') {
          event.preventDefault()
          handlers.current.onLinkShortcut()
          return true
        }
        return false
      },
      // Plain text pastes as Markdown; Shift (paste as plain text) keeps it as typed.
      clipboardTextParser: (text, $context, plain, view) =>
        plain ? (null as unknown as Slice) : pastedSlice(text, view.state.schema, $context.parent.type.name === 'title'),
      handlePaste: (view, event) => {
        const html = event.clipboardData?.getData('text/html')
        const text = event.clipboardData?.getData('text/plain')
        if (html && text && unformatted(html) && markdownish.test(text)) {
          event.preventDefault()
          const slice = pastedSlice(text, view.state.schema, view.state.selection.$from.parent.type.name === 'title')
          view.dispatch(view.state.tr.replaceSelection(slice).scrollIntoView().setMeta('paste', true).setMeta('uiEvent', 'paste'))
          return true
        }
        const files = imageFiles(event.clipboardData?.files)
        // Text with pictures (a document, a file copied in the file manager)
        // pastes as text: the file there is often just a rendering of it.
        if (files.length === 0 || event.clipboardData?.getData('text/plain').trim()) return false
        event.preventDefault()
        handlers.current.onImages(files, view.state.selection.from)
        return true
      },
      handleDrop: (view, event, _slice, moved) => {
        const files = moved ? [] : imageFiles(event.dataTransfer?.files)
        if (files.length === 0) return false
        event.preventDefault()
        const at = view.posAtCoords({ left: event.clientX, top: event.clientY })?.pos ?? view.state.selection.from
        handlers.current.onImages(files, at)
        return true
      },
    },
    onUpdate: ({ editor: e, transaction }) => {
      if (transaction.docChanged) handlers.current.onChange(e)
    },
  })

  const { tags, onOpenTag } = options
  useEffect(() => {
    editor.storage.tags.set({ tags, onOpen: onOpenTag })
  }, [editor, tags, onOpenTag])

  return editor
}

/** Replaces the whole note, e.g. with the server's version after a conflict. */
export function replaceNote(editor: Editor, fields: Fields) {
  editor.commands.setContent(noteContent(fields), { emitUpdate: false })
}

export function NoteContent({ editor }: { editor: Editor }) {
  return <EditorContent editor={editor} className={styles.content} />
}

const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)
const mod = isMac ? '⌘' : 'Ctrl+'
const shift = isMac ? '⇧' : 'Shift+'

function useFormat(editor: Editor) {
  return useEditorState({
    editor,
    selector: ({ editor: e }) => {
      const inTitle = e.state.selection.$from.parent.type.name === 'title'
      const h1 = e.isActive('heading', { level: 1 })
      const h2 = e.isActive('heading', { level: 2 })
      const h3 = e.isActive('heading', { level: 3 })
      const bullets = e.isActive('bulletList')
      const numbers = e.isActive('orderedList')
      const tasks = e.isActive('taskList')
      const quote = e.isActive('blockquote')
      const item = e.isActive('taskItem') ? 'taskItem' : 'listItem'
      const inList = e.isActive(item)
      return {
        bold: e.isActive('bold'),
        italic: e.isActive('italic'),
        strike: e.isActive('strike'),
        link: e.isActive('link'),
        title: inTitle || h1,
        h2,
        h3,
        body: !inTitle && !h1 && !h2 && !h3 && !bullets && !numbers && !tasks && !quote,
        bullets,
        numbers,
        tasks,
        quote,
        listItem: inList ? item : null,
        canIndent: inList && e.can().sinkListItem(item),
      }
    },
  })
}

type ToolProps = {
  label: string
  keys?: string
  /** Whether a toggle is on; left out for a button that only does something (no pressed state). */
  on?: boolean
  className?: string | undefined
  run: () => void
  children: ReactNode
  /** For a button that opens something (the text styles menu). */
  expanded?: boolean
  controls?: string
}

export function Tool({ label, keys, on, className, run, children, expanded, controls }: ToolProps) {
  return (
    <button
      type="button"
      className={[styles.tool, className].filter(Boolean).join(' ')}
      aria-label={label}
      {...(expanded !== undefined
        ? { 'aria-expanded': expanded, 'aria-controls': controls }
        : on !== undefined && { 'aria-pressed': on })}
      title={keys ? `${label} (${keys})` : label}
      // mousedown, not click: keep the editor's selection and focus.
      onMouseDown={(e) => {
        if (e.button !== 0) return
        e.preventDefault()
        run()
      }}
      // Keyboard activation (Enter/Space) arrives as click without mousedown.
      onClick={(e) => {
        if (e.detail === 0) run()
      }}
    >
      {children}
    </button>
  )
}

/** Bold, italic, strikethrough. */
export function MarkTools({ editor, className }: { editor: Editor; className?: string | undefined }) {
  const f = useFormat(editor)
  const chain = () => editor.chain().focus()
  return (
    <>
      <Tool label="Bold" keys={`${mod}B`} on={f.bold} className={className} run={() => chain().toggleBold().run()}>
        <b className={styles.boldGlyph}>B</b>
      </Tool>
      <Tool label="Italic" keys={`${mod}I`} on={f.italic} className={className} run={() => chain().toggleItalic().run()}>
        <span className={styles.italicGlyph}>I</span>
      </Tool>
      <Tool
        label="Strikethrough"
        keys={`${mod}${shift}S`}
        on={f.strike}
        className={className}
        run={() => chain().toggleStrike().run()}
      >
        <s>S</s>
      </Tool>
    </>
  )
}

type ToolbarProps = { editor: Editor; className?: string; iconSize?: number }

export function ChecklistTool({ editor, className, iconSize = 17 }: ToolbarProps) {
  const f = useFormat(editor)
  return (
    <Tool
      label="Checklist"
      keys={`${shift}${mod}L`}
      on={f.tasks}
      className={className}
      run={() => editor.chain().focus().toggleTaskList().run()}
    >
      <ChecklistIcon size={iconSize} />
    </Tool>
  )
}

export function LinkTool({ editor, open, onToggle, className, iconSize = 16 }: ToolbarProps & { open: boolean; onToggle: () => void }) {
  const f = useFormat(editor)
  return (
    <Tool label="Link" keys={`${mod}K`} on={f.link || open} className={className} run={onToggle}>
      <LinkIcon size={iconSize} />
    </Tool>
  )
}

/** Add a picture: opens the file picker, then hands the files over with the cursor's position. */
export function ImageTool({ editor, onPick, className, iconSize = 16 }: ToolbarProps & { onPick: (files: File[], pos: number) => void }) {
  const input = useRef<HTMLInputElement>(null)
  return (
    <>
      <Tool label="Add picture" className={className} run={() => input.current?.click()}>
        <ImageIcon size={iconSize} />
      </Tool>
      <input
        ref={input}
        type="file"
        accept="image/*"
        multiple
        hidden
        data-testid="image-input"
        onChange={(e) => {
          const files = imageFiles(e.currentTarget.files)
          e.currentTarget.value = ''
          if (files.length > 0) onPick(files, editor.state.selection.from)
        }}
      />
    </>
  )
}

/** Indent and outdent, only while the cursor is in a list. */
export function IndentTools({ editor, className, iconSize = 16 }: ToolbarProps) {
  const f = useFormat(editor)
  const item = f.listItem
  if (!item) return null
  return (
    <>
      <Tool label="Outdent" keys={`${shift}Tab`} on={false} className={className} run={() => editor.chain().focus().liftListItem(item).run()}>
        <OutdentIcon size={iconSize} />
      </Tool>
      {f.canIndent && (
        <Tool label="Indent" keys="Tab" on={false} className={className} run={() => editor.chain().focus().sinkListItem(item).run()}>
          <IndentIcon size={iconSize} />
        </Tool>
      )}
    </>
  )
}

type StyleKey = 'title' | 'h2' | 'h3' | 'body' | 'quote' | 'bullets' | 'numbers'

const blockStyles: { key: StyleKey; label: string; glyph?: string; apply: (e: Editor) => void }[] = [
  { key: 'title', label: 'Title', apply: (e) => e.chain().focus().toggleHeading({ level: 1 }).run() },
  { key: 'h2', label: 'Heading', apply: (e) => e.chain().focus().toggleHeading({ level: 2 }).run() },
  { key: 'h3', label: 'Subheading', apply: (e) => e.chain().focus().toggleHeading({ level: 3 }).run() },
  { key: 'body', label: 'Body', apply: (e) => e.chain().focus().clearNodes().run() },
  { key: 'quote', label: 'Quote', apply: (e) => e.chain().focus().toggleBlockquote().run() },
  { key: 'bullets', label: 'Bulleted list', glyph: '•', apply: (e) => e.chain().focus().toggleBulletList().run() },
  { key: 'numbers', label: 'Numbered list', glyph: '1.', apply: (e) => e.chain().focus().toggleOrderedList().run() },
]

/** The "Aa" menu: marks, then one paragraph style per row with a check on the current one. */
export function TextStyles({ editor, id, onApplied }: { editor: Editor; id: string; onApplied: () => void }) {
  const f = useFormat(editor)
  return (
    <div
      id={id}
      role="group"
      aria-label="Text styles"
      className={styles.styles}
      onMouseDown={(e) => e.preventDefault()}
    >
      <div className={styles.marks}>
        <MarkTools editor={editor} className={styles.markTool} />
      </div>
      {blockStyles.map((s) => (
        <Tool
          key={s.key}
          label={s.label}
          on={f[s.key]}
          className={[styles.styleRow, styles[s.key]].join(' ')}
          run={() => {
            s.apply(editor)
            onApplied()
          }}
        >
          <span className={styles.check} aria-hidden="true">
            {f[s.key] ? '✓' : ''}
          </span>
          {s.glyph && (
            <span className={styles.glyph} aria-hidden="true">
              {s.glyph}
            </span>
          )}
          <span aria-hidden="true">{s.label}</span>
        </Tool>
      ))}
    </div>
  )
}

export function LinkForm({ editor, onDone }: { editor: Editor; onDone: () => void }) {
  const current = (editor.getAttributes('link').href as string | undefined) ?? ''
  const [url, setUrl] = useState(current)
  const [error, setError] = useState<string>()
  // The selection to link, captured before focus moves into the URL field.
  const selection = useRef({ from: editor.state.selection.from, to: editor.state.selection.to })

  const cancel = () => {
    editor.commands.focus()
    onDone()
  }

  const apply = () => {
    const raw = url.trim()
    const range = selection.current
    if (!raw) {
      editor.chain().focus().setTextSelection(range).extendMarkRange('link').unsetLink().run()
      onDone()
      return
    }
    const href = /^[a-z][a-z0-9+.-]*:/i.test(raw) ? raw : raw.includes('@') && !raw.includes('/') ? `mailto:${raw}` : `https://${raw}`
    if (!isAllowedLink(href)) {
      setError('Use a web (https://) or email (mailto:) link.')
      return
    }
    const chain = editor.chain().focus().setTextSelection(range)
    if (range.from === range.to && !editor.isActive('link')) {
      const text = href.replace(/^(https?:\/\/|mailto:)/, '')
      chain.insertContent({ type: 'text', text, marks: [{ type: 'link', attrs: { href } }] }).run()
    } else {
      chain.extendMarkRange('link').setLink({ href }).run()
    }
    onDone()
  }

  return (
    <form
      className={styles.linkForm}
      onSubmit={(e) => {
        e.preventDefault()
        apply()
      }}
    >
      <label htmlFor="link-url" className="visually-hidden">
        Link address
      </label>
      <input
        id="link-url"
        className={styles.linkInput}
        value={url}
        placeholder="Paste or type a link"
        spellCheck={false}
        autoComplete="off"
        // oxlint-disable-next-line jsx-a11y/no-autofocus -- the field opens on request (button or ⌘K)
        autoFocus
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? 'link-error' : undefined}
        onChange={(e) => {
          setUrl(e.target.value)
          setError(undefined)
        }}
        onKeyDown={(e) => {
          if (e.key === 'Escape') {
            e.preventDefault()
            e.stopPropagation()
            cancel()
          }
        }}
      />
      <button type="button" className={styles.linkCancel} onClick={cancel}>
        Cancel
      </button>
      <button type="submit" className={styles.linkApply}>
        {current ? 'Update link' : 'Add link'}
      </button>
      {error && (
        <span id="link-error" role="alert" className={styles.linkError}>
          {error}
        </span>
      )}
    </form>
  )
}
