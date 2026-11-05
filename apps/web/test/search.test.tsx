import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { createNote, interceptApi, list, noteTitle, signedIn } from './helpers'

async function withNotes() {
  return signedIn({
    seed: async (s) => {
      await createNote(s, 'Groceries', 'Oat milk and **bread**\n')
      await createNote(s, 'Trip', 'Book the room\n')
      await createNote(s, 'Milkshake recipe', 'Bananas\n')
    },
  })
}

const search = () => screen.getByRole('searchbox', { name: 'Search notes' })

describe('search', () => {
  it('shows matching notes with highlighted excerpts, prefix matching included', async () => {
    const { user } = await withNotes()

    await user.type(search(), 'milk')

    expect(await within(list()).findByRole('heading', { name: 'Top results' })).toBeTruthy()
    const results = await screen.findByRole('list', { name: 'Search results' })
    await waitFor(() => expect(within(results).getAllByRole('link')).toHaveLength(2))
    expect(within(list()).getByText('2 results')).toBeTruthy()
    const groceries = within(results).getByRole('link', { name: /Groceries/ })
    expect(groceries.querySelector('mark')?.textContent?.toLowerCase()).toBe('milk')
    expect(within(results).queryByRole('link', { name: /Trip/ })).toBeNull()
  })

  it('keeps the last results up while the next words load, without acting on them', async () => {
    const { user } = await withNotes()
    await user.type(search(), 'milk')
    const results = await screen.findByRole('list', { name: 'Search results' })
    await waitFor(() => expect(within(results).getAllByRole('link')).toHaveLength(2))
    const opened = noteTitle()

    let release: (() => void) | undefined
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    interceptApi(
      (_, path) => path.endsWith('/search'),
      async (_, __, real) => {
        await held
        return real()
      },
    )
    await user.type(search(), 'shake')
    // Still the old words' two results, marked busy; Enter waits for the real answer.
    await new Promise((resolve) => setTimeout(resolve, 300))
    const stale = screen.getByRole('list', { name: 'Search results' })
    expect(within(stale).getAllByRole('link')).toHaveLength(2)
    expect(stale.getAttribute('aria-busy')).toBe('true')
    expect(within(list()).getByText('Searching…')).toBeTruthy()
    await user.keyboard('{Enter}')
    expect(noteTitle()).toBe(opened)

    release?.()
    await waitFor(() => expect(within(screen.getByRole('list', { name: 'Search results' })).getAllByRole('link')).toHaveLength(1))
    expect(screen.getByRole('list', { name: 'Search results' }).getAttribute('aria-busy')).toBeNull()
    expect(within(list()).getByText('1 result')).toBeTruthy()
  })

  it('opens a result', async () => {
    const { user } = await withNotes()
    await user.type(search(), 'room')
    const results = await screen.findByRole('list', { name: 'Search results' })
    await user.click(await within(results).findByRole('link', { name: /Trip/ }))
    await waitFor(() => expect(noteTitle()).toBe('Trip'))
  })

  it('opens the top result on Enter', async () => {
    const { user } = await withNotes()
    await user.type(search(), 'banan')
    await screen.findByRole('list', { name: 'Search results' })
    await user.keyboard('{Enter}')
    await waitFor(() => expect(noteTitle()).toBe('Milkshake recipe'))
  })

  it('says when nothing matches, and Escape clears the search', async () => {
    const { user } = await withNotes()
    await user.type(search(), 'zebra')
    expect(await screen.findByText('Nothing matches “zebra”.')).toBeTruthy()

    await user.keyboard('{Escape}')
    expect(search()).toHaveProperty('value', '')
    expect(await within(list()).findByRole('heading', { name: 'Today' })).toBeTruthy()
  })

  it('treats search syntax in the input as plain words', async () => {
    const { user } = await withNotes()
    await user.type(search(), '"milk OR ( NEAR')
    expect(await screen.findByText(/^\d+\+? results?$/)).toBeTruthy()
    expect(screen.queryByText('Search didn’t work.')).toBeNull()
  })
})
