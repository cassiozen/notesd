import { Extension, type Editor } from '@tiptap/core'
import { DOMSerializer, type Node as PMNode } from '@tiptap/pm/model'
import { Plugin, PluginKey, type EditorState, type Transaction } from '@tiptap/pm/state'
import { Decoration, DecorationSet } from '@tiptap/pm/view'
import type { AgentOp } from './agent-edit'
import { isAllowedLink } from './extensions'
import { parse, schema } from './markdown'
import styles from './editor.module.css'

/** Where an overlay shows: at the top or end of the note, the cursor, or under the block quoted. */
export type OverlayPlace = { at?: 'top' | 'end' | 'cursor'; after?: string }

/**
 * What a run shows beside a note open here, never in it (the server's agent.Overlay): a card to read
 * and dismiss, or a suggested change to accept or reject.
 */
export type Overlay = {
  id: string
  runId: string
  noteId: string
  kind: 'card' | 'suggestion'
  place: OverlayPlace
  /** A card's Markdown, or why a suggestion is made. */
  body: string
  change?: AgentOp
}

export type OverlayAnswer = 'dismiss' | 'accept' | 'reject'

/** at: where in the document it's anchored now, which for a cursor's is where an insert goes. */
type OnAnswer = (overlay: Overlay, answer: OverlayAnswer, at: number) => void

type Shown = { overlay: Overlay; at: number }
type State = { shown: Shown[]; label: string; onAnswer: OnAnswer | null }
type Meta = { list: Overlay[]; label: string; onAnswer: OnAnswer }

const key = new PluginKey<State>('agentOverlays')

/** Just after the title: the top of the body. */
const top = (doc: PMNode) => doc.firstChild?.nodeSize ?? 0

/** Where an overlay newly shown is anchored; the top when what it places it by isn't there. */
function anchor(state: EditorState, place: OverlayPlace): number {
  const { doc, selection } = state
  if (place.after) return find(doc, place.after) ?? top(doc)
  if (place.at === 'end') return doc.content.size
  if (place.at === 'cursor' && selection.$head.parent.isTextblock && selection.$head.parent.type.name !== 'title') return selection.head
  return top(doc)
}

/**
 * Inside the first textblock showing the quote (Markdown, as the server has the note), or its longest
 * line with the syntax taken out: the editor shows text, not Markdown.
 */
function find(doc: PMNode, quote: string): number | null {
  const plain = quote.replace(/^\s*(#{1,6}\s+|>\s*|[-*+]\s+(\[[ xX]\]\s+)?|\d+[.)]\s+)/gm, '').replace(/\]\([^)]*\)|[*_~`[\]]/g, '')
  const lines = plain.split('\n').map((l) => l.trim())
  const longest = lines.reduce((a, b) => (b.length > a.length ? b : a), '')
  for (const q of [quote, longest]) {
    if (!q) continue
    let at: number | null = null
    doc.descendants((node, pos) => {
      if (at !== null) return false
      if (!node.isTextblock || node.type.name === 'title') return true
      if (node.textContent.includes(q)) at = pos + 1
      return false
    })
    if (at !== null) return at
  }
  return null
}

/** Where the card goes: at the top or end as they are now, else between blocks, under the textblock anchoring it. */
function spot(doc: PMNode, { overlay, at }: Shown): number {
  const { place } = overlay
  if (!place.after && place.at === 'end') return doc.content.size
  if (!place.after && place.at !== 'cursor') return top(doc)
  const $at = doc.resolve(Math.min(at, doc.content.size))
  if (!$at.parent.isTextblock) return $at.pos
  return $at.parent.type.name === 'title' ? top(doc) : $at.after()
}

function button(label: string, text: string, onClick: () => void): HTMLButtonElement {
  const b = document.createElement('button')
  b.type = 'button'
  b.className = styles.agentCardButton ?? ''
  b.textContent = text
  b.setAttribute('aria-label', label)
  b.title = label
  // Keeps the user's cursor where it was.
  b.addEventListener('mousedown', (e) => e.preventDefault())
  b.addEventListener('click', onClick)
  return b
}

/** A card's Markdown as the editor would show it, with links only where the editor allows them. */
function markdown(body: string): DocumentFragment | HTMLElement {
  const fragment = DOMSerializer.fromSchema(schema).serializeFragment(parse(body).content)
  for (const a of fragment.querySelectorAll('a')) {
    if (!isAllowedLink(a.getAttribute('href') ?? '')) a.removeAttribute('href')
    else {
      a.target = '_blank'
      a.rel = 'noopener noreferrer'
    }
  }
  for (const input of fragment.querySelectorAll('input')) input.disabled = true
  return fragment
}

function part(tag: 'del' | 'ins', text: string): HTMLElement {
  const t = document.createElement(tag)
  t.textContent = text
  return t
}

/** A suggestion's change in words: what goes, then what comes. */
function change(op: AgentOp): HTMLElement {
  const el = document.createElement('p')
  el.className = styles.agentChange ?? ''
  switch (op.kind) {
    case 'replace':
      if (op.find) el.append(part('del', op.find))
      if (op.find && op.replace) el.append(' → ')
      if (op.replace) el.append(part('ins', op.replace))
      break
    case 'title':
      el.append('Title: ', part('ins', op.title))
      break
    case 'insert':
    case 'content':
      el.append(part('ins', op.text))
  }
  return el
}

function card(overlay: Overlay, label: string, onAnswer: (a: OverlayAnswer) => void): HTMLElement {
  const el = document.createElement('div')
  el.className = styles.agentCard ?? ''
  el.contentEditable = 'false'
  el.dataset.agentWidget = ''
  el.dataset.overlay = overlay.id
  el.setAttribute('role', 'note')
  el.setAttribute('aria-label', overlay.kind === 'card' ? `${label}: a note` : `${label}: a suggested change`)
  const head = document.createElement('div')
  head.className = styles.agentCardHead ?? ''
  const who = document.createElement('span')
  who.className = styles.agentCardWho ?? ''
  const dot = document.createElement('span')
  dot.className = styles.agentDot ?? ''
  who.append(dot, label)
  head.append(who)
  const answer = (a: OverlayAnswer) => () => onAnswer(a)
  if (overlay.kind === 'card') head.append(button('Dismiss', '×', answer('dismiss')))
  else head.append(button('Accept the change', '✓', answer('accept')), button('Reject the change', '✗', answer('reject')))
  const body = document.createElement('div')
  body.className = styles.agentCardBody ?? ''
  if (overlay.kind === 'card') body.append(markdown(overlay.body))
  else {
    if (overlay.body) {
      const why = document.createElement('p')
      why.textContent = overlay.body
      body.append(why)
    }
    if (overlay.change) body.append(change(overlay.change))
  }
  el.append(head, body)
  return el
}

/** Hands an answer to the latest handler, with where the overlay is anchored now: a card drawn earlier holds neither. */
function answered(state: EditorState, overlay: Overlay, answer: OverlayAnswer) {
  const p = key.getState(state)
  const shown = p?.shown.find((s) => s.overlay.id === overlay.id)
  if (shown) p?.onAnswer?.(overlay, answer, shown.at)
}

/**
 * Shows what agents' runs put beside the note: cards, positioned between its blocks and never part of
 * its text, each answered with × or ✓ and ✗.
 */
export const AgentOverlays = Extension.create({
  name: 'agentOverlays',

  addProseMirrorPlugins() {
    return [
      new Plugin<State>({
        key,
        state: {
          init: () => ({ shown: [], label: '', onAnswer: null }),
          apply(tr: Transaction, prev: State, _old, state): State {
            const meta = tr.getMeta(key) as Meta | undefined
            let shown = prev.shown
            // The user's typing carries them along: one placed by a quote stays put after the quote changes.
            if (tr.docChanged) shown = shown.map((s) => ({ ...s, at: tr.mapping.map(s.at, -1) }))
            if (!meta) return shown === prev.shown ? prev : { ...prev, shown }
            const was = new Map(shown.map((s) => [s.overlay.id, s]))
            return {
              shown: meta.list.map((overlay) => was.get(overlay.id) ?? { overlay, at: anchor(state, overlay.place) }),
              label: meta.label,
              onAnswer: meta.onAnswer,
            }
          },
        },
        props: {
          decorations(state) {
            const p = key.getState(state)
            if (!p?.shown.length) return null
            const { doc } = state
            const decos = p.shown.map((s, i) =>
              Decoration.widget(spot(doc, s), (view) => card(s.overlay, p.label, (a) => answered(view.state, s.overlay, a)), {
                // In the order shown, after anything else at the same place.
                side: 1 + i,
                key: `overlay-${s.overlay.id}-${p.label}`,
                ignoreSelection: true,
                stopEvent: () => true,
              }),
            )
            return DecorationSet.create(doc, decos)
          },
        },
      }),
    ]
  },
})

/** Shows a note's open overlays, all of them: any not in list are taken away. */
export function setAgentOverlays(editor: Editor, list: Overlay[], label: string, onAnswer: OnAnswer) {
  if (editor.isDestroyed) return
  editor.view.dispatch(editor.state.tr.setMeta(key, { list, label, onAnswer } satisfies Meta).setMeta('addToHistory', false))
}

/** What shows and where it's anchored, for tests. */
export function agentOverlays(state: EditorState): Shown[] {
  return key.getState(state)?.shown ?? []
}
