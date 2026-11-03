import type { Editor } from '@tiptap/core'
import { closeHistory } from '@tiptap/pm/history'
import type { EditorState } from '@tiptap/pm/state'
import type { Fields } from '../notes/saver'
import { markAgentLanded } from './agent-presence'
import { noteContent, noteFields } from './note-doc'

/** Marks the transaction of an agent's change: not the user editing. */
export const AGENT_EDIT = 'agentEdit'

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
  const marked = noteFields(state.tr.insertText(MARK, selection.head).doc).content
  const at = marked.indexOf(MARK)
  // Code points, not UTF-16 units: the server counts runes.
  return { fields, cursor: at < 0 ? null : Array.from(marked.slice(0, at)).length }
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
  const tr = state.tr.replace(start, endA, next.slice(start, endB))
  editor.view.dispatch(markAgentLanded(closeHistory(tr), start, endB).setMeta(AGENT_EDIT, true))
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
