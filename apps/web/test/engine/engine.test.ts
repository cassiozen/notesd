import { describe, expect, test } from 'vitest'
import type { Note, Space } from '../../src/api/types'
import { sha256Hex } from '../../src/images/ref'
import { fromBase64Url } from '../../src/locks/crypto'
import { syncTiming } from '../../src/offline/scheduler'
import { op } from '../helpers'
import { engineSignedIn, inProcessEngine, signIn, twoAccounts, waitFor } from './helpers'

const note = (id: string, title: string, content = '') => ({ id, title, content, spaceId: 'default' })
const inWork = (id: string, title: string, content = '') => ({ id, title, content, spaceId: 'work' })
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
        updatedBy: null,
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

  test('both sides changed different paragraphs: merged, and Undo splits them again', async () => {
    const { server, engine } = await engineSignedIn()
    const id = crypto.randomUUID()
    await engine.call('save', note(id, 'Plan', 'One\n\nTwo\n'))
    await engine.call('sync')
    await server.api('PUT', `/notes/${id}`, { operationId: op(), baseRevision: 1, title: 'Plan', content: 'One, elsewhere\n\nTwo\n' })

    await engine.call('save', note(id, 'Plan', 'One\n\nTwo, here\n'))
    await engine.call('sync')

    const notice = engine.events.find((e) => e.type === 'notice')
    expect(notice).toMatchObject({ notice: { kind: 'Merged', noteId: id, title: 'Plan' } })
    expect(await engine.call('get', id)).toMatchObject({ content: 'One, elsewhere\n\nTwo, here\n' })
    expect(await engine.call('mergedAt', id)).toEqual(expect.any(Number))

    const copy = await engine.call('undoMerge', id)
    expect(await engine.call('mergedAt', id)).toBeNull()
    await engine.call('sync')
    expect((await server.api<Note>('GET', `/notes/${id}`)).body).toMatchObject({ content: 'One, elsewhere\n\nTwo\n' })
    expect((await server.api<Note>('GET', `/notes/${copy}`)).body).toMatchObject({
      title: 'Plan (Conflicted copy)',
      content: 'One\n\nTwo, here\n',
      conflictOf: id,
    })
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
    // None until the server registers a trait.
    expect((await engine.call('spaces')).map((s) => s.traits)).toEqual([[], []])
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

  test('without a session token yet, writes wait instead of being refused', async () => {
    const { server, engine } = await engineSignedIn()
    const token = await signIn('me', 'correct horse')
    engine.setCsrfToken('')
    const id = crypto.randomUUID()
    await engine.call('save', note(id, 'Plan', 'Offline\n'))

    expect(await engine.call('sync')).toBe('Offline')
    expect(await engine.call('pendingNotes')).toBe(1)

    engine.setCsrfToken(token)
    expect(await engine.call('sync')).toBe('Done')
    expect((await server.api<Note>('GET', `/notes/${id}`)).body.content).toBe('Offline\n')
  })

  test('a stale session token pauses sync for a new sign-in, keeping the write', async () => {
    const { engine } = await engineSignedIn()
    engine.setCsrfToken('stale')
    await engine.call('save', note(crypto.randomUUID(), 'Plan', 'Waits\n'))

    expect(await engine.call('sync')).toBe('SignedOut')
    expect(await engine.call('pendingNotes')).toBe(1)
  })
})

describe('shared spaces', () => {
  type Setup = Awaited<ReturnType<typeof twoAccounts>>

  /** The owner's Work shared with Ana, who joins unless told not to. */
  async function shareWork({ server, ana }: Setup, { join = true } = {}) {
    expect((await server.api('POST', '/spaces/work/invites', { userId: ana.id })).status).toBe(201)
    if (!join) return
    const [invite] = (await ana.api<{ invites: { id: string }[] }>('GET', '/invites')).body.invites
    expect((await ana.api('POST', `/invites/${invite?.id}/accept`)).status).toBe(200)
  }

  /** The owner's space Work, with a note per title, made on another device. */
  async function workWithNotes(s: Setup, ...titles: string[]) {
    await s.server.api('PUT', '/spaces/work', { name: 'Work' })
    const ids: string[] = []
    for (const title of titles) {
      const id = crypto.randomUUID()
      ids.push(id)
      // oxlint-disable-next-line no-await-in-loop
      expect((await s.server.api('POST', '/notes', { operationId: op(), id, title, content: '', spaceId: 'work' })).status).toBe(201)
    }
    return ids
  }

  /** Cycles until nothing waits: a refiled change goes out with its new feed's next upload. */
  const syncedAll = (engine: Setup['engine']) =>
    waitFor(async () => (await engine.call('sync')) === 'Done' && (await engine.call('pendingNotes')) === 0)

  test('sharing a space this device has: its notes follow, none lost or doubled', async () => {
    const s = await twoAccounts()
    const [a = '', b = ''] = await workWithNotes(s, 'Plan', 'Budget')
    await s.engine.call('sync')
    expect((await s.engine.call('list', null, 'work')).map((n) => n.id).toSorted()).toEqual([a, b].toSorted())

    await shareWork(s)
    expect(await s.engine.call('sync')).toBe('Done')

    expect((await s.engine.call('list', null, 'work')).map((n) => n.id).toSorted()).toEqual([a, b].toSorted())
    expect(await s.engine.call('list', null, null)).toHaveLength(2)
    expect(await s.engine.call('pendingNotes')).toBe(0)
    const work = (await s.engine.call('spaces')).find((sp) => sp.id === 'work')
    expect(work?.shared).toEqual({
      role: 'owner',
      members: [
        { id: s.me, username: 'me' },
        { id: s.ana.id, username: 'ana' },
      ],
      invites: [],
    })
    expect(work?.traits.map((t) => t.key)).toEqual(['shared'])

    // Edited here after: it goes to the space's own file, where Ana reads it.
    await s.engine.call('save', { ...inWork(a, 'Plan', 'Monday\n'), basedOn: { title: 'Plan', content: '' } })
    await syncedAll(s.engine)
    expect((await s.ana.api<Note>('GET', `/notes/${a}`)).body).toMatchObject({ content: 'Monday\n', updatedBy: s.me })
  })

  test('another member’s edit says who made it, and is news until the space is shown', async () => {
    const s = await twoAccounts()
    const [id = ''] = await workWithNotes(s, 'Plan')
    await shareWork(s)
    await s.engine.call('sync')
    expect(await s.engine.call('news')).toEqual([])

    const before = (await s.ana.api<Note>('GET', `/notes/${id}`)).body
    const edit = { operationId: op(), baseRevision: before.revision, title: 'Plan', content: 'Ana was here\n' }
    expect((await s.ana.api('PUT', `/notes/${id}`, edit)).status).toBe(200)
    await s.engine.call('sync')

    expect(await s.engine.call('get', id)).toMatchObject({ content: 'Ana was here\n', updatedBy: s.ana.id })
    expect(await s.engine.call('list', null, 'work')).toMatchObject([{ id, updatedBy: s.ana.id }])
    expect(await s.engine.call('news')).toEqual([{ spaceId: 'work', count: 1, usernames: ['ana'], latestAt: expect.any(Number) }])

    await s.engine.call('visit', 'work')
    expect(await s.engine.call('news')).toEqual([])
  })

  test('changes queued before the space was shared elsewhere land in its file', async () => {
    const s = await twoAccounts()
    const [id = ''] = await workWithNotes(s, 'Plan')
    await s.engine.call('sync')
    // Not yet sent: an edit to a note there, and a note new there.
    await s.engine.call('save', { ...inWork(id, 'Plan', 'Edited offline\n'), basedOn: { title: 'Plan', content: '' } })
    const fresh = crypto.randomUUID()
    await s.engine.call('save', inWork(fresh, 'Written offline'))

    // Shared from another device meanwhile: its notes moved to the space's own file.
    await shareWork(s)
    await syncedAll(s.engine)

    expect((await s.ana.api<Note>('GET', `/notes/${id}`)).body.content).toBe('Edited offline\n')
    expect((await s.ana.api<Note>('GET', `/notes/${fresh}`)).body.title).toBe('Written offline')
    expect((await s.engine.call('list', null, 'work')).map((n) => n.id).toSorted()).toEqual([id, fresh].toSorted())
    // The owner, at the same address, reads them in the space's file.
    expect((await s.server.api<{ notes: unknown[] }>('GET', '/notes?space=work')).body.notes).toHaveLength(2)
  })

  test('a note moved out of the shared space leaves it for everyone else', async () => {
    const s = await twoAccounts()
    const [id = ''] = await workWithNotes(s, 'Private')
    await shareWork(s)
    await s.engine.call('sync')

    expect(await s.engine.call('move', id, 'default')).toBe(true)
    await syncedAll(s.engine)

    expect((await s.server.api<Note>('GET', `/notes/${id}`)).body).toMatchObject({ title: 'Private', spaceId: 'default' })
    expect((await s.ana.api('GET', `/notes/${id}`)).status).toBe(410)
    expect(await s.engine.call('list', null, 'work')).toEqual([])
    expect((await s.engine.call('list', null, 'default')).map((n) => n.id)).toEqual([id])
  })

  test('leaving takes the space’s notes off the device; an unsynced edit stays, as a copy in the default space', async () => {
    const s = await twoAccounts('ana')
    const [id = ''] = await workWithNotes(s, 'Plan')
    await shareWork(s)
    await s.engine.call('sync')
    expect((await s.engine.call('list', null, 'work')).map((n) => n.id)).toEqual([id])
    expect((await s.engine.call('spaces')).find((sp) => sp.id === 'work')?.shared?.role).toBe('member')

    await s.engine.call('save', { ...inWork(id, 'Plan', 'Not sent\n'), basedOn: { title: 'Plan', content: '' } })
    expect(await s.engine.call('unsynced', 'work')).toEqual([id])

    expect((await s.ana.api('DELETE', `/spaces/work/members/${s.ana.id}`)).status).toBe(204)
    expect(await s.engine.call('sync')).toBe('Done')

    expect((await s.engine.call('spaces')).map((sp) => sp.id)).toEqual(['default'])
    expect(await s.engine.call('get', id)).toBeNull()
    const kept = await s.engine.call('list', null, null)
    expect(kept.map((n) => [n.title, n.spaceId])).toEqual([['Plan (Recovered copy)', 'default']])
    // Sent in the same cycle, to this account's own file.
    expect(await s.engine.call('pendingNotes')).toBe(0)
    expect((await s.ana.api<Note>('GET', `/notes/${kept[0]?.id}`)).body).toMatchObject({ content: 'Not sent\n', spaceId: 'default' })
  })

  test('an invitation waits on the device until joined; then the space’s notes arrive', async () => {
    const s = await twoAccounts('ana')
    const [id = ''] = await workWithNotes(s, 'Plan')
    await shareWork(s, { join: false })
    await s.engine.call('sync')
    const invite = { spaceId: 'work', spaceName: 'Work', spaceIcon: '', from: { id: s.me, username: 'me' } }
    expect(await s.engine.call('invites')).toEqual([{ ...invite, id: expect.any(String), createdAt: expect.any(Number) }])

    const [waiting] = await s.engine.call('invites')
    expect((await s.ana.api('POST', `/invites/${waiting?.id}/accept`)).status).toBe(200)
    await s.engine.call('sync')

    expect(await s.engine.call('invites')).toEqual([])
    expect((await s.engine.call('list', null, 'work')).map((n) => n.id)).toEqual([id])
  })

  test('a picture added in a shared note shows for the other member', async () => {
    const s = await twoAccounts()
    await workWithNotes(s)
    await shareWork(s)
    await s.engine.call('sync')
    const image = await sha256Hex(PNG)
    await s.engine.call('addImage', image, PNG, 'image/png')
    const id = crypto.randomUUID()
    await s.engine.call('save', inWork(id, 'Photo', `![](notesd:image/${image}?w=1&h=1)\n`))

    await syncedAll(s.engine)

    // Uploaded to this account's address; the note's write carried it into the space's file, where Ana finds it.
    expect(await s.ana.bytes('GET', `/images/${image}`)).toMatchObject({ status: 200, type: 'image/png', data: PNG })
  })

  test('a picture already uploaded for a private note, added again in a shared one, shows for the other member', async () => {
    const s = await twoAccounts()
    await workWithNotes(s)
    await shareWork(s)
    await s.engine.call('sync')
    const image = await sha256Hex(PNG)
    await s.engine.call('addImage', image, PNG, 'image/png')
    await s.engine.call('save', { id: crypto.randomUUID(), title: 'Mine', content: `![](notesd:image/${image}?w=1&h=1)\n`, spaceId: 'default' })
    await syncedAll(s.engine)
    expect((await s.ana.bytes('GET', `/images/${image}`)).status).toBe(404)

    await s.engine.call('addImage', image, PNG, 'image/png')
    await s.engine.call('save', inWork(crypto.randomUUID(), 'Photo', `![](notesd:image/${image}?w=1&h=1)\n`))
    await syncedAll(s.engine)

    expect(await s.ana.bytes('GET', `/images/${image}`)).toMatchObject({ status: 200, data: PNG })
  })
})

async function file(image: Promise<Blob | null>) {
  const blob = await image
  return blob && { bytes: new Uint8Array(await blob.arrayBuffer()), type: blob.type }
}
