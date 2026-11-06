import { Extension, type Editor } from '@tiptap/core'
import { Plugin, PluginKey, Selection, type EditorState, type Transaction } from '@tiptap/pm/state'
import { Decoration, DecorationSet } from '@tiptap/pm/view'
import styles from './editor.module.css'

/** How long a change the agent made stays marked, and its Undo offered. */
export const presenceTiming = { landedMs: 6000 }

type Presence = {
  /** Where the agent is expected to write, while it works: who's writing. */
  waiting: { at: number; label: string } | null
  /** What the agent's last change put in, until the user moves on. */
  landed: { from: number; to: number } | null
}

type Meta = { waiting?: string | null; landed?: { from: number; to: number } | null }

const key = new PluginKey<Presence>('agentPresence')

/** Where a run on the note will most likely write: the cursor, or the top of the body when it's in the title. */
function likelySpot(state: EditorState): number {
  const { selection, doc } = state
  const $head = selection.$head
  if (selection.empty && $head.parent.isTextblock && $head.parent.type.name !== 'title') return selection.head
  const title = doc.firstChild
  return Math.min((title?.nodeSize ?? 0) + 1, doc.content.size)
}

function marker(label: string): HTMLElement {
  const el = document.createElement('span')
  el.className = styles.agentMark ?? ''
  el.contentEditable = 'false'
  el.setAttribute('aria-hidden', 'true')
  el.dataset.agentWidget = ''
  const dot = document.createElement('span')
  dot.className = styles.agentDot ?? ''
  el.append(dot, label)
  return el
}

function undoButton(editor: Editor): HTMLElement {
  const b = document.createElement('button')
  b.type = 'button'
  b.className = styles.agentUndo ?? ''
  b.contentEditable = 'false'
  b.textContent = 'Undo'
  b.dataset.agentWidget = ''
  b.setAttribute('aria-label', 'Undo the agent’s change')
  // Keeps the user's cursor where it was.
  b.addEventListener('mousedown', (e) => e.preventDefault())
  b.addEventListener('click', () => editor.commands.undo())
  return b
}

/**
 * Shows the agent at work in the note: a marker where it's expected to
 * write while a run goes, then what it wrote, highlighted for a moment, with
 * Undo beside it until the user types.
 */
export const AgentPresence = Extension.create({
  name: 'agentPresence',

  addProseMirrorPlugins() {
    const editor = this.editor
    let clear: ReturnType<typeof setTimeout> | undefined
    let timed: Presence['landed'] = null
    return [
      new Plugin<Presence>({
        key,
        state: {
          init: () => ({ waiting: null, landed: null }),
          apply(tr: Transaction, prev: Presence, _old, state): Presence {
            const meta = tr.getMeta(key) as Meta | undefined
            let { waiting, landed } = prev
            if (waiting && tr.docChanged) waiting = { ...waiting, at: tr.mapping.map(waiting.at) }
            if (landed && tr.docChanged) {
              // The user typed: the change is theirs to keep now, and Undo would take back their typing first.
              landed = meta?.landed !== undefined ? landed : null
            }
            if (meta?.waiting !== undefined) waiting = meta.waiting === null ? null : { at: likelySpot(state), label: meta.waiting }
            if (meta?.landed !== undefined) {
              landed = meta.landed
              // Only the change landing ends the wait; its highlight expiring may come during the next run's.
              if (meta.landed) waiting = null
            }
            return { waiting, landed }
          },
        },
        props: {
          decorations(state) {
            const p = key.getState(state)
            if (!p) return null
            const decos: Decoration[] = []
            if (p.waiting) {
              const { label } = p.waiting
              decos.push(Decoration.widget(p.waiting.at, () => marker(label), { side: 1, key: `agent-${label}` }))
            }
            if (p.landed) {
              // A deletion has nothing to highlight, but can still be undone.
              if (p.landed.to > p.landed.from) decos.push(Decoration.inline(p.landed.from, p.landed.to, { class: styles.agentLanded ?? '' }))
              // At the end of its last line of text, not past it.
              const end = Selection.near(state.doc.resolve(p.landed.to), -1).head
              decos.push(Decoration.widget(Math.max(end, p.landed.from), () => undoButton(editor), { side: 1, key: 'agent-undo' }))
            }
            return decos.length ? DecorationSet.create(state.doc, decos) : null
          },
        },
        view: () => ({
          update(view) {
            const landed = key.getState(view.state)?.landed ?? null
            if (landed === timed) return
            // A new change restarts the clock; none stops it.
            clearTimeout(clear)
            timed = landed
            if (landed) {
              clear = setTimeout(() => {
                if (!view.isDestroyed) view.dispatch(view.state.tr.setMeta(key, { landed: null } satisfies Meta))
              }, presenceTiming.landedMs)
            }
          },
          destroy: () => clearTimeout(clear),
        }),
      }),
    ]
  },
})

/** Shows, or with null hides, the marker of an agent at work on the note. */
export function setAgentWaiting(editor: Editor, label: string | null) {
  if (editor.isDestroyed) return
  editor.view.dispatch(editor.state.tr.setMeta(key, { waiting: label } satisfies Meta).setMeta('addToHistory', false))
}

/** Marks what an agent's change put in, from just after the transaction that made it. */
export function markAgentLanded(tr: Transaction, from: number, to: number): Transaction {
  return tr.setMeta(key, { landed: { from, to } } satisfies Meta)
}

/** The marker and the highlighted change, for tests. */
export function agentPresence(state: EditorState): Presence | undefined {
  return key.getState(state)
}
