import { Editor } from '@tiptap/core'
import { TextSelection } from '@tiptap/pm/state'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AgentOverlays, agentOverlays, setAgentOverlays, stillQuoted, type Overlay } from './agent-overlays'
import { editorExtensions } from './extensions'
import { noteContent, noteFields } from './note-doc'
import styles from './editor.module.css'

let editor: Editor
afterEach(() => editor.destroy())

function open(content: string) {
  editor = new Editor({
    element: document.createElement('div'),
    extensions: [...editorExtensions, AgentOverlays],
    content: noteContent({ title: 'T', content }),
  })
}

const card = (id: string, place: Overlay['place'], body = id): Overlay => ({ id, runId: 'r', noteId: 'n', kind: 'card', place, body })

/** Each block's text, with the cards between them as [id]. */
function layout(): string[] {
  return [...editor.view.dom.children].map((el) => (el instanceof HTMLElement && el.dataset.overlay ? `[${el.dataset.overlay}]` : (el.textContent ?? '')))
}

describe('cards beside the note', () => {
  it('go at the top, the end, or under the block quoted, and are never in its text', () => {
    open('First\n\nSecond **bold** line\n\nThird')
    setAgentOverlays(editor, [card('top', { at: 'top' }), card('end', { at: 'end' }), card('quote', { after: 'Second **bold**' })], 'Journal agent', () => {})
    expect(layout()).toEqual(['T', '[top]', 'First', 'Second bold line', '[quote]', 'Third', '[end]'])
    expect(noteFields(editor.state.doc).content).toBe('First\n\nSecond **bold** line\n\nThird\n')
    expect(editor.view.dom.querySelector(`.${styles.agentCardWho}`)?.textContent).toBe('Journal agent')
  })

  it('go at the top when the quote isn’t there', () => {
    open('First')
    setAgentOverlays(editor, [card('lost', { after: 'Nowhere' })], 'A', () => {})
    expect(layout()).toEqual(['T', '[lost]', 'First'])
  })

  it('go by the Markdown quoted, not other text that looks the same', () => {
    open('Milk is good\n\n**Milk** is good')
    setAgentOverlays(editor, [card('bold', { after: '**Milk** is' })], 'A', () => {})
    expect(layout()).toEqual(['T', 'Milk is good', 'Milk is good', '[bold]'])
  })

  it('go at the top when the text quoted shows in more than one block', () => {
    open('Milk is *good*\n\nMilk is **good**')
    setAgentOverlays(editor, [card('which', { after: 'Milk is _good_' })], 'A', () => {})
    expect(layout()).toEqual(['T', '[which]', 'Milk is good', 'Milk is good'])
  })

  it('know when the block they quote no longer holds the quote, wherever else it is typed', () => {
    open('Teh plan\n\nOther')
    setAgentOverlays(editor, [card('fix', { after: 'Teh' })], 'A', () => {})
    expect(stillQuoted(editor.state, 'fix')).toBe(true)
    // "Teh" goes from its block, and is typed into another.
    editor.commands.insertContentAt({ from: 4, to: 7 }, 'The')
    editor.commands.insertContentAt(editor.state.doc.content.size - 1, ' Teh')
    expect(stillQuoted(editor.state, 'fix')).toBe(false)
  })

  it('stay under their block while the user types, and the end stays the end', () => {
    open('First\n\nSecond')
    editor.view.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc, 4)))
    setAgentOverlays(editor, [card('here', { at: 'cursor' }), card('end', { at: 'end' })], 'A', () => {})
    expect(layout()).toEqual(['T', 'First', '[here]', 'Second', '[end]'])
    editor.commands.insertContentAt(editor.state.doc.content.size, { type: 'paragraph', content: [{ type: 'text', text: 'Third' }] })
    editor.commands.insertContentAt(5, 'xx')
    expect(layout()).toEqual(['T', 'Fxxirst', '[here]', 'Second', 'Third', '[end]'])
  })

  it('show Markdown, without links the editor wouldn’t allow', () => {
    open('First')
    setAgentOverlays(editor, [card('c', { at: 'top' }, '**What** made you smile? [ok](https://x.test) [bad](javascript:alert(1))')], 'A', () => {})
    const body = editor.view.dom.querySelector(`.${styles.agentCardBody}`)
    expect(body?.querySelector('strong')?.textContent).toBe('What')
    const links = [...(body?.querySelectorAll('a') ?? [])]
    expect(links.map((a) => a.getAttribute('href'))).toEqual(['https://x.test'])
  })

  it('answer with ×, or ✓ and ✗, saying where they’re anchored now', () => {
    open('Teh plan')
    const onAnswer = vi.fn()
    const fix: Overlay = { ...card('fix', { after: 'Teh' }, 'Typo'), kind: 'suggestion', change: { kind: 'replace', find: 'Teh', replace: 'The' } }
    setAgentOverlays(editor, [card('c', { at: 'top' }), fix], 'A', onAnswer)
    const cards = [...editor.view.dom.querySelectorAll<HTMLElement>(`.${styles.agentCard}`)]
    expect(cards[1]?.textContent).toContain('Typo')
    expect(cards[1]?.querySelector('del')?.textContent).toBe('Teh')
    expect(cards[1]?.querySelector('ins')?.textContent).toBe('The')

    cards[0]?.querySelector<HTMLButtonElement>('[aria-label="Dismiss"]')?.click()
    expect(onAnswer).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'c' }), 'dismiss', expect.any(Number))
    editor.commands.insertContentAt(5, 'xx')
    // Drawn before the typing, the card still answers with where it is now.
    cards[1]?.querySelector<HTMLButtonElement>('[aria-label="Accept the change"]')?.click()
    const anchored = agentOverlays(editor.state).find((s) => s.overlay.id === 'fix')?.at
    expect(onAnswer).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'fix' }), 'accept', anchored)
    cards[1]?.querySelector<HTMLButtonElement>('[aria-label="Reject the change"]')?.click()
    expect(onAnswer).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'fix' }), 'reject', anchored)

    setAgentOverlays(editor, [], 'A', onAnswer)
    expect(editor.view.dom.querySelector(`.${styles.agentCard}`)).toBeNull()
  })
})
