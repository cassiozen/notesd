import { screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { sha256Hex } from '../src/images/ref'
import { decryptBody, openImage } from '../src/locks/crypto'
import { lockSession } from '../src/locks/session'
import { notesSource } from '../src/notes/source'
import { chooseLock, imageRef, keyOf, LOCK_PASSWORD, lockWithNewPassword, pick, PNG, pngFile, shown } from './image-helpers'
import { body, bodyText, createNote, getNote, noteTitle, openNote, saved, selectInBody, signedIn } from './helpers'

// Locked notes and pictures on this device's engine, with the server out of reach.

// jsdom can't decode pictures: preparing returns the file as it is, as a 640×480 PNG.
vi.mock('../src/images/prepare', async (actual) => ({
  ...(await actual<typeof import('../src/images/prepare')>()),
  prepareImage: async (file: Blob) => ({ bytes: new Uint8Array(await file.arrayBuffer()), type: 'image/png', w: 640, h: 480 }),
}))

type Session = Awaited<ReturnType<typeof signedIn>>

/** Locks the open note, creating the lock password. */
async function lockHere(s: Session) {
  await lockWithNewPassword(s.user)
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  await saved()
}

describe('locked notes and pictures on this device', () => {
  it('keeps a lock password made here, and unlocks with it offline', async () => {
    const s = await signedIn({ onDevice: true, seed: (sv) => createNote(sv, 'Bank', 'PIN 1234\n').then(() => undefined) })
    await waitFor(() => expect(noteTitle()).toBe('Bank'))
    await lockHere(s)
    // On this device before any sync brings it.
    await waitFor(async () => expect((await s.engine?.call('lockKeys'))?.map((k) => k.kind)).toEqual(['password']))

    await s.user.click(screen.getByRole('button', { name: 'Lock now' }))
    s.server.setOffline(true)
    // As a page opened offline: nothing of the keyring in memory.
    lockSession.forget()
    window.dispatchEvent(new Event('focus'))

    await s.user.type(await screen.findByLabelText('Lock password'), `${LOCK_PASSWORD}{Enter}`)
    await waitFor(() => expect(bodyText()).toBe('PIN 1234'))
  })

  it('offline with no keyring kept here yet, doesn’t take it for none', async () => {
    const s = await signedIn({ onDevice: true })
    s.server.setOffline(true)
    await expect(notesSource().keyring.load()).rejects.toThrow('No connection to the server')
  })

  it('adds a picture offline; sync uploads it with the note', async () => {
    const s = await signedIn({ onDevice: true, seed: (sv) => createNote(sv, 'Trip', 'Text\n').then(() => undefined) })
    await waitFor(() => expect(noteTitle()).toBe('Trip'))
    s.server.setOffline(true)

    selectInBody('Text')
    await pick(pngFile())
    await waitFor(() => expect(shown()[0]?.getAttribute('src')).toMatch(/^blob:/))
    await saved()

    const id = await sha256Hex(PNG)
    s.server.setOffline(false)
    expect(await s.engine?.call('sync')).toBe('Done')
    expect(await s.server.bytes('GET', `/images/${id}`)).toMatchObject({ status: 200, data: PNG })
    const notes = await s.server.api<{ notes: { id: string }[] }>('GET', '/notes')
    expect(imageRef((await getNote(s.server, notes.body.notes[0]?.id ?? '')).content)).toBe(id)
  })

  it('shows a picture seen once, offline', async () => {
    const id = await sha256Hex(PNG)
    const s = await signedIn({
      onDevice: true,
      seed: async (sv) => {
        await sv.bytes('PUT', `/images/${id}`, { type: 'image/png', data: PNG })
        await createNote(sv, 'Other', 'Words\n')
        await createNote(sv, 'Trip', `![](notesd:image/${id}?w=640&h=480)\n`)
      },
    })
    // Seeded within the same millisecond, either may be the one open.
    await openNote(s.user, 'Trip')
    await waitFor(() => expect(shown()[0]?.getAttribute('src')).toMatch(/^blob:/))

    s.server.setOffline(true)
    await openNote(s.user, 'Other')
    await openNote(s.user, 'Trip')

    await waitFor(() => expect(shown()[0]?.getAttribute('src')).toMatch(/^blob:/))
    expect(body().querySelector('[data-state="missing"]')).toBeNull()
  })

  it('locks a note with pictures offline, encrypting the pictures on this device', async () => {
    const plain = await sha256Hex(PNG)
    const s = await signedIn({
      onDevice: true,
      seed: async (sv) => {
        await sv.bytes('PUT', `/images/${plain}`, { type: 'image/png', data: PNG })
        await createNote(sv, 'Trip', `![](notesd:image/${plain}?w=640&h=480)\n`)
        await createNote(sv, 'Bank', 'PIN 1234\n')
      },
    })
    await openNote(s.user, 'Bank')
    await lockHere(s)
    await openNote(s.user, 'Trip')
    await waitFor(() => expect(shown()[0]?.getAttribute('src')).toMatch(/^blob:/))

    s.server.setOffline(true)
    await chooseLock(s.user, 'Lock note')
    await saved()

    s.server.setOffline(false)
    expect(await s.engine?.call('sync')).toBe('Done')
    const trip = (await s.server.api<{ notes: { id: string; title: string }[] }>('GET', '/notes')).body.notes.find(
      (n) => n.title === 'Trip',
    )
    const key = await keyOf(s.server)
    const sealed = imageRef(await decryptBody(key, (await getNote(s.server, trip?.id ?? '')).content))
    expect(sealed).toBeDefined()
    expect(sealed).not.toBe(plain)
    const blob = await s.server.bytes('GET', `/images/${sealed}`)
    expect(blob.type).toBe('application/vnd.notesd.locked')
    expect(await openImage(key, new Uint8Array(blob.data))).toEqual(PNG)
  })
})
