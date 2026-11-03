import { Editor } from '@tiptap/core'
import { TextSelection } from '@tiptap/pm/state'
import { afterEach, describe, expect, it } from 'vitest'
import { applyForAgent } from './agent-edit'
import { AgentPresence, agentPresence, presenceTiming, setAgentWaiting } from './agent-presence'
import { editorExtensions } from './extensions'
import { noteContent, noteFields } from './note-doc'
import styles from './editor.module.css'

let editor: Editor
afterEach(() => editor.destroy())

function open(content: string) {
  editor = new Editor({
    element: document.createElement('div'),
    extensions: [...editorExtensions, AgentPresence],
    content: noteContent({ title: 'T', content }),
  })
}

const text = (from: number, to: number) => editor.state.doc.textBetween(from, to, '\n')
const shown = (cls: string | undefined) => editor.view.dom.querySelector(`.${cls ?? ''}`)

describe('the agent in the note', () => {
  it('waits at the top of the body when the cursor is in the title', () => {
    open('Body')
    editor.view.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc, 1)))
    setAgentWaiting(editor, 'Journal agent')
    const at = agentPresence(editor.state)?.waiting?.at ?? -1
    expect(text(at, at + 4)).toBe('Body')
    expect(shown(styles.agentMark)?.textContent).toBe('Journal agent')
    setAgentWaiting(editor, null)
    expect(shown(styles.agentMark)).toBeNull()
  })

  it('marks what the change put in, with Undo until the user types', () => {
    open('First')
    setAgentWaiting(editor, 'Journal agent')
    applyForAgent(editor, { title: 'T', content: '## A question?\n\nFirst\n' })
    const landed = agentPresence(editor.state)?.landed
    expect(landed && text(landed.from, landed.to)).toBe('A question?')
    expect(shown(styles.agentMark)).toBeNull()
    expect(shown(styles.agentLanded)?.textContent).toBe('A question?')

    // Undo here takes back the agent's change.
    ;(shown(styles.agentUndo) as HTMLButtonElement).click()
    expect(noteFields(editor.state.doc).content).toBe('First\n')
    expect(agentPresence(editor.state)?.landed).toBeNull()
  })

  it('stops offering Undo once the user types, or after a while', async () => {
    open('First')
    applyForAgent(editor, { title: 'T', content: '## A question?\n\nFirst\n' })
    editor.commands.insertContentAt(editor.state.doc.content.size - 1, '!')
    expect(shown(styles.agentUndo)).toBeNull()

    const wait = presenceTiming.landedMs
    presenceTiming.landedMs = 10
    try {
      applyForAgent(editor, { title: 'T', content: '## A question?\n\nFirst!\n\nMore\n' })
      expect(shown(styles.agentUndo)).not.toBeNull()
      await new Promise((r) => setTimeout(r, 30))
      expect(shown(styles.agentUndo)).toBeNull()
    } finally {
      presenceTiming.landedMs = wait
    }
  })
})
