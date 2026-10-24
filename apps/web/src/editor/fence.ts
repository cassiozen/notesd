import type { HLJSApi } from 'highlight.js'
import type { Node as PMNode } from '@tiptap/pm/model'
import { Selection } from '@tiptap/pm/state'
import type { EditorView } from '@tiptap/pm/view'

/**
 * A raw block that is a fenced code block, split for display: the language
 * from the info string and the code without its fence lines (spec §7.1).
 */
export function parseFence(source: string): { lang: string; code: string } | null {
  const lines = source.split('\n')
  const open = /^( {0,3})(`{3,}|~{3,})(.*)$/.exec(lines[0] ?? '')
  if (!open) return null
  const [, indent = '', marker = '', info = ''] = open
  // A backtick fence's info string can't hold a backtick (CommonMark §4.5).
  if (marker[0] === '`' && info.includes('`')) return null
  const body = lines.slice(1)
  const close = new RegExp(`^ {0,3}${marker[0] === '`' ? '`' : '~'}{${marker.length},}\\s*$`)
  if (body.length > 0 && close.test(body[body.length - 1] ?? '')) body.pop()
  // Content lines lose up to as many leading spaces as the opening fence had.
  const strip = new RegExp(`^ {0,${indent.length}}`)
  return {
    lang: info.trim().split(/\s+/)[0] ?? '',
    code: body.map((l) => l.replace(strip, '')).join('\n'),
  }
}

/** The Markdown for a code fence, with a fence longer than any backtick run at a line start in the code. */
export function buildFence(lang: string, code: string): string {
  const longest = Math.max(0, ...(code.match(/^ {0,3}`+/gm) ?? []).map((run) => run.trim().length))
  const marker = '`'.repeat(Math.max(3, longest + 1))
  return `${marker}${lang.replace(/[\s`]/g, '')}\n${code ? `${code}\n` : ''}${marker}`
}

/** What a paragraph holding only an opening fence (```` ``` ```` or ```` ```go ````) asks for, if it is one. */
export function fenceOpener(text: string): { lang: string } | null {
  const m = /^(?:`{3,}|~{3,})\s*([^\s`]*)\s*$/.exec(text)
  return m ? { lang: m[1] ?? '' } : null
}

// ponytail: highlight.js's common bundle (~40 languages) loads on first use,
// so notes without code never download it. Unknown languages stay plain.
let hljs: Promise<HLJSApi> | undefined

function loadHljs(): Promise<HLJSApi> {
  hljs ??= import('highlight.js/lib/common').then(
    (m) => m.default,
    (error: unknown) => {
      // Offline before the chunk was cached: retry next time.
      hljs = undefined
      throw error
    },
  )
  return hljs
}

export function highlight(code: HTMLElement, lang: string): void {
  if (!lang) return
  loadHljs().then(
    (h) => {
      if (!h.getLanguage(lang)) return
      // highlight() escapes the code; its output is spans around escaped text.
      code.innerHTML = h.highlight(code.textContent ?? '', { language: lang, ignoreIllegals: true }).value
    },
    () => {}, // plain text stays
  )
}

const languagesId = 'code-languages'

/** The id of a <datalist> of the languages highlight.js knows: suggestions, not a limit. */
function languageList(): string {
  if (document.getElementById(languagesId)) return languagesId
  const list = document.body.appendChild(document.createElement('datalist'))
  list.id = languagesId
  loadHljs().then(
    (h) => {
      for (const name of h.listLanguages().toSorted()) list.appendChild(document.createElement('option')).value = name
    },
    () => list.remove(), // offline: try again next time a box opens
  )
  return languagesId
}

const editors = new WeakMap<Element, () => void>()

/** Opens the code box of the code fence the node view at `pos` shows. */
export function editFence(view: EditorView, pos: number): void {
  const dom = view.nodeDOM(pos)
  if (dom instanceof Element) editors.get(dom)?.()
}

/**
 * The raw block's node view. A code fence shows its code, highlighted; on the
 * web it opens a code box on click, which writes the whole fence back. Every
 * other raw block (tables, rules…) is shown as written and stays read-only.
 */
export function rawBlockView(initial: PMNode, view: EditorView, getPos: () => number | undefined) {
  let node = initial
  let editing = false
  const dom = document.createElement('pre')
  dom.dataset.raw = ''
  dom.setAttribute('contenteditable', 'false')

  function show() {
    editing = false
    dom.replaceChildren()
    delete dom.dataset.editing
    const source = node.attrs.source as string
    const fence = parseFence(source)
    if (!fence) {
      delete dom.dataset.fence
      dom.title = 'Not editable here; kept exactly as written'
      dom.textContent = source
      return
    }
    dom.dataset.fence = ''
    dom.title = view.editable ? 'Click to edit' : ''
    if (fence.lang) {
      const label = dom.appendChild(document.createElement('span'))
      label.dataset.lang = ''
      label.textContent = fence.lang
    }
    const code = dom.appendChild(document.createElement('code'))
    code.textContent = fence.code
    highlight(code, fence.lang)
  }

  function edit() {
    const parsed = parseFence(node.attrs.source as string)
    if (!parsed || editing || !view.editable) return
    const original = parsed
    editing = true
    dom.replaceChildren()
    dom.dataset.editing = ''
    dom.title = ''
    const lang = dom.appendChild(document.createElement('input'))
    lang.dataset.lang = ''
    lang.placeholder = 'Language'
    lang.setAttribute('aria-label', 'Language')
    lang.spellcheck = false
    lang.setAttribute('list', languageList())
    lang.value = original.lang
    const code = dom.appendChild(document.createElement('textarea'))
    code.setAttribute('aria-label', 'Code')
    code.spellcheck = false
    code.value = original.code
    const fit = () => {
      code.style.height = 'auto'
      code.style.height = `${code.scrollHeight}px`
    }
    code.addEventListener('input', fit)
    code.addEventListener('keydown', (e) => {
      if (e.key !== 'Tab' || e.shiftKey) return
      e.preventDefault()
      code.setRangeText('\t', code.selectionStart, code.selectionEnd, 'end')
      fit()
    })
    dom.addEventListener('keydown', onKey)
    dom.addEventListener('focusout', onFocusOut)
    code.focus()
    fit()

    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape' || (e.key === 'Enter' && (e.metaKey || e.ctrlKey))) {
        e.preventDefault()
        commit(true)
      }
    }
    function onFocusOut(e: FocusEvent) {
      if (!dom.contains(e.relatedTarget as Node | null)) commit(false)
    }
    function commit(refocus: boolean) {
      dom.removeEventListener('keydown', onKey)
      dom.removeEventListener('focusout', onFocusOut)
      const pos = getPos()
      const newLang = lang.value.trim()
      // Unchanged: keep the source as written, fence style and all.
      const changed = newLang !== original.lang || code.value !== original.code
      show()
      if (pos === undefined) return
      const tr = view.state.tr
      // No code left: the block goes, leaving an empty line where it was, so a
      // note or quote it was alone in stays valid and the cursor has a home.
      const empty = !code.value
      const paragraph = view.state.schema.nodes.paragraph
      if (empty && paragraph) tr.replaceWith(pos, pos + node.nodeSize, paragraph.create())
      else if (changed) tr.setNodeMarkup(pos, undefined, { source: buildFence(newLang, code.value) })
      if (refocus) {
        // The cursor goes into that empty line, or after the block.
        const at = empty ? pos + 1 : pos + (tr.doc.nodeAt(pos)?.nodeSize ?? 0)
        tr.setSelection(Selection.near(tr.doc.resolve(at)))
      }
      view.dispatch(tr)
      if (refocus) view.focus()
    }
  }

  dom.addEventListener('click', edit)
  editors.set(dom, edit)
  show()

  return {
    dom,
    update(next: PMNode) {
      if (next.type !== node.type) return false
      const same = next.attrs.source === node.attrs.source
      node = next
      if (!same && !editing) show()
      return true
    },
    // The code box handles its own keys and clicks; ProseMirror stays out.
    stopEvent: (e: Event) => editing && dom.contains(e.target as Node),
    ignoreMutation: () => true,
    destroy() {
      editors.delete(dom)
    },
  }
}
