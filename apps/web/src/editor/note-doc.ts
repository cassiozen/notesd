import type { JSONContent } from '@tiptap/core'
import { Fragment, Slice, type Node as PMNode, type Schema } from '@tiptap/pm/model'
import { plainText, previewOf } from '../notes/plaintext'
import type { Fields } from '../notes/saver'
import { parse, serialize } from './markdown'

/** The editor document for a note: its title as the first line, then the parsed body. */
export function noteContent({ title, content }: Fields): JSONContent {
  // Titles from other clients could hold line breaks; the title line can't.
  const line = title.replace(/\s*\n\s*/g, ' ')
  return {
    type: 'doc',
    content: [{ type: 'title', ...(line && { content: [{ type: 'text', text: line }] }) }, ...(parse(content).toJSON().content ?? [])],
  }
}

/**
 * Pasted plain text, read as Markdown: "# Heading" pastes as a heading, not
 * as text whose "#" a save would escape. Pasted into the title, the first
 * line's text becomes the title and the rest follows in the body.
 */
export function pastedSlice(markdown: string, schema: Schema, inTitle: boolean): Slice {
  const nodes = (parse(markdown).toJSON().content ?? []).map((json: JSONContent) => schema.nodeFromJSON(json))
  if (inTitle) {
    const first = nodes[0]
    const lead = first?.isTextblock ? nodes.shift() : undefined
    const text = lead?.textBetween(0, lead.content.size, ' ', (leaf: PMNode) => (leaf.isText ? '' : String(leaf.attrs.source ?? ' '))) ?? ''
    nodes.unshift(schema.node('title', null, text ? schema.text(text) : undefined))
  }
  return Slice.maxOpen(Fragment.fromArray(nodes))
}

/** The note's fields from the editor document. Serializes: call per save, not per keystroke. */
export function noteFields(doc: PMNode): Fields {
  return { title: titleOf(doc), content: serialize(doc) }
}

export function titleOf(doc: PMNode): string {
  const first = doc.firstChild
  return first?.type.name === 'title' ? first.textContent : ''
}

/**
 * Title and list preview straight from the document, cheap enough to run
 * while typing. Matches the server's preview except where Markdown and the
 * editor disagree on whitespace; the saved note's preview replaces it.
 */
export function noteSummary(doc: PMNode): { title: string; preview: string } {
  const lines: string[] = []
  let size = 0
  const visit = (node: PMNode) => {
    if (size > 200) return
    if (node.type.name === 'rawBlock') return push(plainText(String(node.attrs.source)))
    if (!node.isTextblock) return node.forEach(visit)
    let text = ''
    node.forEach((child) => {
      if (child.isText) text += child.text ?? ''
      else if (child.type.name === 'rawInline' || child.type.name === 'image') text += plainText(String(child.attrs.source))
      else if (child.type.name === 'hardBreak') text += '\n'
    })
    push(text)
  }
  const push = (text: string) => {
    lines.push(text)
    size += text.length
  }
  doc.forEach((node, _, i) => {
    if (i > 0 || node.type.name !== 'title') visit(node)
  })
  return { title: titleOf(doc), preview: previewOf(lines.join('\n')) }
}
