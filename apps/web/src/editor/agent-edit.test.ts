import { Editor } from '@tiptap/core'
import { TextSelection } from '@tiptap/pm/state'
import { afterEach, describe, expect, it } from 'vitest'
import { applyForAgent, madeTo, readForAgent, type AgentOp } from './agent-edit'
import { editorExtensions } from './extensions'
import { noteContent, noteFields } from './note-doc'

let editor: Editor
afterEach(() => editor.destroy())

function open(content: string, cursorAfter?: string) {
  editor = new Editor({ extensions: editorExtensions, content: noteContent({ title: 'T', content }) })
  if (cursorAfter !== undefined) {
    let at = -1
    editor.state.doc.descendants((node, pos) => {
      const i = node.isText ? (node.text ?? '').indexOf(cursorAfter) : -1
      if (at < 0 && i >= 0) at = pos + i + cursorAfter.length
    })
    editor.view.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc, at)))
  }
}

describe('readForAgent', () => {
  it('gives the cursor in code points into the saved text', () => {
    open('# Plan\n\nDear **Sam** 👋, hi', 'Sam')
    const { fields, cursor } = readForAgent(editor.state)
    expect(fields.content).toBe('# Plan\n\nDear **Sam** 👋, hi\n')
    // Inside the bold text, so before its closing marks.
    expect(Array.from(fields.content).slice(0, cursor ?? 0).join('')).toBe('# Plan\n\nDear **Sam')
  })

  it('leaves out a pending bold toggle, which the saved text lacks', () => {
    open('Dear Sam', 'Dear')
    editor.commands.toggleBold()
    const { fields, cursor } = readForAgent(editor.state)
    expect(fields.content).toBe('Dear Sam\n')
    expect(Array.from(fields.content).slice(0, cursor ?? 0).join('')).toBe('Dear')
  })

  it('has no cursor in the title', () => {
    open('Body')
    editor.view.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc, 1)))
    expect(readForAgent(editor.state).cursor).toBeNull()
  })
})

describe('applyForAgent', () => {
  it('changes only what differs, keeping the cursor, as one undoable step', () => {
    open('First\n\nSecond', 'Sec')
    const head = editor.state.selection.head
    applyForAgent(editor, { title: 'T', content: '# Added\n\nFirst\n\nSecond\n' })
    expect(noteFields(editor.state.doc).content).toBe('# Added\n\nFirst\n\nSecond\n')
    // Moved along by what was added above it.
    expect(editor.state.doc.textBetween(editor.state.selection.head - 3, editor.state.selection.head)).toBe('Sec')
    expect(editor.state.selection.head).toBeGreaterThan(head)
    editor.commands.undo()
    expect(noteFields(editor.state.doc).content).toBe('First\n\nSecond\n')
  })

  it('changes the title too', () => {
    open('Body')
    applyForAgent(editor, { title: 'Shopping', content: 'Body\n' })
    expect(noteFields(editor.state.doc)).toEqual({ title: 'Shopping', content: 'Body\n' })
  })
})

describe('madeTo', () => {
  const before = { title: 'T', content: 'Milk\n' }
  const change = (op?: AgentOp) => ({ before, after: { title: 'T', content: 'Milk\n\n#tag\n' }, op })

  it('is after while the editor still shows before', () => {
    expect(madeTo(change(), before, null)).toEqual({ title: 'T', content: 'Milk\n\n#tag\n' })
  })

  it('makes the op to what the user typed meanwhile, as the server would', () => {
    const now = { title: 'T', content: 'Milk\nTea\n' }
    expect(madeTo(change({ kind: 'insert', at: 'end', text: '#tag' }), now, null)?.content).toBe('Milk\nTea\n\n#tag\n')
    expect(madeTo(change({ kind: 'insert', at: 'start', text: '# List' }), now, null)?.content).toBe('# List\n\nMilk\nTea\n')
    expect(madeTo(change({ kind: 'insert', at: 'end', text: 'First' }), { title: 'T', content: '\n' }, null)?.content).toBe('First\n')
    expect(madeTo(change({ kind: 'insert', at: 'cursor', text: '🍵 ' }), { title: 'T', content: '🥛 Milk\nTea\n' }, 7)?.content).toBe(
      '🥛 Milk\n🍵 Tea\n',
    )
    expect(madeTo(change({ kind: 'replace', find: 'Tea', replace: 'Green tea' }), now, null)?.content).toBe('Milk\nGreen tea\n')
    expect(madeTo(change({ kind: 'replace', find: 'Tea', replace: '$&$&' }), now, null)?.content).toBe('Milk\n$&$&\n')
    expect(madeTo(change({ kind: 'title', title: 'Shopping' }), now, null)).toEqual({ title: 'Shopping', content: 'Milk\nTea\n' })
  })

  it('is null where the op no longer fits', () => {
    const now = { title: 'T', content: 'Tea\nTea\n' }
    expect(madeTo(change({ kind: 'replace', find: 'Milk', replace: 'Oat milk' }), now, null)).toBeNull()
    expect(madeTo(change({ kind: 'replace', find: 'Tea', replace: 'Coffee' }), now, null)).toBeNull()
    expect(madeTo(change({ kind: 'insert', at: 'cursor', text: 'x' }), now, null)).toBeNull()
    expect(madeTo(change(), now, null)).toBeNull()
    // The whole content, made again, would wipe out the typing.
    expect(madeTo(change({ kind: 'content', text: 'Rewritten\n' }), now, null)).toBeNull()
    expect(madeTo(change({ kind: 'content', text: 'Rewritten\n' }), before, null)?.content).toBe('Milk\n\n#tag\n')
  })
})
