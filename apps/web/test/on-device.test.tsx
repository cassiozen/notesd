import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Note, Space } from '../src/api/types'
import {
  bodyText,
  createNote,
  editElsewhere,
  list,
  op,
  openNote,
  openSettings,
  row,
  saved,
  signedIn,
  typeAtEnd,
} from './helpers'

// The screens on this device's engine. Saves still go to the server; the engine syncs them back.

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

  it('opens a note and saves edits, which the device then has too', async () => {
    let id = ''
    const { engine, user } = await signedIn({
      onDevice: true,
      seed: async (s) => {
        id = await createNote(s, 'Plan', 'Step one')
      },
    })
    await openNote(user, 'Plan')
    await waitFor(() => expect(bodyText()).toBe('Step one'))

    await typeAtEnd(user, ', step two')
    await saved()

    await waitFor(async () => expect((await engine?.call('get', id))?.content).toBe('Step one, step two\n'))
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

  it('opens a note the server has before this device does', async () => {
    const { server } = await signedIn({ onDevice: true })
    const id = await createNote(server, 'Fresh', 'Just made\n')
    window.history.pushState(null, '', `/notes/${id}`)
    window.dispatchEvent(new PopStateEvent('popstate'))

    await waitFor(() => expect(bodyText()).toBe('Just made'))
    expect((await server.api<Note>('GET', `/notes/${id}`)).body.title).toBe('Fresh')
  })
})
