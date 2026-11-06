import type { Editor } from '@tiptap/core'
import { closeHistory } from '@tiptap/pm/history'
import type { EditorState } from '@tiptap/pm/state'
import type { Fields } from '../notes/saver'
import { noteContent, noteFields } from './note-doc'

// A private-use character, which Markdown writes as itself: where it lands
// in the saved text is where the cursor is.
const MARK = ''

/**
 * The note as an agent reads it: the text it would save, and the cursor as
 * Unicode code points into the content; null in the title, or with a
 * selection that isn't a cursor in text.
 */
export function readForAgent(state: EditorState): { fields: Fields; cursor: number | null } {
  const fields = noteFields(state.doc)
  const { selection } = state
  if (!selection.empty || !selection.$head.parent.isTextblock || selection.$head.parent.type.name === 'title') {
    return { fields, cursor: null }
  }
  // Not a pending bold or italic toggle's marks: those aren't in the saved text.
  const marked = noteFields(state.tr.setStoredMarks(null).insertText(MARK, selection.head).doc).content
  const at = marked.indexOf(MARK)
  // Code points, not UTF-16 units: the server counts runes.
  return { fields, cursor: at < 0 ? null : Array.from(marked.slice(0, at)).length }
}

/** A run's change as the agent asked for it (the server's agent.edit). */
export type AgentOp =
  | { kind: 'title'; title: string }
  | { kind: 'replace'; find: string; replace: string }
  | { kind: 'insert'; at: 'start' | 'end' | 'cursor'; text: string }
  | { kind: 'content'; text: string }

/** A run's change: after for before, or op made to what the editor shows instead (agent.EditorChange). */
export type AgentChange = { before: Fields; after: Fields; op?: AgentOp }

/**
 * now with the change made to it: after, if now is before, else op made to now, as the server's
 * EditorChange.MadeTo does, so the user's typing meanwhile doesn't make the run read the note again.
 * Null when op no longer fits: its find isn't there exactly once, or an insert at the cursor has none.
 */
export function madeTo(change: AgentChange, now: Fields, cursor: number | null): Fields | null {
  const { before, after, op } = change
  if (now.title === before.title && now.content === before.content) return after
  if (!op) return null
  const { title, content } = now
  switch (op.kind) {
    case 'title':
      return { title: op.title, content }
    case 'replace': {
      const at = content.indexOf(op.find)
      if (op.find === '' || at < 0 || content.indexOf(op.find, at + op.find.length) >= 0) return null
      return { title, content: content.slice(0, at) + op.replace + content.slice(at + op.find.length) }
    }
    case 'insert': {
      if (op.at !== 'cursor') return { title, content: insertText(content, op.text, op.at === 'start') }
      if (cursor === null) return null
      const points = Array.from(content)
      const at = Math.min(Math.max(cursor, 0), points.length)
      return { title, content: points.slice(0, at).join('') + op.text + points.slice(at).join('') }
    }
    case 'content':
      // Made again, the whole content would wipe out what the user typed.
      return null
  }
}

/** text as its own block at the start or end of content, as the server's insertText adds it. */
function insertText(content: string, text: string, atStart: boolean): string {
  if (content.replace(/^\n+|\n+$/g, '') === '') return `${text}\n`
  if (atStart) return `${text}\n\n${content.replace(/^\n+/, '')}`
  return `${content.replace(/\n+$/, '')}\n\n${text}\n`
}

/**
 * Changes the note to after as one step of its own in the undo history,
 * replacing only what differs so the selection stays put.
 */
export function applyForAgent(editor: Editor, after: Fields) {
  const { state } = editor
  const next = state.schema.nodeFromJSON(noteContent(after))
  const start = state.doc.content.findDiffStart(next.content)
  if (start === null) return
  const end = state.doc.content.findDiffEnd(next.content)
  if (!end) return
  let { a: endA, b: endB } = end
  // Where the change repeats text around it, the two ends cross.
  const overlap = start - Math.min(endA, endB)
  if (overlap > 0) {
    endA += overlap
    endB += overlap
  }
  editor.view.dispatch(closeHistory(state.tr.replace(start, endA, next.slice(start, endB))))
  // And the user's next keystroke starts a step of its own.
  editor.view.dispatch(closeHistory(editor.state.tr))
}

/** Resolves once the user isn't mid-way through composing a character (an IME), or after a few seconds. */
export function composed(editor: Editor, maxMs = 3000): Promise<void> {
  if (!editor.view.composing) return Promise.resolve()
  return new Promise((resolve) => {
    const done = () => {
      clearTimeout(timer)
      editor.view.dom.removeEventListener('compositionend', done)
      // ProseMirror applies the composed text just after the event.
      setTimeout(resolve, 0)
    }
    const timer = setTimeout(done, maxMs)
    editor.view.dom.addEventListener('compositionend', done)
  })
}
