import { Node, type Extensions } from '@tiptap/core'
import { Blockquote } from '@tiptap/extension-blockquote'
import { Bold } from '@tiptap/extension-bold'
import { Document } from '@tiptap/extension-document'
import { HardBreak } from '@tiptap/extension-hard-break'
import { Heading } from '@tiptap/extension-heading'
import { Italic } from '@tiptap/extension-italic'
import { Link } from '@tiptap/extension-link'
import { BulletList, ListItem, ListKeymap, OrderedList, TaskItem, TaskList } from '@tiptap/extension-list'
import { Paragraph } from '@tiptap/extension-paragraph'
import { Strike } from '@tiptap/extension-strike'
import { Text } from '@tiptap/extension-text'
import { Placeholder, UndoRedo } from '@tiptap/extensions'
import { buildFence, editFence, fenceOpener, rawBlockView } from './fence'

const linkProtocols = /^(https?:|mailto:)/i

export function isAllowedLink(url: string): boolean {
  return linkProtocols.test(url.trim())
}

/**
 * Markdown the editor can't represent (code, tables, images, rules…), kept
 * as its exact source: shown read-only and written back unchanged, so a
 * save never corrupts a note written on another device (spec §7.1).
 */
export const RawBlock = Node.create({
  name: 'rawBlock',
  group: 'block',
  atom: true,
  selectable: true,
  draggable: false,
  addAttributes() {
    return { source: { default: '' } }
  },
  parseHTML() {
    return [{ tag: 'pre[data-raw]', getAttrs: (el) => ({ source: el.textContent ?? '' }) }]
  },
  renderHTML({ node }) {
    return [
      'pre',
      { 'data-raw': '', contenteditable: 'false', title: 'Not editable here; kept exactly as written' },
      node.attrs.source as string,
    ]
  },
  // A code fence shows its code, not its fence lines, highlighted by language,
  // and opens a code box on click. renderHTML above still gives copy/paste the exact source.
  addNodeView() {
    return ({ node, view, getPos }) => rawBlockView(node, view, getPos)
  },
  // A paragraph holding only ``` (or ```go) turns into a code fence on Enter
  // and opens its code box, the way code blocks start in other editors.
  addKeyboardShortcuts() {
    return {
      Enter: ({ editor }) => {
        const { state, view } = editor
        const { $from, empty } = state.selection
        if (!empty || $from.parent.type.name !== 'paragraph' || $from.parentOffset !== $from.parent.content.size) return false
        const opener = fenceOpener($from.parent.textContent)
        if (!opener) return false
        const start = $from.before()
        const block = this.type.create({ source: buildFence(opener.lang, '') })
        const tr = state.tr.replaceWith(start, $from.after(), block)
        // Something to type into after the block when it ends the note.
        const paragraph = state.schema.nodes.paragraph
        if (paragraph && !tr.doc.nodeAt(start + block.nodeSize)) tr.insert(start + block.nodeSize, paragraph.create())
        view.dispatch(tr)
        editFence(view, start)
        return true
      },
    }
  },
})

export const RawInline = Node.create({
  name: 'rawInline',
  group: 'inline',
  inline: true,
  atom: true,
  selectable: true,
  addAttributes() {
    return { source: { default: '' } }
  },
  parseHTML() {
    return [{ tag: 'code[data-raw]', getAttrs: (el) => ({ source: el.textContent ?? '' }) }]
  },
  renderHTML({ node }) {
    return ['code', { 'data-raw': '', contenteditable: 'false' }, node.attrs.source as string]
  },
})

/**
 * An image the apps added (spec §7.6). Like a raw run it is atomic and
 * written back as its exact source; id, size and alt are read from it. The
 * editor swaps in a node view that loads (and decrypts) the picture.
 */
export const NoteImage = Node.create({
  name: 'image',
  group: 'inline',
  inline: true,
  atom: true,
  selectable: true,
  draggable: false,
  addAttributes() {
    return {
      source: { default: '' },
      id: { default: '' },
      w: { default: 1 },
      h: { default: 1 },
      alt: { default: '' },
    }
  },
  parseHTML() {
    return [
      {
        tag: 'img[data-image]',
        getAttrs: (el) => ({
          source: el.getAttribute('data-image') ?? '',
          id: el.getAttribute('data-id') ?? '',
          w: Number(el.getAttribute('width') ?? 1),
          h: Number(el.getAttribute('height') ?? 1),
          alt: el.getAttribute('alt') ?? '',
        }),
      },
    ]
  },
  renderHTML({ node }) {
    const { source, id, w, h, alt } = node.attrs as { source: string; id: string; w: number; h: number; alt: string }
    return ['img', { 'data-image': source, 'data-id': id, width: w, height: h, alt }]
  },
})

/**
 * The note's first line, stored as its own plain-text title field, not in
 * the Markdown. Marks aren't allowed: a title has no formatting.
 */
const Title = Node.create({
  name: 'title',
  content: 'text*',
  marks: '',
  defining: true,
  parseHTML() {
    return [{ tag: 'h1[data-title]', priority: 60 }]
  },
  renderHTML() {
    return ['h1', { 'data-title': '' }, 0]
  },
})

const blockExtensions: Extensions = [
  Paragraph,
  Text,
  HardBreak,
  Bold,
  Italic,
  Strike,
  Heading.configure({ levels: [1, 2, 3] }),
  BulletList,
  OrderedList,
  ListItem,
  TaskList,
  TaskItem.configure({ nested: true }),
  Blockquote,
  Link.configure({
    openOnClick: false,
    autolink: true,
    linkOnPaste: true,
    protocols: ['mailto'],
    defaultProtocol: 'https',
    isAllowedUri: (url) => isAllowedLink(url),
  }),
  RawBlock,
  RawInline,
  NoteImage,
]

/** Document model only: what Markdown parses into. */
export const schemaExtensions: Extensions = [Document, ...blockExtensions]

/** The editor: a title line, then the body, plus editing behaviour. Nothing outside spec §7.1. */
export const editorExtensions: Extensions = [
  Document.extend({ content: 'title block+' }),
  Title,
  ...blockExtensions.map((e) =>
    e.name === 'taskList'
      ? TaskList.extend({
          addKeyboardShortcuts() {
            return { ...this.parent?.(), 'Mod-Shift-l': () => this.editor.commands.toggleTaskList() }
          },
        })
      : e,
  ),
  ListKeymap,
  UndoRedo,
  Placeholder.configure({
    showOnlyCurrent: false,
    placeholder: ({ node }) => (node.type.name === 'title' ? 'New note' : ''),
  }),
]
