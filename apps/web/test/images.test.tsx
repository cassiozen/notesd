import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { decryptBody, openImage } from '../src/locks/crypto'
import { sha256Hex } from '../src/images/ref'
import { chooseLock, imageRef, keyOf, LOCK_PASSWORD, lockWithNewPassword, pick, PNG, pngFile, shown } from './image-helpers'
import { body, createNote, getNote, noteTitle, openNote as openInList, saved, selectInBody, signedIn } from './helpers'

// jsdom can't decode or draw pictures: preparing returns the file as it is,
// as a 1×1 PNG of the size the test names. A file named slow.png waits for
// release(), to act while it uploads.
let release = () => {}
vi.mock('../src/images/prepare', async (actual) => ({
  ...(await actual<typeof import('../src/images/prepare')>()),
  prepareImage: async (file: Blob) => {
    if (file instanceof File && file.name === 'slow.png') await new Promise<void>((r) => (release = r))
    return { bytes: new Uint8Array(await file.arrayBuffer()), type: 'image/png', w: 640, h: 480 }
  },
}))

/** Signed in with one note open; the test PNG is on the server unless asked otherwise. */
async function openNote(title: string, content: string, { upload = true, other = '' } = {}) {
  let id = ''
  const ctx = await signedIn({
    seed: async (s) => {
      if (upload) await s.bytes('PUT', `/images/${await sha256Hex(PNG)}`, { type: 'image/png', data: PNG })
      // Older, so the note opened is the one named.
      if (other) await createNote(s, other, 'Other\n')
      id = await createNote(s, title, content)
    },
  })
  await waitFor(() => expect(noteTitle()).toBe(title))
  return { ...ctx, id }
}

/** A clipboard's getData holding only plain text. */
const textOnly = (type: string) => (type === 'text/plain' ? 'pasted words' : '')

/** Locks the open note, creating the lock password; resolves once the server has it locked. */
async function lockOnServer(s: Awaited<ReturnType<typeof openNote>>) {
  await lockWithNewPassword(s.user)
  await waitFor(async () => expect((await getNote(s.server, s.id)).content.startsWith('notesd:locked:v1:')).toBe(true))
}

describe('images', () => {
  it('a picked picture is uploaded, shown and saved as a reference on its own line', async () => {
    const { server, id } = await openNote('Trip', 'Before\n\nAfter\n')
    selectInBody('Before')
    await pick(pngFile())

    const sha = await sha256Hex(PNG)
    await waitFor(() => expect(shown()).toHaveLength(1))
    expect(shown()[0]?.getAttribute('src')).toBe(`/api/v1/images/${sha}`)
    expect(shown()[0]?.width).toBe(640)
    await saved()
    await waitFor(async () =>
      expect((await getNote(server, id)).content).toBe(`Before\n\n![](notesd:image/${sha}?w=640&h=480)\n\nAfter\n`),
    )
    const stored = await server.bytes('GET', `/images/${sha}`)
    expect(stored.type).toBe('image/png')
    expect(stored.data).toEqual(PNG)
  })

  it('a pasted picture goes in at the cursor', async () => {
    const { server, id } = await openNote('Paste', 'Line\n')
    selectInBody('Line')
    const paste = new Event('paste', { bubbles: true, cancelable: true })
    Object.defineProperty(paste, 'clipboardData', { value: { files: [pngFile()], getData: () => '', types: ['Files'] } })
    body().dispatchEvent(paste)
    const sha = await sha256Hex(PNG)
    await waitFor(async () => expect((await getNote(server, id)).content).toBe(`Line\n\n![](notesd:image/${sha}?w=640&h=480)\n`))
  })

  it('pasted text that comes with a picture pastes as text', async () => {
    const { server, id } = await openNote('Mixed', 'Line\n')
    selectInBody('Line')
    const paste = new Event('paste', { bubbles: true, cancelable: true })
    Object.defineProperty(paste, 'clipboardData', { value: { files: [pngFile()], getData: textOnly, types: ['text/plain', 'Files'] } })
    body().dispatchEvent(paste)
    // Left to the editor's own paste, which takes the text.
    await waitFor(async () => expect((await getNote(server, id)).content).toContain('pasted words'))
    expect(imageRef((await getNote(server, id)).content)).toBeUndefined()
  })

  it('several pictures picked at once go in in the order picked', async () => {
    const { server, id } = await openNote('Many', 'Line\n')
    const other = new Uint8Array([...PNG, 1])
    selectInBody('Line')
    await pick(pngFile(), new File([other], 'two.png', { type: 'image/png' }))
    const [a, b] = [await sha256Hex(PNG), await sha256Hex(other)]
    await waitFor(async () =>
      expect((await getNote(server, id)).content).toBe(
        `Line\n\n![](notesd:image/${a}?w=640&h=480)\n\n![](notesd:image/${b}?w=640&h=480)\n`,
      ),
    )
  })

  it('a picture that finishes uploading after its note is closed goes at the end of it', async () => {
    const s = await openNote('First', 'Text\n', { other: 'Second' })
    selectInBody('Text')
    await pick(new File([PNG], 'slow.png', { type: 'image/png' }))
    await openInList(s.user, 'Second')
    release()
    const sha = await sha256Hex(PNG)
    await waitFor(async () => expect((await getNote(s.server, s.id)).content).toBe(`Text\n\n![](notesd:image/${sha}?w=640&h=480)\n`))
  })

  it('stored references render as pictures and survive other edits unchanged', async () => {
    const sha = await sha256Hex(PNG)
    const content = `![a dot](notesd:image/${sha}?w=640&h=480)\n\nText\n`
    const { server, user, id } = await openNote('Shown', content)
    expect(shown()[0]?.alt).toBe('a dot')
    expect(shown()[0]?.height).toBe(480)
    selectInBody('Text')
    await user.click(screen.getByRole('button', { name: 'Bold' }))
    await waitFor(async () => expect((await getNote(server, id)).content).toBe(`![a dot](notesd:image/${sha}?w=640&h=480)\n\n**Text**\n`))
  })

  it('a failed upload says so and inserts nothing', async () => {
    const { server, user, id } = await openNote('Offline', 'Text\n')
    server.setOffline(true)
    await pick(pngFile())
    expect(await screen.findByText(/picture couldn’t be uploaded/)).toBeTruthy()
    expect(shown()).toHaveLength(0)
    server.setOffline(false)
    await user.click(screen.getByRole('button', { name: 'Dismiss' }))
    await waitFor(() => expect(screen.queryByText(/picture couldn’t be uploaded/)).toBeNull())
    expect((await getNote(server, id)).content).toBe('Text\n')
  })

  it('locking encrypts the images too, and removing the lock brings the plain ones back', async () => {
    const plain = await sha256Hex(PNG)
    const s = await openNote('Secret', `![](notesd:image/${plain}?w=640&h=480)\n`)

    await lockOnServer(s)

    const key = await keyOf(s.server)
    const text = await decryptBody(key, (await getNote(s.server, s.id)).content)
    const sealed = imageRef(text)
    expect(sealed).toBeDefined()
    expect(sealed).not.toBe(plain)
    const blob = await s.server.bytes('GET', `/images/${sealed}`)
    expect(blob.type).toBe('application/vnd.notesd.locked')
    expect(await openImage(key, new Uint8Array(blob.data))).toEqual(PNG)
    // The server can't read the ciphertext, so the save lists the image, and only that one.
    const saves = s.server.sent('PUT', `/notes/${s.id}`) as { imageIds?: string[] }[]
    expect(saves.at(-1)?.imageIds).toEqual([sealed])
    // Shown decrypted, not from the server.
    await waitFor(() => expect(shown()[0]?.getAttribute('src')).toMatch(/^blob:/))

    await chooseLock(s.user, 'Remove lock')
    await waitFor(async () => expect((await getNote(s.server, s.id)).content).toBe(`![](notesd:image/${plain}?w=640&h=480)\n`))
    // A plain note's images are read from its content.
    expect((s.server.sent('PUT', `/notes/${s.id}`).at(-1) as { imageIds?: string[] }).imageIds).toBeUndefined()
  })

  it('a picture added to a locked note is uploaded encrypted', async () => {
    const s = await openNote('Locked', 'Text\n')
    await lockOnServer(s)

    selectInBody('Text')
    await pick(pngFile())
    const key = await keyOf(s.server)
    let sealed: string | undefined
    await waitFor(async () => {
      sealed = imageRef(await decryptBody(key, (await getNote(s.server, s.id)).content))
      expect(sealed).toBeDefined()
    })
    const blob = await s.server.bytes('GET', `/images/${sealed}`)
    expect(blob.type).toBe('application/vnd.notesd.locked')
    expect(await openImage(key, new Uint8Array(blob.data))).toEqual(PNG)
  })

  it('a lock change that can’t move the images is refused', async () => {
    const plain = await sha256Hex(PNG)
    // Never uploaded: the image can't be downloaded to encrypt it.
    const s = await openNote('Stuck', `![](notesd:image/${plain}?w=640&h=480)\n`, { upload: false })
    await chooseLock(s.user, 'Lock note')
    const dialog = await screen.findByRole('dialog', { name: 'Create a lock password' })
    await s.user.type(within(dialog).getByLabelText('Lock password'), LOCK_PASSWORD)
    await s.user.type(within(dialog).getByLabelText('Confirm'), LOCK_PASSWORD)
    await s.user.click(within(dialog).getByRole('button', { name: 'Create and lock' }))
    expect(await screen.findByText(/couldn’t be downloaded/)).toBeTruthy()
    expect((await getNote(s.server, s.id)).content).toBe(`![](notesd:image/${plain}?w=640&h=480)\n`)
    expect(screen.queryByRole('button', { name: 'Lock now' })).toBeNull()
  })
})
