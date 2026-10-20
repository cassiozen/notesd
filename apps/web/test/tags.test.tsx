import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { App } from '../src/app'
import {
  bodyText,
  createNote,
  list,
  listNotes,
  mediumLayout,
  newNoteButton,
  noteTitle,
  openNote,
  phoneLayout,
  row,
  signedIn,
  typeAtEnd,
  typeInTitle,
} from './helpers'

async function withTags() {
  return signedIn({
    seed: async (s) => {
      await createNote(s, 'Roof', 'Call about #home/repairs today\n')
      await createNote(s, 'Garden', 'Plant bulbs #Home\n')
      await createNote(s, 'Standup', 'Notes #work\n')
      await createNote(s, 'Loose', 'No tags here\n')
    },
  })
}

const panel = () => screen.getByRole('navigation', { name: 'Sidebar' })
const tags = () => {
  // The first list; expanded parents nest their own.
  const [top] = within(within(panel()).getByRole('region', { name: 'Tags' })).getAllByRole('list')
  if (!top) throw new Error('no tag list')
  return top
}
const tagRow = (name: string) => within(tags()).getByRole('button', { name: new RegExp(`^#${name},`, 'i') })
const rowTitles = () =>
  within(list())
    .queryAllByRole('link')
    .map((a) => a.querySelector('span')?.textContent)

describe('tags in the sidebar', () => {
  it('lists tags with counts, nested ones under their parent', async () => {
    const { user } = await withTags()
    await waitFor(() => tags())

    expect(tagRow('home').getAttribute('aria-label')).toBe('#Home, 2 notes')
    expect(tagRow('work').getAttribute('aria-label')).toBe('#work, 1 note')
    expect(within(tags()).queryByRole('button', { name: /^#home\/repairs/ })).toBeNull()

    await user.click(within(tags()).getByRole('button', { name: 'Expand Home' }))
    expect(within(tags()).getByRole('button', { name: /^#home\/repairs, 1 note/ })).toBeTruthy()
  })

  it('filters the list by a tag and its nested tags, and back to all notes', async () => {
    const { user } = await withTags()
    await user.click(await waitFor(() => tagRow('home')))

    expect(await within(list()).findByRole('heading', { name: /#Home/ })).toBeTruthy()
    await waitFor(() => expect(rowTitles().toSorted()).toEqual(['Garden', 'Roof']))
    expect(within(list()).getByText('2 notes')).toBeTruthy()
    expect(screen.getByRole('searchbox', { name: 'Search notes' }).getAttribute('placeholder')).toBe('Search in #Home')

    await user.click(within(panel()).getByRole('button', { name: 'Untagged' }))
    await waitFor(() => expect(rowTitles()).toEqual(['Loose']))

    await user.click(within(list()).getByRole('button', { name: 'Show all notes' }))
    await waitFor(() => expect(rowTitles()).toHaveLength(4))
  })

  it('searches within the selected tag', async () => {
    const { user } = await withTags()
    await user.click(await waitFor(() => tagRow('work')))
    await within(list()).findByRole('heading', { name: /#work/ })
    await user.type(screen.getByRole('searchbox', { name: 'Search notes' }), 'notes')
    const results = await screen.findByRole('list', { name: 'Search results' })
    await waitFor(() => expect(within(results).getAllByRole('link')).toHaveLength(1))
  })

  it('updates as notes gain and lose tags', async () => {
    const { user } = await withTags()
    await openNote(user, 'Loose')
    await typeAtEnd(user, ' #errands')
    await waitFor(() => expect(tagRow('errands')).toBeTruthy(), { timeout: 3000 })

    // Filtered, a note that loses the tag leaves the list.
    await user.click(tagRow('errands'))
    await waitFor(() => expect(rowTitles()).toEqual(['Loose']))
    await typeAtEnd(user, '{Backspace}'.repeat(' #errands'.length))
    await waitFor(() => expect(rowTitles()).toEqual([]), { timeout: 3000 })
  })
})

describe('new note in a tag', () => {
  it('starts with the tag, and is dropped if nobody types', async () => {
    const { server, user } = await withTags()
    await user.click(await waitFor(() => tagRow('work')))
    await within(list()).findByRole('heading', { name: /#work/ })

    await user.click(newNoteButton())
    await waitFor(() => expect(bodyText()).toBe('#work'))
    await user.click(row('Standup'))
    await waitFor(() => expect(within(list()).queryByRole('link', { name: /^New note/ })).toBeNull())
    expect(await listNotes(server)).toHaveLength(4)

    await user.click(newNoteButton())
    await typeInTitle(user, 'Retro')
    await waitFor(async () => {
      const retro = (await server.api<{ notes: { title: string }[] }>('GET', '/notes?tag=work')).body.notes
      expect(retro.map((n) => n.title).toSorted()).toEqual(['Retro', 'Standup'])
    })
    expect(noteTitle()).toBe('Retro')
  })
})

describe('typing a tag', () => {
  it('suggests existing tags and inserts the chosen one', async () => {
    const { user } = await withTags()
    await openNote(user, 'Loose')
    await waitFor(() => tagRow('home'))

    await typeAtEnd(user, ' #rep')
    const options = await screen.findByRole('listbox', { name: 'Tags' })
    expect(within(options).getAllByRole('option').map((o) => o.textContent)).toEqual(['#home/repairs'])
    await user.keyboard('{Enter}')
    await waitFor(() => expect(bodyText()).toBe('No tags here #home/repairs '))
  })
})

describe('tags sidebar', () => {
  it('hides and comes back, and stays as left', async () => {
    const { user } = await withTags()
    await user.click(within(panel()).getByRole('button', { name: 'Hide sidebar' }))
    expect(screen.queryByRole('navigation', { name: 'Sidebar' })).toBeNull()

    await user.click(within(list()).getByRole('button', { name: 'Show sidebar' }))
    expect(panel()).toBeTruthy()
    expect(within(list()).queryByRole('button', { name: 'Show sidebar' })).toBeNull()
    expect(localStorage.getItem('notes.sidebar.hidden')).toBe('false')
  })

  it('reopens on the list last shown', async () => {
    const { user } = await withTags()
    await user.click(await waitFor(() => tagRow('work')))
    await within(list()).findByRole('heading', { name: /#work/ })

    cleanup()
    render(<App />)
    expect(await within(await screen.findByRole('complementary', { name: 'Notes' })).findByRole('heading', { name: /#work/ })).toBeTruthy()
    await waitFor(() => expect(rowTitles()).toEqual(['Standup']))
  })
})

describe('tags screen on a phone', () => {
  it('goes from the list to the tags and back to the list, filtered', async () => {
    phoneLayout()
    const { user } = await withTags()
    expect(screen.queryByRole('navigation', { name: 'Sidebar' })).toBeNull()

    await user.click(within(list()).getByRole('button', { name: 'Sidebar' }))
    expect(window.location.pathname).toBe('/tags')
    expect(screen.queryByRole('complementary', { name: 'Notes' })).toBeNull()
    await user.click(await waitFor(() => tagRow('home')))

    expect(window.location.pathname).toBe('/')
    expect(await within(list()).findByRole('heading', { name: /#Home/ })).toBeTruthy()
    await waitFor(() => expect(rowTitles().toSorted()).toEqual(['Garden', 'Roof']))
  })

  it('opens settings from the tags screen and returns to it', async () => {
    phoneLayout()
    const { user } = await withTags()
    await user.click(within(list()).getByRole('button', { name: 'Sidebar' }))
    await user.click(within(panel()).getByRole('button', { name: 'Settings' }))
    await user.click(await screen.findByRole('button', { name: 'Done' }))
    expect(window.location.pathname).toBe('/tags')
    expect(panel()).toBeTruthy()
  })
})

describe('tags screen beside a note', () => {
  it('takes the list’s place and keeps the note open', async () => {
    mediumLayout()
    const { user } = await withTags()
    await openNote(user, 'Loose')
    await user.click(within(list()).getByRole('button', { name: 'Sidebar' }))

    expect(screen.queryByRole('complementary', { name: 'Notes' })).toBeNull()
    expect(noteTitle()).toBe('Loose')
    await user.click(await waitFor(() => tagRow('work')))
    expect(await within(list()).findByRole('heading', { name: /#work/ })).toBeTruthy()
    expect(noteTitle()).toBe('Loose')
  })
})
