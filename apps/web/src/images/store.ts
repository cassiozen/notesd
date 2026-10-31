import type { Editor } from '@tiptap/core'
import { LOCKED_IMAGE } from '../api/images'
import { LockedError, openImage, sealImage } from '../locks/crypto'
import { lockSession } from '../locks/session'
import { notesSource } from '../notes/source'
import { ImageError, prepareImage, sniffType, type Prepared } from './prepare'
import { imageMarkdown, sha256Hex, type ImageRef } from './ref'

// Storing and showing note images (architecture §7.6). An image is stored
// (uploaded, or kept on this device for sync) before its reference goes in the note.

/** Stores bytes as an image, encrypted with the session key when locked; returns its reference. */
async function upload(file: Prepared, locked: boolean): Promise<ImageRef> {
  let bytes = file.bytes
  let type = file.type
  if (locked) {
    const key = lockSession.key()
    if (!key) throw new ImageError('Unlock locked notes to add pictures to this note.')
    bytes = await sealImage(key, bytes)
    type = LOCKED_IMAGE
  }
  const id = await sha256Hex(bytes)
  await notesSource().images.put(id, bytes, type)
  remember(id, file.bytes, file.type, locked)
  return { id, w: file.w, h: file.h }
}

/** Prepares and uploads a picture the user picked for a note; locked() is asked again after preparing. */
export async function storeImage(file: Blob, locked: () => boolean): Promise<ImageRef> {
  const prepared = await prepareImage(file)
  const wasLocked = locked()
  const ref = await upload(prepared, wasLocked)
  // Locked or unlocked while uploading: store it the way the note is now.
  return locked() === wasLocked ? ref : await upload(prepared, locked())
}

// --- showing locked images ---------------------------------------------------

/** Decrypted images as blob: URLs, while locked notes are unlocked. */
const decrypted = new Map<string, Promise<string | null>>()

/** Keeps a locked image's plain bytes for showing it, unless locked notes locked meanwhile. */
function remember(id: string, plain: Uint8Array<ArrayBuffer>, type: string, locked: boolean) {
  // Locked during the upload: the cleanup below already ran, and nothing would revoke this URL.
  if (!locked || lockSession.getStatus() !== 'unlocked') return
  decrypted.set(id, Promise.resolve(URL.createObjectURL(new Blob([plain], { type }))))
}

lockSession.subscribe(() => {
  if (lockSession.getStatus() === 'unlocked') return
  for (const url of decrypted.values()) void url.then((u) => u && URL.revokeObjectURL(u))
  decrypted.clear()
})

/** Whether an image is known to be encrypted, so it can skip loading as a plain one first. */
export function knownLocked(id: string): boolean {
  return decrypted.has(id)
}

/**
 * A blob: URL for an encrypted image, decrypted with the session key; null
 * when it isn't encrypted, locked notes are locked, or it won't download.
 */
/** [blob]: the encrypted file, when the caller already has it. */
export function decryptedURL(id: string, blob?: Blob): Promise<string | null> {
  let url = decrypted.get(id)
  if (!url) {
    url = (async () => {
      const key = lockSession.key()
      if (!key) return null
      const file = blob ?? (await notesSource().images.get(id))
      if (file.type !== LOCKED_IMAGE) return null
      const plain = await openImage(key, new Uint8Array(await file.arrayBuffer()))
      return URL.createObjectURL(new Blob([plain], { type: sniffType(plain) ?? 'application/octet-stream' }))
    })().catch(() => null)
    decrypted.set(id, url)
    // A failure is retried next time, not cached.
    void url.then((u) => u === null && decrypted.get(id) === url && decrypted.delete(id))
  }
  return url
}

// --- locking and unlocking ---------------------------------------------------

/**
 * Before a note is locked (or its lock removed), re-uploads each of its
 * images encrypted (or plain) and points the image nodes at the new ones.
 * Throws, changing nothing, if an image can't be fetched or stored: a
 * locked note must not keep pointing at plain images.
 */
export async function resealImages(editor: Editor, locked: boolean): Promise<void> {
  const ids = new Set<string>()
  editor.state.doc.descendants((node) => {
    if (node.type.name === 'image') ids.add(String(node.attrs.id))
  })
  if (ids.size === 0) return
  const key = lockSession.key()
  if (!key) throw new ImageError('Unlock locked notes first.')

  const moved = new Map<string, string>()
  for (const id of ids) {
    // One at a time: images can be large, and the server writes serially anyway.
    // oxlint-disable-next-line no-await-in-loop
    const next = await moveImage(id, locked, key)
    if (next) moved.set(id, next)
  }
  if (moved.size === 0) return

  // Found again now: the note may have changed while the images moved.
  const { tr } = editor.state
  editor.state.doc.descendants((node, pos) => {
    if (node.type.name !== 'image') return
    const next = moved.get(String(node.attrs.id))
    if (!next) return
    const ref = { id: next, w: Number(node.attrs.w), h: Number(node.attrs.h) }
    tr.setNodeMarkup(pos, undefined, { ...node.attrs, id: next, source: imageMarkdown(ref, String(node.attrs.alt)) })
  })
  editor.view.dispatch(tr)
}

/** Re-uploads one image encrypted (locked) or plain; its new id, or null if it already is. */
async function moveImage(id: string, locked: boolean, key: CryptoKey): Promise<string | null> {
  const blob = await notesSource().images.get(id).catch(() => {
    throw new ImageError('A picture in this note couldn’t be downloaded. Try again when the connection is back.')
  })
  if ((blob.type === LOCKED_IMAGE) === locked) return null
  const bytes = new Uint8Array(await blob.arrayBuffer())
  let file: Uint8Array<ArrayBuffer>
  let type: string
  if (locked) {
    file = await sealImage(key, bytes)
    type = LOCKED_IMAGE
  } else {
    try {
      file = await openImage(key, bytes)
    } catch (err) {
      if (err instanceof LockedError) throw new ImageError('A picture in this note was locked with another key.')
      throw err
    }
    type = sniffType(file) ?? ''
    if (!type) throw new ImageError('A picture in this note isn’t an image the server accepts.')
  }
  const next = await sha256Hex(file)
  await notesSource().images.put(next, file, type).catch(() => {
    throw new ImageError('A picture in this note couldn’t be uploaded. Try again when the connection is back.')
  })
  remember(next, bytes, blob.type, locked)
  return next
}

/**
 * Puts an image on its own line at pos (mapped by the caller), or in the
 * empty paragraph there. Returns the position just after it, where the next
 * of several pictures goes, so they keep their order.
 */
export function insertImageAt(editor: Editor, pos: number, ref: ImageRef): number {
  const { state } = editor
  const node = state.schema.nodes.image?.create({ source: imageMarkdown(ref), ...ref, alt: '' })
  const paragraph = state.schema.nodes.paragraph
  if (!node || !paragraph) return pos
  const $pos = state.doc.resolve(Math.min(pos, state.doc.content.size))
  const block = $pos.parent
  const tr = state.tr
  let end: number
  if (block.type === paragraph && block.content.size === 0) {
    tr.insert($pos.pos, node)
    end = $pos.pos + node.nodeSize
  } else {
    // After the line the cursor is on; the title line counts as one too. At
    // the top level (a drop between blocks) pos is already between lines.
    const at = $pos.depth === 0 ? $pos.pos : $pos.after($pos.depth)
    tr.insert(at, paragraph.create(null, node))
    end = at + 1 + node.nodeSize
  }
  editor.view.dispatch(tr.scrollIntoView())
  return end
}
