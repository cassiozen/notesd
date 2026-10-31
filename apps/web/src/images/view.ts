import { LOCKED_IMAGE } from '../api/images'
import { NoteImage } from '../editor/extensions'
import { notesSource } from '../notes/source'
import { decryptedURL, knownLocked } from './store'

/**
 * The image node as the editor shows it. A plain image loads straight from
 * the server; one that won't (it is encrypted: a locked note's) is fetched,
 * decrypted and shown from a blob: URL; so is every image kept on this
 * device. Sized from the reference, so text doesn't move when it arrives.
 */
export const ImageView = NoteImage.extend({
  addNodeView() {
    return ({ node }) => {
      const { id, w, h, alt } = node.attrs as { id: string; w: number; h: number; alt: string }
      const dom = document.createElement('span')
      dom.setAttribute('data-image-view', '')
      dom.contentEditable = 'false'
      const img = document.createElement('img')
      img.alt = alt
      img.width = w
      img.height = h
      img.draggable = false
      img.decoding = 'async'
      dom.append(img)

      let gone = false
      let kept: string | undefined
      // Missing may only mean offline: try again when the connection is back.
      const retry = () => {
        dom.removeAttribute('data-state')
        load()
      }
      const missing = () => {
        dom.setAttribute('data-state', 'missing')
        window.addEventListener('online', retry, { once: true })
      }
      const decrypt = (blob?: Blob) =>
        void decryptedURL(id, blob).then((url) => {
          if (gone) return
          if (url) img.src = url
          else missing()
        })
      const fromDevice = () =>
        notesSource()
          .images.get(id)
          .then(
            (blob) => {
              if (gone) return
              if (blob.type === LOCKED_IMAGE) return decrypt(blob)
              kept = URL.createObjectURL(blob)
              img.src = kept
            },
            () => !gone && missing(),
          )
      const load = () => {
        const url = notesSource().images.url
        if (knownLocked(id)) decrypt()
        else if (!url) void fromDevice()
        else {
          img.addEventListener('error', () => decrypt(), { once: true })
          img.src = url(id)
        }
      }
      load()
      return {
        dom,
        // Atom: nothing inside is the editor's to track.
        ignoreMutation: () => true,
        destroy: () => {
          gone = true
          window.removeEventListener('online', retry)
          if (kept) URL.revokeObjectURL(kept)
        },
      }
    }
  },
})
