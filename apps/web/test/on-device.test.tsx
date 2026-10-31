import { screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { Space } from '../src/api/types'
import { timing } from '../src/notes/saver'
import type { Engine } from '../src/offline/tab'
import {
  bodyText,
  createNote,
  deleteNote,
  editElsewhere,
  getNote,
  list,
  listNotes,
  noteTitle,
  op,
  openNote,
  openSettings,
  row,
  saved,
  signedIn,
  typeAtEnd,
} from './helpers'

// The screens on this device's engine, which syncs with the server in the background.

const defaults = { ...timing }
afterEach(() => {
  Object.assign(timing, defaults)
  // @ts-expect-error -- back to the document's own getter
  delete document.visibilityState
})

/** Saves go out when the tab is hidden, without waiting for the typing to pause. */
function hideTab() {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
  document.dispatchEvent(new Event('visibilitychange'))
}

const panel = () => screen.getByRole('navigation', { name: 'Sidebar' })
const tagRow = (name: string) =>
  within(within(panel()).getByRole('region', { name: 'Tags' })).getByRole('button', { name: new RegExp(`^#${name},`, 'i') })

describe('notes on this device', () => {
  it('pages through more than 50 notes and counts them all', async () => {
    const { user } = await signedIn({
      onDevice: true,
      seed: async (s) => {
        for (let i = 0; i < 55; i++) await createNote(s, `Note ${i}`)
      },
    })

    await waitFor(() => expect(within(list()).getAllByRole('link')).toHaveLength(50))
    expect(within(list()).getByText('55 notes')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(within(list()).getAllByRole('link')).toHaveLength(55))
  })

  it('saves on this device, and sync sends it', async () => {
    let id = ''
    const { engine, server, user } = await signedIn({
      onDevice: true,
      seed: async (s) => {
        id = await createNote(s, 'Plan', 'Step one')
      },
    })
    await openNote(user, 'Plan')
    await waitFor(() => expect(bodyText()).toBe('Step one'))

    await typeAtEnd(user, ', step two')
    await saved()

    expect((await engine?.call('get', id))?.content).toBe('Step one, step two\n')
    expect((await getNote(server, id)).content).toBe('Step one')
    await engine?.call('sync')
    expect((await getNote(server, id)).content).toBe('Step one, step two\n')
  })

  it('shows what sync brings in, without being asked', async () => {
    let id = ''
    const { engine, server } = await signedIn({
      onDevice: true,
      seed: async (s) => {
        id = await createNote(s, 'Roof', 'Call about it\n')
      },
    })
    await editElsewhere(server, id, { title: 'Roof repairs', content: 'Call about it #home\n' })

    await engine?.call('sync')

    await waitFor(() => expect(row('Roof repairs')).toBeTruthy())
    await waitFor(() => expect(tagRow('home').getAttribute('aria-label')).toBe('#home, 1 note'))
  })

  it('searches, counting matches in other spaces', async () => {
    const { user } = await signedIn({
      onDevice: true,
      seed: async (s) => {
        await s.api('PUT', '/spaces/work', { name: 'Work' })
        await createNote(s, 'Groceries', 'Milk and garden seeds\n')
        await s.api('POST', '/notes', { operationId: op(), id: crypto.randomUUID(), title: 'Standup', content: 'Garden project\n', spaceId: 'work' })
      },
    })

    await user.type(screen.getByRole('searchbox', { name: 'Search notes' }), 'garden')

    const results = await screen.findByRole('list', { name: 'Search results' })
    await waitFor(() => expect(within(results).getAllByRole('link')).toHaveLength(1))
    expect(within(results).getByRole('link', { name: /Groceries/ }).querySelector('mark')?.textContent).toBe('garden')
    expect(await screen.findByRole('button', { name: '1 more in Work' })).toBeTruthy()
  })

  it('makes a space on this device, and sync sends it', async () => {
    const { engine, server, user } = await signedIn({ onDevice: true })
    await openSettings(user, 'Spaces')
    const card = await screen.findByRole('region', { name: 'Spaces' })
    await user.click(within(card).getByRole('button', { name: 'New space' }))
    const dialog = await screen.findByRole('dialog', { name: 'New space' })
    await user.type(within(dialog).getByLabelText('Name'), 'Work')
    await user.click(within(dialog).getByRole('radio', { name: 'Duck' }))
    await user.click(within(dialog).getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(within(panel()).getByRole('button', { name: /^Space: / }).getAttribute('aria-label')).toBe('Space: Work'))
    expect((await engine?.call('spaces'))?.map((s) => s.name)).toEqual(['Notes', 'Work'])
    await engine?.call('sync')
    const onServer = (await server.api<{ spaces: Space[] }>('GET', '/spaces')).body.spaces
    expect(onServer.map((s) => [s.name, s.icon])).toEqual([
      ['Notes', ''],
      ['Work', 'duck'],
    ])
  })

  it('shows a change sync brings in to the open note', async () => {
    let id = ''
    const { engine, server, user } = await signedIn({
      onDevice: true,
      seed: async (s) => {
        id = await createNote(s, 'Plan', 'Step one\n')
      },
    })
    await openNote(user, 'Plan')

    await editElsewhere(server, id, { content: 'Step one, from the phone\n' })
    await engine?.call('sync')

    await waitFor(() => expect(bodyText()).toBe('Step one, from the phone'))
  })

  it('merges typing with a change sync brought to the other field meanwhile', async () => {
    let id = ''
    const { engine, server, user } = await signedIn({
      onDevice: true,
      seed: async (s) => {
        id = await createNote(s, 'Plan', 'Step one\n')
      },
    })
    await openNote(user, 'Plan')
    timing.debounceMs = 60_000

    await typeAtEnd(user, ', step two')
    await editElsewhere(server, id, { title: 'Plan B' })
    await engine?.call('sync')
    hideTab()

    await waitFor(() => expect(noteTitle()).toBe('Plan B'))
    expect(bodyText()).toBe('Step one, step two')
    expect(await engine?.call('get', id)).toMatchObject({ title: 'Plan B', content: 'Step one, step two\n' })
  })

  it('keeps both versions when sync finds the same text changed in two places', async () => {
    let id = ''
    const { engine, server, user } = await signedIn({
      onDevice: true,
      seed: async (s) => {
        id = await createNote(s, 'Plan', 'Step one\n')
      },
    })
    await openNote(user, 'Plan')
    await typeAtEnd(user, ', mine')
    await saved()

    await editElsewhere(server, id, { content: 'Step one, theirs\n' })
    await engine?.call('sync')

    expect(await screen.findByText(/^Changed in two places\. Your version was kept as “Plan \(Conflicted copy\)”\.$/)).toBeTruthy()
    await waitFor(() => expect(row('Plan \\(Conflicted copy\\)')).toBeTruthy())
  })

  it('opens the typing as a new note when sync finds the note deleted elsewhere', async () => {
    let id = ''
    const { engine, server, user } = await signedIn({
      onDevice: true,
      seed: async (s) => {
        id = await createNote(s, 'Ideas', 'One')
      },
    })
    await openNote(user, 'Ideas')
    timing.debounceMs = 60_000

    await typeAtEnd(user, ', two')
    const note = await getNote(server, id)
    await server.api('DELETE', `/notes/${id}`, { operationId: op(), baseRevision: note.revision })
    await engine?.call('sync')
    hideTab()

    await waitFor(() => expect(noteTitle()).toBe('Ideas (Recovered copy)'))
    expect(bodyText()).toBe('One, two')
    expect(await screen.findByText('Deleted on another device. Your edits were kept as “Ideas (Recovered copy)”.')).toBeTruthy()
    await waitFor(() => expect(window.location.pathname).not.toBe(`/notes/${id}`))
  })

  it('keeps the typing when its recovered copy can’t be stored, and Retry makes one copy', async () => {
    let id = ''
    const { engine, server, user } = await signedIn({
      onDevice: true,
      seed: async (s) => {
        id = await createNote(s, 'Ideas', 'One')
      },
    })
    if (!engine) throw new Error('no engine')
    const call = engine.call as (method: string, ...args: unknown[]) => Promise<unknown>
    let failures = 1
    engine.call = ((method: string, ...args: unknown[]) =>
      method === 'recover' && failures-- > 0 ? Promise.reject(new Error('disk I/O error')) : call(method, ...args)) as Engine['call']
    await openNote(user, 'Ideas')
    timing.debounceMs = 60_000

    await typeAtEnd(user, ', two')
    const note = await getNote(server, id)
    await server.api('DELETE', `/notes/${id}`, { operationId: op(), baseRevision: note.revision })
    await engine.call('sync')
    hideTab()

    expect(await screen.findByText(/disk I\/O error/)).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Retry' }))
    await waitFor(() => expect(noteTitle()).toBe('Ideas (Recovered copy)'))
    expect(bodyText()).toBe('One, two')
    const copies = (await engine.call('list', null, null)).filter((n) => n.title === 'Ideas (Recovered copy)')
    expect(copies).toHaveLength(1)
  })

  it('closes the note when sync finds it deleted elsewhere', async () => {
    let id = ''
    const { engine, server, user } = await signedIn({
      onDevice: true,
      seed: async (s) => {
        await createNote(s, 'Keep', 'Stays\n')
        id = await createNote(s, 'Gone', 'Goes\n')
      },
    })
    await openNote(user, 'Gone')

    const note = await getNote(server, id)
    await server.api('DELETE', `/notes/${id}`, { operationId: op(), baseRevision: note.revision })
    await engine?.call('sync')

    await waitFor(() => expect(noteTitle()).not.toBe('Gone'))
    expect(within(list()).queryByRole('link', { name: /^Gone/ })).toBeNull()
  })

  it('says when notes are stuck on this device, and Sync now sends them', async () => {
    const { engine, server, user } = await signedIn({ onDevice: true, seed: (s) => createNote(s, 'Plan', 'One').then(() => undefined) })
    await openNote(user, 'Plan')
    server.setOffline(true)
    await typeAtEnd(user, ', two')
    await saved()

    expect(await engine?.call('sync')).toBe('Offline')
    expect(await screen.findByText('1 note not synced')).toBeTruthy()

    server.setOffline(false)
    await user.click(screen.getByRole('button', { name: 'Sync now' }))
    await waitFor(() => expect(screen.queryByText('1 note not synced')).toBeNull())
    expect((await listNotes(server)).map((n) => n.preview)).toEqual(['One, two'])
  })

  it('moves a note to another space on this device', async () => {
    let id = ''
    const { engine, user } = await signedIn({
      onDevice: true,
      seed: async (s) => {
        await s.api('PUT', '/spaces/work', { name: 'Work' })
        id = await createNote(s, 'Groceries', 'Milk\n')
      },
    })
    await openNote(user, 'Groceries')
    await user.click(await screen.findByRole('button', { name: 'More' }))
    await user.click(screen.getByRole('menuitem', { name: 'Move to space…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Move to space' })
    await user.click(within(dialog).getByRole('button', { name: 'Work' }))

    expect(await screen.findByText('Moved to Work')).toBeTruthy()
    await waitFor(async () => expect((await engine?.call('get', id))?.spaceId).toBe('work'))
    await waitFor(() => expect(within(list()).queryByRole('link', { name: /^Groceries/ })).toBeNull())
  })

  it('deletes on this device after the undo window, and Undo keeps the note', async () => {
    let id = ''
    const { engine, user } = await signedIn({
      onDevice: true,
      seed: async (s) => {
        await createNote(s, 'Keep', 'Stays\n')
        id = await createNote(s, 'Bin', 'Goes\n')
      },
    })
    await openNote(user, 'Bin')
    // Long enough to click Undo on a slow runner.
    timing.undoDeleteMs = 5000
    await deleteNote(user)
    expect(within(list()).getByText('1 note')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Undo' }))
    await waitFor(() => expect(noteTitle()).toBe('Bin'))
    expect(await engine?.call('get', id)).not.toBeNull()

    timing.undoDeleteMs = 50
    await deleteNote(user)
    await waitFor(async () => expect(await engine?.call('get', id)).toBeNull())
    await waitFor(() => expect(screen.queryByText('Note deleted')).toBeNull())
    expect(within(list()).getByText('1 note')).toBeTruthy()
  })

})
