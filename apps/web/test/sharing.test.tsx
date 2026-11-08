import { act, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { Note, Space } from '../src/api/types'
import { sha256Hex } from '../src/images/ref'
import { encryptBody } from '../src/locks/crypto'
import { reloadSharing } from '../src/notes/sharing'
import { reloadSpaces } from '../src/notes/spaces'
import { pick, PNG, pngFile, shown } from './image-helpers'
import { list, noteTitle, op, openNote, saved, signedIn, typeAtEnd } from './helpers'
import type { Client, TestServer } from './server'

// Shared spaces (architecture §7.9) on the screens, against a real server with two accounts: the owner "me" and
// Ana. The browser signs in as either.

vi.mock('../src/images/prepare', async (actual) => ({
  ...(await actual<typeof import('../src/images/prepare')>()),
  prepareImage: async (file: Blob) => ({ bytes: new Uint8Array(await file.arrayBuffer()), type: 'image/png', w: 640, h: 480 }),
}))

type Ana = Client & { id: number }

const panel = () => screen.getByRole('navigation', { name: 'Sidebar' })
const switcher = () => within(panel()).getByRole('button', { name: /^Space: / })
const popover = () => screen.getByRole('dialog', { name: 'Spaces' })
const spaceRow = (name: string) => within(popover()).getByRole('button', { name: new RegExp(`^${name}(,|$)`) })
const rowTitles = () =>
  within(list())
    .queryAllByRole('link')
    .map((a) => a.querySelector('span')?.textContent)

type User = Awaited<ReturnType<typeof signedIn>>['user']

async function switchTo(user: User, name: string) {
  await user.click(await waitFor(() => switcher()))
  await user.click(spaceRow(name))
}

/** Space info for the space shown, from the switcher's card. */
async function spaceInfo(user: User, name: string) {
  await user.click(switcher())
  await user.click(within(popover()).getByRole('button', { name: 'Space info' }))
  return screen.findByRole('dialog', { name })
}

async function noteIn(server: Client, space: string, title: string, content = '', id: string = crypto.randomUUID()) {
  const res = await server.api('POST', '/notes', { operationId: op(), id, title, content, spaceId: space })
  expect(res.status).toBe(201)
  return id
}

/** The owner's Work, with a note ([noteId]), and Ana's account; shared with her (and joined) when asked. */
async function seedWork(
  server: TestServer,
  { share = false, join = share, noteId: id }: { share?: boolean; join?: boolean; noteId?: string } = {},
): Promise<{ ana: Ana; noteId: string }> {
  await server.api('PUT', '/spaces/work', { name: 'Work' })
  const noteId = await noteIn(server, 'work', 'Standup', 'Status\n', id)
  const ana = await server.addAccount('ana')
  if (share) expect((await server.api('POST', '/spaces/work/invites', { userId: ana.id })).status).toBe(201)
  if (join) {
    const [invite] = (await ana.api<{ invites: { id: string }[] }>('GET', '/invites')).body.invites
    expect((await ana.api('POST', `/invites/${invite?.id}/accept`)).status).toBe(200)
  }
  return { ana, noteId }
}

async function remoteSpaces(server: Client) {
  return (await server.api<{ spaces: Space[] }>('GET', '/spaces')).body.spaces
}

/** Ana changes a note in Work, as her own device would. */
async function anaEdits(ana: Ana, id: string, content: string) {
  const note = (await ana.api<Note>('GET', `/notes/${id}`)).body
  const res = await ana.api('PUT', `/notes/${id}`, { operationId: op(), baseRevision: note.revision, title: note.title, content })
  expect(res.status).toBe(200)
}

describe('sharing a space', () => {
  it('shares from Space info: the first person pressed is invited, in place, and its notes stay in the list', async () => {
    let ana: Ana | undefined
    const { server, user } = await signedIn({ seed: async (s) => void ({ ana } = await seedWork(s)) })
    await act(() => reloadSpaces())
    await switchTo(user, 'Work')
    await waitFor(() => expect(rowTitles()).toEqual(['Standup']))

    const info = await spaceInfo(user, 'Work')
    expect(within(info).getByText('Invite people to read and edit every note in Work, and add their own.')).toBeTruthy()
    // Everyone but the account itself.
    const people = await within(info).findByRole('list', { name: 'People to invite' })
    expect(within(people).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual(['Invite ana'])
    await user.click(within(people).getByRole('button', { name: 'Invite ana' }))

    // Lands on the same page: nothing opened over it.
    const inSpace = await within(info).findByRole('list', { name: 'People in this space' })
    expect(within(inSpace).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['MmeOwner · You', 'AanaInvitedCancel'])
    expect(screen.getAllByRole('dialog')).toHaveLength(1)
    expect(within(info).getByRole('img', { name: 'Shared' })).toBeTruthy()
    expect(within(info).queryByRole('list', { name: 'People to invite' })).toBeNull()
    const work = (await remoteSpaces(server)).find((s) => s.id === 'work')
    expect(work?.shared?.invites?.map((i) => i.userId)).toEqual([ana?.id])
    // Its notes moved to the space's own file, where the list now reads them.
    await waitFor(() => expect(rowTitles()).toEqual(['Standup']))
  })

  it('says why a space with locked notes can’t be shared', async () => {
    const { user } = await signedIn({
      seed: async (s) => {
        await seedWork(s)
        // Locked with a key of its own: nothing here needs to open it.
        const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
        const content = await encryptBody(key, 'Dear diary\n')
        const locked = { operationId: op(), id: crypto.randomUUID(), title: 'Diary', content, spaceId: 'work' }
        const res = await s.api('POST', '/notes', locked)
        expect(res).toMatchObject({ status: 201 })
      },
    })
    await act(() => reloadSpaces())
    await switchTo(user, 'Work')
    const info = await spaceInfo(user, 'Work')
    await user.click(await within(info).findByRole('button', { name: 'Invite ana' }))
    expect((await within(info).findByRole('alert')).textContent).toBe('Unlock its locked note to share it.')
  })

  it('lists who’s in it; the owner cancels an invitation and removes someone, confirmed in place', async () => {
    let ana: Ana | undefined
    const { server, user } = await signedIn({
      seed: async (s) => {
        ;({ ana } = await seedWork(s, { share: true }))
        const ben = await s.addAccount('ben')
        await s.api('POST', '/spaces/work/invites', { userId: ben.id })
      },
    })
    await act(() => reloadSpaces())
    await switchTo(user, 'Work')
    const info = await spaceInfo(user, 'Work')
    const people = within(info).getByRole('list', { name: 'People in this space' })
    expect(within(people).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'MmeOwner · You',
      'AanaMake ownerRemove',
      'BbenInvitedCancel',
    ])

    await user.click(within(info).getByRole('button', { name: 'Cancel ben’s invitation' }))
    await waitFor(() => expect(within(people).queryByText('ben')).toBeNull())
    // Back among the people to invite.
    expect(within(info).getByRole('button', { name: 'Invite ben' })).toBeTruthy()

    await user.click(within(info).getByRole('button', { name: 'Remove ana' }))
    const confirm = within(info).getByRole('group', { name: 'Remove ana?' })
    expect(document.activeElement?.textContent).toBe('Cancel')
    // Escape closes the confirmation, not the page.
    await user.keyboard('{Escape}')
    expect(within(info).queryByRole('group', { name: 'Remove ana?' })).toBeNull()
    expect(document.activeElement?.getAttribute('aria-label')).toBe('Remove ana')
    await user.click(within(info).getByRole('button', { name: 'Remove ana' }))
    await user.click(within(within(info).getByRole('group', { name: 'Remove ana?' })).getByRole('button', { name: 'Remove' }))
    expect(confirm.isConnected).toBe(false)
    await waitFor(() => expect(within(info).queryByRole('list', { name: 'People in this space' })).toBeNull())
    expect(screen.getAllByRole('dialog')).toHaveLength(1)
    const work = (await remoteSpaces(server)).find((s) => s.id === 'work')
    expect(work?.shared?.members.map((m) => m.username)).toEqual(['me'])
    expect(work?.traits).toEqual([])
    expect((await remoteSpaces(ana as Ana)).map((s) => s.id)).toEqual(['default'])
  })

  it('a failed change says why, and the page shows what the server has now', async () => {
    const { server, user } = await signedIn({
      seed: async (s) => {
        await seedWork(s, { share: true })
        const ben = await s.addAccount('ben')
        await s.api('POST', '/spaces/work/invites', { userId: ben.id })
      },
    })
    await act(() => reloadSpaces())
    await switchTo(user, 'Work')
    const info = await spaceInfo(user, 'Work')
    // Cancelled on another device, after this page listed it.
    const [invite] = (await remoteSpaces(server)).find((s) => s.id === 'work')?.shared?.invites ?? []
    expect((await server.api('DELETE', `/spaces/work/invites/${invite?.id}`)).status).toBe(204)

    await user.click(within(info).getByRole('button', { name: 'Cancel ben’s invitation' }))
    expect((await within(info).findByRole('alert')).textContent).toBe('That’s no longer there. It may have changed on another device.')
    const people = within(info).getByRole('list', { name: 'People in this space' })
    await waitFor(() => expect(within(people).queryByText('ben')).toBeNull())
  })

  it('hands the space on: the new owner’s, and this account a member', async () => {
    let ana: Ana | undefined
    const { server, user } = await signedIn({ seed: async (s) => void ({ ana } = await seedWork(s, { share: true })) })
    await act(() => reloadSpaces())
    await switchTo(user, 'Work')
    const info = await spaceInfo(user, 'Work')
    await user.click(within(info).getByRole('button', { name: 'Make owner: ana' }))
    const confirm = within(info).getByRole('group', { name: 'Make ana the owner?' })
    await user.click(within(confirm).getByRole('button', { name: 'Make owner' }))

    await waitFor(() => expect(within(info).getByRole('button', { name: 'Leave space…' })).toBeTruthy())
    expect((await remoteSpaces(server)).find((s) => s.id === 'work')?.shared?.role).toBe('member')
    expect((await remoteSpaces(ana as Ana)).find((s) => s.id === 'work')?.shared?.role).toBe('owner')
    // A member doesn't rename or delete it.
    expect(within(info).queryByRole('textbox', { name: 'Name' })).toBeNull()
    expect(within(info).queryByRole('button', { name: 'Change icon' })).toBeNull()
    expect(within(info).queryByRole('button', { name: 'Delete space…' })).toBeNull()
    expect(within(info).getByRole('heading', { name: 'Work' })).toBeTruthy()
  })
})

describe('as a member', () => {
  it('joins from the switcher’s invitation, then sees the space and its notes', async () => {
    const { user } = await signedIn({ as: 'ana', seed: async (s) => void (await seedWork(s, { share: true, join: false })) })
    await act(() => reloadSharing())
    await user.click(switcher())
    const invites = within(popover()).getByRole('list', { name: 'Invitations' })
    expect(within(invites).getByRole('listitem').textContent).toBe('me invited you to WorkJoinDecline')

    await user.click(within(invites).getByRole('button', { name: 'Join Work' }))
    await waitFor(() => expect(switcher().getAttribute('aria-label')).toBe('Space: Work'))
    await waitFor(() => expect(rowTitles()).toEqual(['Standup']))
    await user.click(switcher())
    expect(within(popover()).queryByRole('list', { name: 'Invitations' })).toBeNull()
  })

  it('declines an invitation: it goes, and nothing else changes', async () => {
    const { server, user } = await signedIn({ as: 'ana', seed: async (s) => void (await seedWork(s, { share: true, join: false })) })
    await act(() => reloadSharing())
    await user.click(switcher())
    await user.click(within(popover()).getByRole('button', { name: 'Decline Work' }))
    await user.click(switcher())
    await waitFor(() => expect(within(popover()).queryByRole('list', { name: 'Invitations' })).toBeNull())
    expect((await remoteSpaces(server)).find((s) => s.id === 'work')?.shared?.invites ?? []).toEqual([])
  })

  it('leaves after a warning: the space and its notes go from the screens', async () => {
    const { server, user } = await signedIn({ as: 'ana', seed: async (s) => void (await seedWork(s, { share: true })) })
    await act(() => reloadSpaces())
    await switchTo(user, 'Work')
    await waitFor(() => expect(rowTitles()).toEqual(['Standup']))
    const info = await spaceInfo(user, 'Work')
    expect(within(info).queryByRole('list', { name: 'People to invite' })).toBeNull()
    expect(within(info).queryByRole('button', { name: /^Remove / })).toBeNull()
    await user.click(within(info).getByRole('button', { name: 'Leave space…' }))
    const confirm = await within(info).findByRole('group', { name: 'Leave Work?' })
    expect(within(confirm).getByText(/Its notes will be removed from this device/)).toBeTruthy()
    await user.click(within(confirm).getByRole('button', { name: 'Leave' }))

    await waitFor(() => expect(switcher().getAttribute('aria-label')).toBe('Space: Notes'))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Work' })).toBeNull())
    expect((await remoteSpaces(server)).find((s) => s.id === 'work')?.shared?.members.map((m) => m.username)).toEqual(['me'])
  })
})

describe('who changed what', () => {
  it('rows and the editor name another member’s edit; the switcher says what changed while away', async () => {
    let ana: Ana | undefined
    let id = ''
    const { user } = await signedIn({ seed: async (s) => void ({ ana, noteId: id } = await seedWork(s, { share: true })) })
    await act(() => reloadSpaces())
    await anaEdits(ana as Ana, id, 'Ana was here\n')
    await act(() => reloadSharing())

    // Away in Notes: Work's row in the switcher says what Ana did.
    await user.click(switcher())
    const row = spaceRow('Work')
    // Just now: a few characters, as the rows say it.
    expect(row.getAttribute('aria-label')).toMatch(/^Work, ana edited a note · (now|\d+m)$/)
    await user.click(row)

    await waitFor(() => expect(within(list()).getByRole('link', { name: /^Standup/ }).textContent).toMatch(/ana · (now|\d+m)/))
    await openNote(user, 'Standup')
    expect(await screen.findByText(/^Edited by ana · (now|\d+m)$/)).toBeTruthy()

    // Shown now: no longer news.
    await switchTo(user, 'Notes')
    await act(() => reloadSharing())
    await user.click(switcher())
    expect(spaceRow('Work').getAttribute('aria-label')).toBe('Work, Shared')
  })

  it('a space shown while the tab is hidden stays news until the tab is seen', async () => {
    let ana: Ana | undefined
    let id = ''
    const { user } = await signedIn({ seed: async (s) => void ({ ana, noteId: id } = await seedWork(s, { share: true })) })
    await act(() => reloadSpaces())
    await anaEdits(ana as Ana, id, 'Ana was here\n')

    let state: DocumentVisibilityState = 'hidden'
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
    try {
      await switchTo(user, 'Work')
      await act(() => reloadSharing())
      // Back in Notes with the tab seen: Work was never, so it's still news.
      state = 'visible'
      await switchTo(user, 'Notes')
      await act(() => reloadSharing())
      await user.click(switcher())
      expect(spaceRow('Work').getAttribute('aria-label')).toMatch(/^Work, ana edited a note · /)
      await user.keyboard('{Escape}')

      state = 'hidden'
      await switchTo(user, 'Work')
      state = 'visible'
      await act(async () => void document.dispatchEvent(new Event('visibilitychange')))
      await switchTo(user, 'Notes')
      await act(() => reloadSharing())
      await user.click(switcher())
      await waitFor(() => expect(spaceRow('Work').getAttribute('aria-label')).toBe('Work, Shared'))
    } finally {
      Reflect.deleteProperty(document, 'visibilityState')
    }
  })

  it('this account’s own edits look as before', async () => {
    const { user } = await signedIn({ seed: async (s) => void (await seedWork(s, { share: true })) })
    await act(() => reloadSpaces())
    await switchTo(user, 'Work')
    await openNote(user, 'Standup')
    await typeAtEnd(user, ' mine')
    await saved()
    expect(within(list()).getByRole('link', { name: /^Standup/ }).textContent).not.toContain('·')
    expect(screen.queryByText(/^Edited by/)).toBeNull()
  })
})

describe('notes in a shared space', () => {
  it('saves to the space’s own file', async () => {
    let id = ''
    const { server, user } = await signedIn({ seed: async (s) => void ({ noteId: id } = await seedWork(s, { share: true })) })
    await act(() => reloadSpaces())
    await switchTo(user, 'Work')
    await openNote(user, 'Standup')
    await typeAtEnd(user, ' done')
    await saved()
    expect((await server.api<Note>('GET', `/notes/${id}`)).body.content).toBe('Status done\n')
    expect(server.sent('PUT', `/notes/${id}`)).toHaveLength(1)
  })

  it('an edit to a note whose space was shared elsewhere follows it there', async () => {
    let id = ''
    let ana: Ana | undefined
    const { server, user } = await signedIn({ seed: async (s) => void ({ noteId: id, ana } = await seedWork(s)) })
    await act(() => reloadSpaces())
    await switchTo(user, 'Work')
    await openNote(user, 'Standup')
    // Shared from another device: the note moved to the space's own file.
    await server.api('POST', '/spaces/work/invites', { userId: ana?.id })

    await typeAtEnd(user, ' after')
    await saved()
    expect((await server.api<Note>('GET', `/notes/${id}`)).body.content).toBe('Status after\n')
  })

  it('a picture is uploaded to the account’s address, and loads from it', async () => {
    const { server, user } = await signedIn({ seed: async (s) => void (await seedWork(s, { share: true })) })
    await act(() => reloadSpaces())
    await switchTo(user, 'Work')
    await openNote(user, 'Standup')
    pick(pngFile())
    await saved()
    const image = await sha256Hex(PNG)
    expect((await server.bytes('GET', `/images/${image}`)).status).toBe(200)
    await waitFor(() => expect(shown()[0]?.getAttribute('src')).toBe(`/api/v1/images/${image}`))
  })

  it('searching from another space counts and lists its matches once', async () => {
    const { user } = await signedIn({ seed: async (s) => void (await seedWork(s, { share: true })) })
    await act(() => reloadSpaces())
    await user.type(screen.getByRole('searchbox', { name: 'Search notes' }), 'status')
    expect(await screen.findByText('Nothing in Notes matches “status”.')).toBeTruthy()
    await user.click(await screen.findByRole('button', { name: '1 more in Work' }))
    const other = await screen.findByRole('list', { name: 'Results in other spaces' })
    await waitFor(() => expect(within(other).getAllByRole('link').map((a) => a.querySelector('span')?.textContent)).toEqual(['Standup']))
  })

  it('opens by its address, though it isn’t in the account’s file', async () => {
    const noteId = crypto.randomUUID()
    await signedIn({ path: `/notes/${noteId}`, seed: async (s) => void (await seedWork(s, { share: true, noteId })) })
    await waitFor(() => expect(noteTitle()).toBe('Standup'))
    await waitFor(() => expect(switcher().getAttribute('aria-label')).toBe('Space: Work'))
  })

  it('Lock note explains instead of locking', async () => {
    const { user } = await signedIn({ seed: async (s) => void (await seedWork(s, { share: true })) })
    await act(() => reloadSpaces())
    await switchTo(user, 'Work')
    await openNote(user, 'Standup')
    await user.click(await screen.findByRole('button', { name: 'More' }))
    await user.click(screen.getByRole('menuitem', { name: 'Lock note' }))
    const dialog = await screen.findByRole('dialog', { name: 'Locked notes can’t be in a shared space.' })
    await user.click(within(dialog).getByRole('button', { name: 'OK' }))
    expect(screen.queryByRole('dialog', { name: /Lock/ })).toBeNull()
  })

  it('moving out asks who will no longer see it, then moves it with one update', async () => {
    let id = ''
    const { server, user } = await signedIn({ seed: async (s) => void ({ noteId: id } = await seedWork(s, { share: true })) })
    await act(() => reloadSpaces())
    await switchTo(user, 'Work')
    await openNote(user, 'Standup')
    await user.click(await screen.findByRole('button', { name: 'More' }))
    await user.click(screen.getByRole('menuitem', { name: 'Move to space…' }))
    const picker = await screen.findByRole('dialog', { name: 'Move to space' })
    await user.click(within(picker).getByRole('button', { name: 'Notes' }))

    const confirm = await screen.findByRole('dialog', { name: 'Move “Standup” to Notes?' })
    expect(within(confirm).getByText('It will no longer be:')).toBeTruthy()
    expect(within(confirm).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['Visible to ana'])
    await user.click(within(confirm).getByRole('button', { name: 'Move' }))

    expect(await screen.findByText('Moved to Notes')).toBeTruthy()
    await waitFor(async () => expect((await server.api<Note>('GET', `/notes/${id}`)).body.spaceId).toBe('default'))
    // One update naming the space: the server moved it between files.
    expect(server.sent('PUT', `/notes/${id}`)).toHaveLength(1)
  })

  it('moving in asks who will see it, and a locked note says it can’t', async () => {
    const { server, user } = await signedIn({
      seed: async (s) => {
        await seedWork(s, { share: true })
        await noteIn(s, 'default', 'Groceries', 'Milk\n')
      },
    })
    await act(() => reloadSpaces())
    await openNote(user, 'Groceries')
    await user.click(await screen.findByRole('button', { name: 'More' }))
    await user.click(screen.getByRole('menuitem', { name: 'Move to space…' }))
    await user.click(within(await screen.findByRole('dialog', { name: 'Move to space' })).getByRole('button', { name: /^Work/ }))
    const confirm = await screen.findByRole('dialog', { name: 'Move “Groceries” to Work?' })
    expect(within(confirm).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['Visible to ana'])
    await user.click(within(confirm).getByRole('button', { name: 'Move' }))
    await waitFor(async () => {
      const work = (await server.api<{ notes: Note[] }>('GET', '/notes?space=work')).body.notes
      expect(work.map((n) => n.title).toSorted()).toEqual(['Groceries', 'Standup'])
    })
  })
})

describe('on this device', () => {
  it('joining brings the space’s notes, rows name other members, and leaving takes them away', async () => {
    let ana: Ana | undefined
    let id = ''
    const { user, engine } = await signedIn({
      onDevice: true,
      seed: async (s) => {
        ;({ ana, noteId: id } = await seedWork(s, { share: true }))
        // Ana leaves room for "me" to be the member on this device: the owner hands Work to her.
        await s.api('PUT', '/spaces/work/owner', { userId: ana.id })
      },
    })
    await act(() => reloadSpaces())
    await anaEdits(ana as Ana, id, 'Ana was here\n')
    await engine?.call('sync')
    await act(() => reloadSharing())

    await user.click(switcher())
    expect(spaceRow('Work').getAttribute('aria-label')).toMatch(/^Work, ana edited a note · /)
    await user.click(spaceRow('Work'))
    await waitFor(() => expect(within(list()).getByRole('link', { name: /^Standup/ }).textContent).toMatch(/ana · /))

    const info = await spaceInfo(user, 'Work')
    await user.click(within(info).getByRole('button', { name: 'Leave space…' }))
    await user.click(within(await within(info).findByRole('group', { name: 'Leave Work?' })).getByRole('button', { name: 'Leave' }))

    await waitFor(() => expect(switcher().getAttribute('aria-label')).toBe('Space: Notes'))
    expect(await engine?.call('list', null, 'work')).toEqual([])
  })
})
