import { Extension, type Editor } from '@tiptap/core'
import { closeHistory } from '@tiptap/pm/history'
import type { Node as PMNode } from '@tiptap/pm/model'
import { Plugin, PluginKey, TextSelection, type Transaction } from '@tiptap/pm/state'
import { Decoration, DecorationSet } from '@tiptap/pm/view'
import { OBJECT } from '../notes/plaintext'

export type Match = { from: number; to: number }

type FindState = {
  query: string
  matches: Match[]
  /** Index into matches; -1 with none. */
  current: number
  /** Where "the first match" is counted from: the cursor when the search started. */
  anchor: number
  decorations: DecorationSet
}

const key = new PluginKey<FindState>('find')

const closed: FindState = { query: '', matches: [], current: -1, anchor: 0, decorations: DecorationSet.empty }

/**
 * Lowercase, one character for one, so offsets in the folded text are offsets
 * in the document. İ is the one letter whose lowercase is longer (i + a
 * combining dot): it folds to i, which is what people type. ς and σ are one
 * letter.
 */
function fold(s: string): string {
  let out = ''
  for (const c of s) out += c === 'İ' ? 'i' : c === 'ς' ? 'σ' : c.toLowerCase()
  return out
}

/**
 * Case-insensitive plain-text matches, each inside one textblock. Inline atoms
 * (pictures, raw code) count as one unmatchable character per position, so a
 * match never spans one and text offsets map straight back to positions.
 */
export function findMatches(doc: PMNode, query: string): Match[] {
  const needle = fold(query)
  if (!needle) return []
  const out: Match[] = []
  doc.descendants((node, pos) => {
    if (!node.isTextblock) return true
    let text = ''
    node.forEach((child) => {
      text += child.isText ? (child.text ?? '') : child.type.name === 'hardBreak' ? '\n' : OBJECT.repeat(child.nodeSize)
    })
    const hay = fold(text)
    for (let i = hay.indexOf(needle); i !== -1; i = hay.indexOf(needle, i + needle.length)) {
      out.push({ from: pos + 1 + i, to: pos + 1 + i + needle.length })
    }
    return false
  })
  return out
}

function decorate(doc: PMNode, matches: Match[], current: number): DecorationSet {
  return DecorationSet.create(
    doc,
    matches.map((m, i) => Decoration.inline(m.from, m.to, { 'data-find': i === current ? 'current' : '' })),
  )
}

function build(doc: PMNode, query: string, anchor: number, current?: number): FindState {
  const matches = findMatches(doc, query)
  const at = current ?? firstFrom(matches, anchor)
  return { query, matches, current: at, anchor, decorations: decorate(doc, matches, at) }
}

/** The first match at or after pos, wrapping to the top. */
function firstFrom(matches: Match[], pos: number): number {
  if (matches.length === 0) return -1
  const i = matches.findIndex((m) => m.from >= pos)
  return i === -1 ? 0 : i
}

type Meta = { state: FindState }

/** Highlights for find and replace; FindBar drives it. Nothing here reaches the saved note. */
export const Find = Extension.create({
  name: 'find',
  addProseMirrorPlugins() {
    return [
      new Plugin<FindState>({
        key,
        state: {
          init: () => closed,
          apply(tr, prev) {
            const meta = tr.getMeta(key) as Meta | undefined
            if (meta) return meta.state
            if (!tr.docChanged || !prev.query) return prev
            // Typing (or a remote change) while the bar is open: stay on the same match, or the next one.
            const was = prev.matches[prev.current]
            const anchor = tr.mapping.map(prev.anchor)
            const matches = findMatches(tr.doc, prev.query)
            const current = was ? firstFrom(matches, tr.mapping.map(was.from)) : firstFrom(matches, anchor)
            return { query: prev.query, matches, current, anchor, decorations: decorate(tr.doc, matches, current) }
          },
        },
        props: {
          decorations: (state) => key.getState(state)?.decorations,
        },
      }),
    ]
  },
})

export function findState(editor: Editor): { query: string; total: number; current: number } {
  const s = key.getState(editor.state) ?? closed
  return { query: s.query, total: s.matches.length, current: s.current }
}

/** Selects the current match (or puts the cursor back where the search started) and shows it. */
function commit(editor: Editor, tr: Transaction, next: FindState) {
  const m = next.matches[next.current]
  const sel = m ? TextSelection.create(tr.doc, m.from, m.to) : TextSelection.create(tr.doc, Math.min(next.anchor, tr.doc.content.size))
  editor.view.dispatch(tr.setMeta(key, { state: next } satisfies Meta).setSelection(sel))
  if (m) reveal(editor)
}

/**
 * Brings the current match into view, centred, unless it's already clear of
 * the find bar on top and the formatting dock at the bottom; jumping a
 * visible match would lose the reader's place.
 */
function reveal(editor: Editor) {
  const el = editor.view.dom.querySelector<HTMLElement>('[data-find="current"]')
  const scroller = el?.closest<HTMLElement>('[data-note-scroller]')
  if (!el || !scroller) return
  const box = el.getBoundingClientRect()
  const view = scroller.getBoundingClientRect()
  if (box.top >= view.top + 72 && box.bottom <= view.bottom - 96) return
  el.scrollIntoView?.({ block: 'center' })
}

/** Starts or refines a search. Refining keeps the current match when it still matches. */
export function search(editor: Editor, query: string) {
  const prev = key.getState(editor.state) ?? closed
  const at = prev.matches[prev.current]
  const anchor = prev.query && at ? at.from : prev.query ? prev.anchor : editor.state.selection.from
  commit(editor, editor.state.tr, build(editor.state.doc, query, anchor))
}

export function step(editor: Editor, by: 1 | -1) {
  const s = key.getState(editor.state) ?? closed
  const n = s.matches.length
  if (n === 0) return
  const current = (s.current + by + n) % n
  commit(editor, editor.state.tr, { ...s, current, decorations: decorate(editor.state.doc, s.matches, current) })
}

/** Removes the highlights; the editor keeps the last match selected. */
export function endSearch(editor: Editor) {
  if (editor.isDestroyed || !key.getState(editor.state)?.query) return
  editor.view.dispatch(editor.state.tr.setMeta(key, { state: closed } satisfies Meta))
}

function replaceRange(tr: Transaction, m: Match, text: string) {
  // insertText keeps the formatting at the start of the match.
  if (text) tr.insertText(text, m.from, m.to)
  else tr.delete(m.from, m.to)
}

/** Replaces the current match, then moves to the next one. Each replace is its own undo step. */
export function replaceCurrent(editor: Editor, text: string) {
  const s = key.getState(editor.state) ?? closed
  const m = s.matches[s.current]
  if (!m) return
  const tr = closeHistory(editor.state.tr)
  replaceRange(tr, m, text)
  // From after the new text: replacing "a" with "aa" mustn't match its own result.
  const after = tr.mapping.map(m.to)
  const anchor = tr.mapping.map(s.anchor)
  const matches = findMatches(tr.doc, s.query)
  commit(editor, tr, build(tr.doc, s.query, anchor, firstFrom(matches, after)))
}

/** Replaces every match in one undo step; returns how many. */
export function replaceAll(editor: Editor, text: string): number {
  const s = key.getState(editor.state) ?? closed
  if (s.matches.length === 0) return 0
  const tr = closeHistory(editor.state.tr)
  for (const m of s.matches.toReversed()) replaceRange(tr, m, text)
  const anchor = tr.mapping.map(s.anchor)
  const next = build(tr.doc, s.query, anchor)
  editor.view.dispatch(tr.setMeta(key, { state: next } satisfies Meta))
  return s.matches.length
}
