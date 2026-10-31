import { describe, expect, test } from 'vitest'
import type { Note, Space } from '../../src/api/types'
import { sha256Hex } from '../../src/images/ref'
import { fromBase64Url } from '../../src/locks/crypto'
import { syncTiming } from '../../src/offline/scheduler'
import { op } from '../helpers'
import { engineSignedIn, inProcessEngine, signIn, waitFor } from './helpers'

const note = (id: string, title: string, content = '') => ({ id, title, content, spaceId: 'default' })
const PNG = fromBase64Url('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH_iZk9HQAAAABJRU5ErkJggg')

describe('engine', () => {
  test('saves on this device, then sync sends it', async () => {
    const { server, engine } = await engineSignedIn()
    const id = crypto.randomUUID()

    const saved = await engine.call('save', note(id, 'Shopping', 'Buy milk\n'))
    expect(saved).toMatchObject({ id, title: 'Shopping', dirty: true, revision: null })
    expect(await engine.call('pendingNotes')).toBe(1)
    expect(saved?.createdAt).toEqual(expect.any(Number))
    expect(await engine.call('list', null, null)).toEqual([
      {
        id,
        title: 'Shopping',
        preview: 'Buy milk',
        createdAt: saved?.createdAt,
        updatedAt: saved?.updatedAt,
        locked: false,
        revision: null,
        spaceId: 'default',
      },
    ])

    expect(await engine.call('sync')).toBe('Done')
    expect((await server.api<Note>('GET', `/notes/${id}`)).body).toMatchObject({ title: 'Shopping', content: 'Buy milk\n' })
    expect(await engine.call('get', id)).toMatchObject({ dirty: false, revision: 1 })
    expect(await engine.call('list', null, null)).toMatchObject([{ id, revision: 1 }])
    expect(await engine.call('pendingNotes')).toBe(0)
  })

  test('brings in notes made elsewhere: listed, tagged, searchable', async () => {
    const { server, engine } = await engineSignedIn()
    const id = crypto.randomUUID()
    await server.api('POST', '/notes', { operationId: op(), id, title: 'Garden', content: 'Sow <basil> in May #garden/herbs\n' })

    await engine.call('sync')

    expect((await engine.call('list', null, null)).map((n) => n.title)).toEqual(['Garden'])
    expect(await engine.call('tags', null)).toEqual([
      { key: 'garden', name: 'garden', count: 1 },
      { key: 'garden/herbs', name: 'garden/herbs', count: 1 },
    ])
    expect((await engine.call('list', { tag: 'garden' }, 'default')).map((n) => n.id)).toEqual([id])
    expect(await engine.call('list', { untagged: true }, null)).toEqual([])
    const [hit] = await engine.call('search', 'basil', null, { space: 'default' }, 0, 30)
    // HTML, as the server's search answers.
    expect(hit?.excerpt).toContain('&lt;<mark>basil</mark>&gt;')
    expect(await engine.call('countElsewhere', 'basil', 'default')).toBe(0)
  })

  test('both sides changed the text: the server keeps the note, this side gets a conflicted copy', async () => {
    const { server, engine } = await engineSignedIn()
    const id = crypto.randomUUID()
    await engine.call('save', note(id, 'Plan', 'Monday\n'))
    await engine.call('sync')
    await server.api('PUT', `/notes/${id}`, { operationId: op(), baseRevision: 1, title: 'Plan', content: 'Tuesday\n' })

    await engine.call('save', note(id, 'Plan', 'Wednesday\n'))
    await engine.call('sync')

    const notice = engine.events.find((e) => e.type === 'notice')
    expect(notice).toMatchObject({ notice: { kind: 'ConflictedCopy', noteId: id, title: 'Plan (Conflicted copy)' } })
    expect(await engine.call('get', id)).toMatchObject({ content: 'Tuesday\n' })
    const copy = notice?.type === 'notice' && notice.notice.kind === 'ConflictedCopy' ? notice.notice.copyId : ''
    expect((await server.api<Note>('GET', `/notes/${copy}`)).body).toMatchObject({ content: 'Wednesday\n', conflictOf: id })
  })

  test('syncs by itself after an edit, and keeps retrying while offline', async () => {
    const { server, engine } = await engineSignedIn()
    Object.assign(syncTiming, { afterEditMs: 50, firstRetryMs: 50, maxRetryMs: 100 })
    server.setOffline(true)
    const id = crypto.randomUUID()

    await engine.call('save', note(id, 'Offline'))
    await waitFor(async () => (await engine.call('status')).kind === 'Offline')
    server.setOffline(false)

    await waitFor(async () => (await server.api('GET', `/notes/${id}`)).status === 200)
    await waitFor(async () => (await engine.call('pendingNotes')) === 0)
    expect(engine.events.some((e) => e.type === 'status' && e.status.kind === 'Offline')).toBe(true)
  })

  test('signed out, sync waits for the next sign-in and its token', async () => {
    const { server, engine, password } = await engineSignedIn()
    const id = crypto.randomUUID()
    const csrf = await signIn('me', password)
    // Without the token the server refuses the logout; the engine's must be the new session's too.
    await fetch('/api/v1/auth/logout', { method: 'POST', headers: { 'X-CSRF-Token': csrf } })
    await engine.call('save', note(id, 'Later'))

    expect(await engine.call('sync')).toBe('SignedOut')
    expect(await engine.call('status')).toEqual({ kind: 'SignedOut' })

    engine.setCsrfToken(await signIn('me', password))
    await waitFor(async () => (await server.api('GET', `/notes/${id}`)).status === 200)
  })

  test('spaces: made here, a note moved in, both sent; creating an id again changes nothing', async () => {
    const { server, engine } = await engineSignedIn()
    const id = crypto.randomUUID()
    const space = crypto.randomUUID()
    await engine.call('save', note(id, 'Invoice'))

    await engine.call('createSpace', space, 'Work', 'briefcase')
    await engine.call('createSpace', space, 'Work', 'briefcase')
    expect(await engine.call('move', id, space)).toBe(true)
    await engine.call('sync')

    expect((await engine.call('spaces')).map((s) => [s.name, s.noteCount])).toEqual([
      ['Notes', 0],
      ['Work', 1],
    ])
    const remote = (await server.api<{ spaces: Space[] }>('GET', '/spaces')).body.spaces
    expect(remote.filter((s) => s.name === 'Work')).toEqual([expect.objectContaining({ id: space, icon: 'briefcase' })])
    expect((await server.api<Note>('GET', `/notes/${id}`)).body.spaceId).toBe(space)
    expect(await engine.call('deleteSpace', space)).toBe(false)
  })

  test('recovering again with the same id makes no second note', async () => {
    const { engine } = await engineSignedIn()
    const id = crypto.randomUUID()
    await engine.call('recover', id, 'Plan', 'Monday\n', null, 'default')
    await engine.call('recover', id, 'Plan', 'Monday\n', null, 'default')
    expect((await engine.call('list', null, null)).map((n) => n.title)).toEqual(['Plan (Recovered copy)'])
  })

  test('a call the engine refuses rejects with its message', async () => {
    const { engine } = await engineSignedIn()
    await expect(engine.call('createSpace', crypto.randomUUID(), '  ', '')).rejects.toThrow('A space needs a name')
  })

  test('the keyring is kept as given', async () => {
    const { engine } = await engineSignedIn()
    const keys = [
      { id: 'k1', kind: 'password' as const, salt: 's', iterations: 10_000, wrapped: 'w', createdAt: 5 },
      { id: 'k2', kind: 'passkey' as const, credentialId: 'c', salt: 's2', iterations: 0, wrapped: 'w2', createdAt: 6 },
    ]
    await engine.call('replaceLockKeys', keys)
    expect(await engine.call('lockKeys')).toEqual(keys)
  })

  test('a database no sync has brought notes to says it never synced', async () => {
    const { server } = await engineSignedIn()
    server.setOffline(true)
    const engine = inProcessEngine('elsewhere', '')
    expect(await engine.call('sync')).toBe('Offline')
    expect(await engine.call('lastSyncAt')).toBeNull()
  })

  test('a picture added here is kept, and sync uploads it once it can', async () => {
    const { server, engine } = await engineSignedIn()
    const id = await sha256Hex(PNG)
    await engine.call('addImage', id, PNG, 'image/png')
    expect(await file(engine.call('image', id))).toEqual({ bytes: PNG, type: 'image/png' })

    server.setOffline(true)
    expect(await engine.call('sync')).toBe('Offline')
    server.setOffline(false)
    expect((await server.bytes('GET', `/images/${id}`)).status).toBe(404)

    expect(await engine.call('sync')).toBe('Done')
    expect(await server.bytes('GET', `/images/${id}`)).toMatchObject({ status: 200, type: 'image/png', data: PNG })
  })

  test('a picture the server refuses for good doesn’t hold notes back', async () => {
    const { server, engine } = await engineSignedIn()
    // Not the bytes' hash: hash_mismatch, every time.
    const wrong = await sha256Hex(new Uint8Array([1, 2, 3]))
    await engine.call('addImage', wrong, PNG, 'image/png')
    const id = crypto.randomUUID()
    await engine.call('save', note(id, 'Trip', `![](notesd:image/${wrong}?w=1&h=1)\n`))

    expect(await engine.call('sync')).toBe('Done')
    expect((await server.api('GET', `/notes/${id}`)).status).toBe(200)
    expect((await server.bytes('GET', `/images/${wrong}`)).status).toBe(404)
  })

  test('a picture is downloaded once, then shows offline', async () => {
    const { server, engine } = await engineSignedIn()
    const id = await sha256Hex(PNG)
    await server.bytes('PUT', `/images/${id}`, { type: 'image/png', data: PNG })

    expect(await file(engine.call('image', id))).toEqual({ bytes: PNG, type: 'image/png' })
    server.setOffline(true)
    expect(await file(engine.call('image', id))).toEqual({ bytes: PNG, type: 'image/png' })
    server.setOffline(false)
    expect(await engine.call('image', await sha256Hex(new Uint8Array([9])))).toBeNull()
  })

  test('wiping forgets the pictures too', async () => {
    const { server, engine } = await engineSignedIn()
    const id = await sha256Hex(PNG)
    await engine.call('addImage', id, PNG, 'image/png')

    await engine.call('wipe')

    server.setOffline(true)
    await expect(engine.call('image', id)).rejects.toThrow()
    server.setOffline(false)
    expect(await engine.call('sync')).toBe('Done')
    expect((await server.bytes('GET', `/images/${id}`)).status).toBe(404)
  })
})

async function file(image: Promise<Blob | null>) {
  const blob = await image
  return blob && { bytes: new Uint8Array(await blob.arrayBuffer()), type: blob.type }
}
