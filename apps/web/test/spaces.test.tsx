import { act, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { spaces as spacesApi } from '../src/api/spaces'
import type { Note, Space, SpaceTrait } from '../src/api/types'
import { SPACE_RESET_MS } from '../src/notes/shell'
import { reloadSpaces } from '../src/notes/spaces'
import duck from '../src/notes/space-icons/duck.svg?url'
import {
  bodyText,
  createNote,
  list,
  newNoteButton,
  noteTitle,
  op,
  openNote,
  openSettings,
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
  const session = await signedIn({
    seed: async (s) => {
      await s.api('PUT', '/spaces/work', { name: 'Work' })
      await createNote(s, 'Groceries', 'Milk and garden seeds #home\n')
      await noteIn(s, 'work', 'Standup', 'Garden project status #clients\n')
      await noteIn(s, 'work', 'Budget', 'Numbers #clients\n')
    },
  })
  // The switcher shows only Notes until the list loads, so a menu opened before then has no Work.
  await act(() => reloadSpaces())
  return session
}

const panel = () => screen.getByRole('navigation', { name: 'Sidebar' })
const rowTitles = () =>
  within(list())
    .queryAllByRole('link')
    .map((a) => a.querySelector('span')?.textContent)
const switcher = () => within(panel()).getByRole('button', { name: /^Space: / })
const popover = () => screen.getByRole('dialog', { name: 'Spaces' })
/** A space in the switcher's list: its name, then its traits if it has any ("Work, Shared"). */
const spaceRow = (name: string) => within(popover()).getByRole('button', { name: new RegExp(`^${name}(,|$)`) })

type User = Awaited<ReturnType<typeof signedIn>>['user']

async function switchTo(user: User, name: string) {
  await user.click(await waitFor(() => switcher()))
  await user.click(spaceRow(name))
}

/** Settings › Spaces, then the named space's row: its Space info. */
async function infoFromSettings(user: User, name: string) {
  if (!screen.queryByRole('region', { name: 'Spaces' })) await openSettings(user, 'Spaces')
  const card = await screen.findByRole('region', { name: 'Spaces' })
  await user.click(await within(card).findByRole('button', { name: new RegExp(`^${name}, `) }))
  return screen.findByRole('dialog', { name })
}

async function spaces(server: TestServer) {
  return (await server.api<{ spaces: Space[] }>('GET', '/spaces')).body.spaces
}

describe('one space', () => {
  it('looks like there are no spaces', async () => {
    const { user } = await signedIn({ seed: (s) => createNote(s, 'Only', 'text\n').then(() => undefined) })
    // The space is named all the same, and its switcher offers only a new one.
    await user.click(await waitFor(() => switcher()))
    expect(switcher().getAttribute('aria-label')).toBe('Space: Notes')
    // Nothing said about who sees it: private is the default.
    expect(within(popover()).getByRole('region', { name: 'Notes' }).textContent).toBe('NotesInfo')
    expect(within(popover()).queryByRole('heading', { name: 'Switch to' })).toBeNull()
    expect(within(popover()).getByRole('button', { name: 'New space…' })).toBeTruthy()
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
    await user.click(within(popover()).getByRole('button', { name: 'New space…' }))

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
    await openSettings(user, 'Spaces')
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

  it('changes a space’s name and icon in place on its Space info', async () => {
    const { server, user } = await withWork()
    const info = await infoFromSettings(user, 'Notes')
    expect(within(info).queryByRole('radiogroup')).toBeNull()
    await user.click(within(info).getByRole('button', { name: 'Change icon' }))
    // The default space starts with the app's icon. A pick is saved as it's made.
    expect((within(info).getByRole('radio', { name: 'App icon' }) as HTMLInputElement).checked).toBe(true)
    await user.click(within(info).getByRole('radio', { name: 'Castle' }))
    await waitFor(async () => expect((await spaces(server))[0]?.icon).toBe('castle'))
    expect(info.getAttribute('data-space')).toBe('castle')

    const name = within(info).getByRole('textbox', { name: 'Name' })
    // Escape puts back what was typed, and the page stays.
    await user.clear(name)
    await user.type(name, 'Nope')
    await user.keyboard('{Escape}')
    expect((name as HTMLInputElement).value).toBe('Notes')
    expect(screen.getByRole('dialog', { name: 'Notes' })).toBeTruthy()

    await user.clear(name)
    await user.type(name, '  Home {Enter}')
    await waitFor(async () =>
      expect((await spaces(server)).map((s) => [s.name, s.icon])).toEqual([
        ['Home', 'castle'],
        ['Work', ''],
      ]),
    )
    expect(await screen.findByRole('dialog', { name: 'Home' })).toBeTruthy()
    expect(screen.queryByRole('dialog', { name: 'Edit space' })).toBeNull()

    // An empty name is put back rather than saved.
    await user.clear(name)
    await user.tab()
    expect((name as HTMLInputElement).value).toBe('Home')
  })

  it('shows the notes that stopped a delete', async () => {
    const { server, user } = await withWork()
    await server.api('PUT', '/spaces/empty', { name: 'Empty' })
    const info = await infoFromSettings(user, 'Empty')
    const remove = within(info).getByRole('button', { name: 'Delete space…' })
    expect(remove.hasAttribute('disabled')).toBe(false)
    // Another device files a note there meanwhile.
    await noteIn(server, 'empty', 'Late', 'x\n')
    await user.click(remove)
    await user.click(within(within(info).getByRole('group', { name: 'Delete Empty?' })).getByRole('button', { name: 'Delete' }))

    expect((await within(info).findByRole('alert')).textContent).toMatch(/another device/)
    await waitFor(() => expect(within(info).getByRole('button', { name: 'Delete space…' }).hasAttribute('disabled')).toBe(true))
    expect(within(info).getByText('1 note')).toBeTruthy()
  })

  it('only deletes an empty space, and says why not', async () => {
    const { server, user } = await withWork()
    await server.api('PUT', '/spaces/empty', { name: 'Empty' })

    let info = await infoFromSettings(user, 'Work')
    let remove = within(info).getByRole('button', { name: 'Delete space…' })
    expect(remove.hasAttribute('disabled')).toBe(true)
    expect(remove.getAttribute('aria-describedby')).toBeTruthy()
    expect(within(info).getByText('Move or delete its notes to delete it.')).toBeTruthy()
    await user.click(within(info).getByRole('button', { name: 'Done' }))

    info = await infoFromSettings(user, 'Notes')
    expect(within(info).getByRole('button', { name: 'Delete space…' }).hasAttribute('disabled')).toBe(true)
    expect(within(info).getByText(/can be renamed, not deleted/)).toBeTruthy()
    await user.click(within(info).getByRole('button', { name: 'Done' }))

    info = await infoFromSettings(user, 'Empty')
    remove = within(info).getByRole('button', { name: 'Delete space…' })
    await user.click(remove)
    // Asked in place first; Cancel leaves it.
    await user.click(within(within(info).getByRole('group', { name: 'Delete Empty?' })).getByRole('button', { name: 'Cancel' }))
    expect(within(info).queryByRole('group', { name: 'Delete Empty?' })).toBeNull()
    // Back on Delete, though it was off while the confirmation was open.
    expect(document.activeElement).toBe(remove)
    await user.click(remove)
    await user.click(within(within(info).getByRole('group', { name: 'Delete Empty?' })).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Empty' })).toBeNull())
    const card = screen.getByRole('region', { name: 'Spaces' })
    await waitFor(() => expect(within(card).queryByText('Empty')).toBeNull())
    expect((await spaces(server)).map((s) => s.id)).toEqual(['default', 'work'])
  })
})

describe('Space info', () => {
  it('opens from the switcher’s card, for the space shown', async () => {
    const { user } = await withWork()
    await switchTo(user, 'Work')
    // No traits: nothing beside the switcher.
    expect(within(panel()).queryByRole('button', { name: /^Space info/ })).toBeNull()
    await user.click(switcher())
    await user.click(within(popover()).getByRole('button', { name: 'Space info' }))

    const info = await screen.findByRole('dialog', { name: 'Work' })
    expect(within(info).getByText('2 notes')).toBeTruthy()
    // Without traits, sharing is its People row, to invite from: not a feature turned off.
    const people = within(info).getByRole('button', { name: 'People' })
    expect(people.textContent).toBe('PeopleOnly you')
    expect(within(info).queryByText('Off')).toBeNull()
    // Focus starts on the dialog, not its first control.
    expect(document.activeElement).toBe(info)
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Work' })).toBeNull())
  })
})

describe('traits', () => {
  afterEach(() => vi.restoreAllMocks())

  const shared: SpaceTrait = { key: 'shared', label: 'Shared', summary: 'With Ana and Ben', exposure: 'Visible to Ana and Ben' }
  const published: SpaceTrait = {
    key: 'published',
    label: 'Published',
    summary: 'At notes.example',
    exposure: 'Public at notes.example',
    problem: 'Publishing failed',
  }
  const calendar: SpaceTrait = { key: 'calendar', label: 'Calendar', summary: 'Synced with iCloud', exposure: 'Sent to Apple' }

  /** Work as a newer server would send it: shared, published (failing), and a trait this app doesn't know. */
  async function withTraits() {
    const fetchList = spacesApi.list
    vi.spyOn(spacesApi, 'list').mockImplementation(async () => {
      const body = await fetchList()
      const work = body.spaces.find((s) => s.id === 'work')
      if (work) work.traits = [shared, published, calendar]
      return body
    })
    return withWork()
  }

  it('shows beside the switcher and in its list and card, and open Space info', async () => {
    const { user } = await withTraits()
    await user.click(await waitFor(() => switcher()))
    // Another space's row: each trait's label, a problem in its place.
    const row = spaceRow('Work')
    expect(row.getAttribute('aria-label')).toBe('Work, Shared, Published (Publishing failed), Calendar')
    // One line of glyphs after the name; words only for the problem.
    expect(row.textContent).toBe('WorkPublishing failed')
    await user.click(row)

    // In Work, the card words each trait; Notes is the one to switch to.
    await user.click(switcher())
    const card = within(popover()).getByRole('region', { name: 'Work' })
    expect(within(card).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'Shared · With Ana and Ben',
      'Publishing failed',
      'Calendar · Synced with iCloud',
    ])
    expect(spaceRow('Notes').getAttribute('aria-label')).toBe('Notes')
    expect(spaceRow('Notes').textContent).toBe('Notes')
    expect(within(popover()).queryByRole('button', { name: /^Work, / })).toBeNull()
    await user.keyboard('{Escape}')

    const glyphs = await within(panel()).findByRole('button', { name: 'Space info: Shared, Published (Publishing failed), Calendar' })
    await user.click(glyphs)
    const info = await screen.findByRole('dialog', { name: 'Work' })
    // A row each: sharing and publishing open their pages, a problem said in its row; one this app doesn't know
    // has nothing to open.
    const rows = within(within(info).getByRole('list', { name: 'Work’s settings' })).getAllByRole('listitem')
    expect(rows.map((r) => r.querySelector('button') !== null)).toEqual([true, true, false])
    expect(rows[1]?.textContent).toBe('WebsitePublishing failed')
    expect(rows[2]?.textContent).toBe('CalendarSynced with iCloud · Update the app to change this.')
  })

  it('asks before moving a note where more people see it', async () => {
    const { server, user } = await withTraits()
    await openNote(user, 'Groceries')
    const moveTo = async () => {
      await user.click(await screen.findByRole('button', { name: 'More' }))
      await user.click(screen.getByRole('menuitem', { name: 'Move to space…' }))
      const dialog = await screen.findByRole('dialog', { name: 'Move to space' })
      await user.click(within(dialog).getByRole('button', { name: /^Work/ }))
      return screen.findByRole('dialog', { name: 'Move “Groceries” to Work?' })
    }

    let confirm = await moveTo()
    expect(within(confirm).getByText('In Work, this note will be:')).toBeTruthy()
    expect(within(confirm).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'Visible to Ana and Ben',
      'Public at notes.example',
      'Sent to Apple',
    ])
    await user.click(within(confirm).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(rowTitles()).toEqual(['Groceries'])

    confirm = await moveTo()
    await user.click(within(confirm).getByRole('button', { name: 'Move' }))
    expect(await screen.findByText('Moved to Work')).toBeTruthy()
    await waitFor(async () => {
      const work = (await server.api<{ notes: Note[] }>('GET', '/notes?space=work')).body.notes
      expect(work.map((n) => n.title)).toContain('Groceries')
    })
  })

  it('asks before moving a note out, saying who will no longer see it', async () => {
    const { user } = await withTraits()
    await switchTo(user, 'Work')
    await openNote(user, 'Budget')
    await user.click(await screen.findByRole('button', { name: 'More' }))
    await user.click(screen.getByRole('menuitem', { name: 'Move to space…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Move to space' })
    await user.click(within(dialog).getByRole('button', { name: 'Notes' }))

    const confirm = await screen.findByRole('dialog', { name: 'Move “Budget” to Notes?' })
    expect(within(confirm).getByText('It will no longer be:')).toBeTruthy()
    expect(within(confirm).queryByText(/this note will be/)).toBeNull()
    expect(within(confirm).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'Visible to Ana and Ben',
      'Public at notes.example',
      'Sent to Apple',
    ])
    await user.click(within(confirm).getByRole('button', { name: 'Move' }))
    expect(await screen.findByText('Moved to Notes')).toBeTruthy()
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
    // Focus goes to the first space to switch to; → reaches its info, ↑ the card's.
    expect(document.activeElement).toBe(spaceRow('Work'))
    await user.keyboard('{ArrowRight}')
    expect(document.activeElement).toBe(within(popover()).getByRole('button', { name: 'Space info: Work' }))
    await user.keyboard('{ArrowUp}')
    expect(document.activeElement).toBe(within(popover()).getByRole('button', { name: 'Space info' }))
    await user.keyboard('{End}')
    expect(document.activeElement).toBe(within(popover()).getByRole('button', { name: 'New space…' }))
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog', { name: 'Spaces' })).toBeNull()
    expect(document.activeElement).toBe(switcher())

    // Typing a name's first letter jumps to it.
    await user.keyboard('{ArrowDown}w{Enter}')
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
