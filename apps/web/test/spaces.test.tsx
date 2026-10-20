import { screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Note, Space } from '../src/api/types'
import { SPACE_RESET_MS } from '../src/notes/shell'
import duck from '../src/notes/space-icons/duck.svg?url'
import {
  bodyText,
  createNote,
  list,
  newNoteButton,
  noteTitle,
  op,
  openNote,
  phoneLayout,
  saved,
  signedIn,
  typeAtEnd,
  typeInTitle,
} from './helpers'
import type { TestServer } from './server'

async function noteIn(server: TestServer, spaceId: string, title: string, content = '') {
  const id = crypto.randomUUID()
  const res = await server.api('POST', '/notes', { operationId: op(), id, title, content, spaceId })
  expect(res.status).toBe(201)
  return id
}

/** Notes in the default space and in Work. */
async function withWork() {
  return signedIn({
    seed: async (s) => {
      await s.api('PUT', '/spaces/work', { name: 'Work' })
      await createNote(s, 'Groceries', 'Milk and garden seeds #home\n')
      await noteIn(s, 'work', 'Standup', 'Garden project status #clients\n')
      await noteIn(s, 'work', 'Budget', 'Numbers #clients\n')
    },
  })
}

const panel = () => screen.getByRole('navigation', { name: 'Sidebar' })
const rowTitles = () =>
  within(list())
    .queryAllByRole('link')
    .map((a) => a.querySelector('span')?.textContent)
const switcher = () => within(panel()).getByRole('button', { name: /^Space: / })

async function switchTo(user: Awaited<ReturnType<typeof signedIn>>['user'], name: string) {
  await user.click(await waitFor(() => switcher()))
  await user.click(screen.getByRole('menuitemradio', { name }))
}

async function spaces(server: TestServer) {
  return (await server.api<{ spaces: Space[] }>('GET', '/spaces')).body.spaces
}

describe('one space', () => {
  it('looks like there are no spaces', async () => {
    const { user } = await signedIn({ seed: (s) => createNote(s, 'Only', 'text\n').then(() => undefined) })
    // The space is named all the same, and its menu offers only a new one.
    await user.click(await waitFor(() => switcher()))
    expect(switcher().getAttribute('aria-label')).toBe('Space: Notes')
    expect(screen.getAllByRole('menuitemradio').map((i) => i.textContent)).toEqual(['Notes✓'])
    expect(screen.getByRole('menuitem', { name: 'New space…' })).toBeTruthy()
    await user.keyboard('{Escape}')
    expect(within(list()).getByRole('heading', { name: 'All Notes' })).toBeTruthy()
    expect(screen.getByRole('searchbox', { name: 'Search notes' }).getAttribute('placeholder')).toBe('Search all notes')

    await user.click(await screen.findByRole('button', { name: 'More' }))
    expect(screen.queryByRole('menuitem', { name: 'Move to space…' })).toBeNull()
  })
})

describe('creating spaces', () => {
  it('starts from the switcher too', async () => {
    const { server, user } = await signedIn({ seed: (s) => createNote(s, 'Old', 'text\n').then(() => undefined) })
    await user.click(await waitFor(() => switcher()))
    await user.click(screen.getByRole('menuitem', { name: 'New space…' }))

    const dialog = await screen.findByRole('dialog', { name: 'New space' })
    await user.type(within(dialog).getByLabelText('Name'), 'Work')
    await user.click(within(dialog).getByRole('radio', { name: 'Duck' }))
    await user.click(within(dialog).getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(switcher().getAttribute('aria-label')).toBe('Space: Work'))
    expect(await within(list()).findByText('No notes in Work yet.')).toBeTruthy()
    expect((await spaces(server)).map((s) => s.name)).toEqual(['Notes', 'Work'])
  })

  it('names the new space, then switches to it', async () => {
    const { server, user } = await signedIn({ seed: (s) => createNote(s, 'Old', 'text\n').then(() => undefined) })
    await user.click(screen.getByRole('button', { name: 'Settings' }))
    const card = await screen.findByRole('region', { name: 'Spaces' })
    await user.click(within(card).getByRole('button', { name: 'New space' }))

    const dialog = await screen.findByRole('dialog', { name: 'New space' })
    // Notes keeps its name: nothing here renames it.
    expect(within(dialog).queryByLabelText(/^Rename/)).toBeNull()
    await user.type(within(dialog).getByLabelText('Name'), 'Work')
    // The icon is required too.
    await user.click(within(dialog).getByRole('button', { name: 'Create' }))
    expect(within(dialog).getByRole('alert').textContent).toBe('Choose an icon.')
    await user.click(within(dialog).getByRole('radio', { name: 'Duck' }))
    await user.click(within(dialog).getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Settings' })).toBeNull())
    await waitFor(() => expect(switcher().getAttribute('aria-label')).toBe('Space: Work'))
    expect(await within(list()).findByText('No notes in Work yet.')).toBeTruthy()
    expect((await spaces(server)).map((s) => [s.name, s.icon])).toEqual([
      ['Notes', ''],
      ['Work', 'duck'],
    ])
    expect(switcher().querySelector('img')?.getAttribute('src')).toBe(duck)

    // A new note lands in the space shown.
    await user.click(newNoteButton())
    await typeInTitle(user, 'Plan')
    await waitFor(async () => {
      const work = (await server.api<{ notes: Note[] }>('GET', '/notes?space=' + (await spaces(server))[1]?.id)).body.notes
      expect(work.map((n) => n.title)).toEqual(['Plan'])
    })
  })

  it('changes a space’s name and icon', async () => {
    const { server, user } = await withWork()
    await user.click(screen.getByRole('button', { name: 'Settings' }))
    const card = await screen.findByRole('region', { name: 'Spaces' })
    await user.click(await within(card).findByRole('button', { name: 'Edit Notes' }))

    const dialog = await screen.findByRole('dialog', { name: 'Edit space' })
    // The default space starts with the app's icon.
    expect((within(dialog).getByRole('radio', { name: 'App icon' }) as HTMLInputElement).checked).toBe(true)
    await user.clear(within(dialog).getByLabelText('Name'))
    await user.type(within(dialog).getByLabelText('Name'), 'Home')
    await user.click(within(dialog).getByRole('radio', { name: 'Castle' }))
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Edit space' })).toBeNull())
    expect((await spaces(server)).map((s) => [s.name, s.icon])).toEqual([
      ['Home', 'castle'],
      ['Work', ''],
    ])
  })

  it('shows the notes that stopped a delete', async () => {
    const { server, user } = await withWork()
    await server.api('PUT', '/spaces/empty', { name: 'Empty' })
    await user.click(screen.getByRole('button', { name: 'Settings' }))
    const card = await screen.findByRole('region', { name: 'Spaces' })
    const remove = await waitFor(() => within(card).getByRole('button', { name: 'Delete Empty' }))
    // Another device files a note there meanwhile.
    await noteIn(server, 'empty', 'Late', 'x\n')
    await user.click(remove)

    expect((await within(card).findByRole('alert')).textContent).toMatch(/another device/)
    await waitFor(() => expect(within(card).getByRole('button', { name: 'Delete Empty' }).hasAttribute('disabled')).toBe(true))
  })

  it('only deletes an empty space', async () => {
    const { server, user } = await withWork()
    await server.api('PUT', '/spaces/empty', { name: 'Empty' })
    await user.click(screen.getByRole('button', { name: 'Settings' }))
    const card = await screen.findByRole('region', { name: 'Spaces' })
    await waitFor(() => within(card).getByRole('button', { name: 'Delete Empty' }))

    expect(within(card).getByRole('button', { name: 'Delete Work' }).hasAttribute('disabled')).toBe(true)
    expect(within(card).queryByRole('button', { name: 'Delete Notes' })).toBeNull()
    await user.click(within(card).getByRole('button', { name: 'Delete Empty' }))
    await waitFor(() => expect(within(card).queryByText('Empty')).toBeNull())
    expect((await spaces(server)).map((s) => s.id)).toEqual(['default', 'work'])
  })
})

describe('switching spaces', () => {
  it('shows only the space’s notes and tags', async () => {
    const { user } = await withWork()
    await waitFor(() => expect(rowTitles()).toEqual(['Groceries']))
    await waitFor(() => expect(within(panel()).getByRole('button', { name: /^#home,/ })).toBeTruthy())

    await switchTo(user, 'Work')
    await waitFor(() => expect(rowTitles().toSorted()).toEqual(['Budget', 'Standup']))
    await waitFor(() => expect(within(panel()).getByRole('button', { name: /^#clients, 2 notes/ })).toBeTruthy())
    expect(within(panel()).queryByRole('button', { name: /^#home,/ })).toBeNull()
    expect(screen.getByRole('searchbox', { name: 'Search notes' }).getAttribute('placeholder')).toBe('Search in Work')
  })

  it('works from the keyboard, focus back on the switcher after', async () => {
    const { user } = await withWork()
    const button = await waitFor(() => switcher())
    button.focus()
    await user.keyboard('{ArrowDown}')
    // Focus goes to the space shown, then the arrows move through the others.
    expect(document.activeElement).toBe(screen.getByRole('menuitemradio', { name: 'Notes' }))
    await user.keyboard('{ArrowDown}')
    expect(document.activeElement).toBe(screen.getByRole('menuitemradio', { name: 'Work' }))
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('menu', { name: 'Spaces' })).toBeNull()
    expect(document.activeElement).toBe(switcher())

    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}')
    await waitFor(() => expect(switcher().getAttribute('aria-label')).toBe('Space: Work'))
    expect(document.activeElement).toBe(switcher())
  })

  it('moves a note to another space', async () => {
    const { server, user } = await withWork()
    await openNote(user, 'Groceries')
    await user.click(await screen.findByRole('button', { name: 'More' }))
    await user.click(screen.getByRole('menuitem', { name: 'Move to space…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Move to space' })
    await user.click(within(dialog).getByRole('button', { name: 'Work' }))

    expect(await screen.findByText('Moved to Work')).toBeTruthy()
    await waitFor(() => expect(rowTitles()).toEqual([]))
    await waitFor(async () => {
      const work = (await server.api<{ notes: Note[] }>('GET', '/notes?space=work')).body.notes
      expect(work.map((n) => n.title).toSorted()).toEqual(['Budget', 'Groceries', 'Standup'])
    })
  })

  it('offers no move for a new note with nothing in it', async () => {
    const { user } = await withWork()
    await waitFor(() => switcher())
    await user.click(newNoteButton())
    await user.click(await screen.findByRole('button', { name: 'More' }))
    expect(screen.queryByRole('menuitem', { name: 'Move to space…' })).toBeNull()
  })

  it('keeps a move made on another device while typing here', async () => {
    const { server, user } = await withWork()
    await openNote(user, 'Groceries')
    await waitFor(() => expect(bodyText()).toBe('Milk and garden seeds #home'))
    const [groceries] = (await server.api<{ notes: Note[] }>('GET', '/notes?space=default')).body.notes
    if (!groceries) throw new Error('no note')
    const moved = await server.api('PUT', `/notes/${groceries.id}`, {
      operationId: op(),
      baseRevision: groceries.revision,
      title: groceries.title,
      content: 'Milk and garden seeds #home\n',
      spaceId: 'work',
    })
    expect(moved.status).toBe(200)

    await typeAtEnd(user, ' and bread')
    await saved()
    await waitFor(async () => {
      const note = (await server.api<Note>('GET', `/notes/${groceries.id}`)).body
      expect(note).toMatchObject({ spaceId: 'work', content: 'Milk and garden seeds #home and bread\n' })
    })
    // It went to Work: this space's list lets it go.
    await waitFor(() => expect(rowTitles()).toEqual([]))
  })

  it('says it’s this space that has no matches when others do', async () => {
    const { user } = await withWork()
    await user.type(screen.getByRole('searchbox', { name: 'Search notes' }), 'status')
    expect(await screen.findByText('Nothing in Notes matches “status”.')).toBeTruthy()
    expect(await screen.findByRole('button', { name: '1 more in Work' })).toBeTruthy()
  })

  it('finds notes in other spaces and switches to theirs', async () => {
    const { user } = await withWork()
    await user.type(screen.getByRole('searchbox', { name: 'Search notes' }), 'garden')
    const results = await screen.findByRole('list', { name: 'Search results' })
    await waitFor(() => expect(within(results).getAllByRole('link')).toHaveLength(1))

    await user.click(await screen.findByRole('button', { name: '1 more in Work' }))
    const other = await screen.findByRole('list', { name: 'Results in other spaces' })
    await user.click(within(other).getByRole('link', { name: /^Standup/ }))

    await waitFor(() => expect(switcher().getAttribute('aria-label')).toBe('Space: Work'))
    await waitFor(() => expect(noteTitle()).toBe('Standup'))
  })
})

/** The tab hidden, then shown again ms later. */
function away(ms: number) {
  let state: DocumentVisibilityState = 'visible'
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
  const now = Date.now()
  state = 'hidden'
  document.dispatchEvent(new Event('visibilitychange'))
  vi.spyOn(Date, 'now').mockReturnValue(now + ms)
  state = 'visible'
  document.dispatchEvent(new Event('visibilitychange'))
}

describe('going back to the default space', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    // @ts-expect-error -- back to the document's own getter
    delete document.visibilityState
  })

  it('happens after a long time away with no note open', async () => {
    // On a phone: wider, a note is always open beside the list.
    phoneLayout()
    const { user } = await withWork()
    const sidebar = async () => user.click(await waitFor(() => within(list()).getByRole('button', { name: 'Sidebar' })))
    await sidebar()
    await switchTo(user, 'Work')
    await waitFor(() => expect(rowTitles().toSorted()).toEqual(['Budget', 'Standup']))

    away(SPACE_RESET_MS - 60_000)
    await new Promise((r) => setTimeout(r, 50))
    expect(rowTitles().toSorted()).toEqual(['Budget', 'Standup'])

    away(SPACE_RESET_MS + 60_000)
    await waitFor(() => expect(rowTitles()).toEqual(['Groceries']))
    await sidebar()
    expect(switcher().getAttribute('aria-label')).toBe('Space: Notes')
  })

  it('waits while a note is open', async () => {
    const { user } = await withWork()
    await switchTo(user, 'Work')
    await waitFor(() => expect(rowTitles().toSorted()).toEqual(['Budget', 'Standup']))
    await openNote(user, 'Budget')

    away(SPACE_RESET_MS + 60_000)
    await new Promise((r) => setTimeout(r, 50))
    expect(switcher().getAttribute('aria-label')).toBe('Space: Work')
    expect(rowTitles().toSorted()).toEqual(['Budget', 'Standup'])
  })
})
