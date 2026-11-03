import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { body, createNote, getNote, noteTitle, openNote as openInList, phoneLayout, saved, selectInBody, signedIn } from './helpers'

async function openNote(title: string, content: string) {
  let id = ''
  const ctx = await signedIn({
    seed: async (s) => {
      id = await createNote(s, title, content)
    },
  })
  await waitFor(() => expect(noteTitle()).toBe(title))
  return { ...ctx, id }
}

const findBar = () => screen.getByRole('search', { name: 'Find in note' })
const findField = () => within(findBar()).getByRole('textbox', { name: 'Find' })
const replaceField = () => within(findBar()).getByRole('textbox', { name: 'Replace with' })
const count = () => within(findBar()).getByRole('status').textContent
const highlighted = () => Array.from(body().querySelectorAll('[data-find]'), (el) => el.textContent)
const current = () => body().querySelector('[data-find="current"]')?.textContent

describe('find', () => {
  it('opens from the More menu and finds as you type, ignoring case and formatting', async () => {
    const { user } = await openNote('Milk run', '**Mi**lk, then milk.\n\nMore MILK\n')
    await user.click(screen.getByRole('button', { name: 'More' }))
    await user.click(within(screen.getByRole('menu', { name: 'More' })).getByRole('menuitem', { name: /^Find…/ }))

    expect(document.activeElement).toBe(findField())
    // No replace row for plain Find.
    expect(within(findBar()).queryByRole('textbox', { name: 'Replace with' })).toBeNull()

    await user.type(findField(), 'milk')
    // The title counts too.
    expect(count()).toBe('1 of 4')
    expect(highlighted()).toEqual(['Milk', 'Mi', 'lk', 'milk', 'MILK'])

    await user.keyboard('{Enter}')
    expect(count()).toBe('2 of 4')
    expect(current()).toBe('Mi')
    await user.keyboard('{Shift>}{Enter}{/Shift}')
    await user.keyboard('{Shift>}{Enter}{/Shift}')
    expect(count()).toBe('4 of 4')
    expect(current()).toBe('MILK')

    await user.type(findField(), 'shake')
    expect(count()).toBe('No results')
    expect(highlighted()).toEqual([])
  })

  it('starts at the cursor and closes with Escape, leaving the match selected', async () => {
    const { user } = await openNote('Notes', 'one cat\n\ntwo cat\n\nthree cat\n')
    selectInBody('two')
    await user.keyboard('{Control>}f{/Control}')

    // Seeded from the selection.
    expect(findField()).toHaveProperty('value', 'two')
    await user.clear(findField())
    await user.type(findField(), 'cat')
    expect(count()).toBe('2 of 3')

    await user.keyboard('{Control>}g{/Control}')
    expect(count()).toBe('3 of 3')
    await user.keyboard('{F3}')
    expect(count()).toBe('1 of 3')

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('search', { name: 'Find in note' })).toBeNull()
    expect(highlighted()).toEqual([])
    await waitFor(() => expect(document.activeElement).toBe(body()))
    expect(window.getSelection()?.toString()).toBe('cat')
  })

  it('remembers the last search', async () => {
    const { user } = await openNote('Notes', 'apple pie\n')
    selectInBody('pie')
    await user.keyboard('{Control>}f{/Control}')
    await user.clear(findField())
    await user.type(findField(), 'apple')
    await user.keyboard('{Escape}')
    await user.keyboard('{Control>}f{/Control}')
    expect(findField()).toHaveProperty('value', 'apple')
    expect(count()).toBe('1 of 1')
  })
})

describe('find and replace', () => {
  it('counts replacements once none are left', async () => {
    const { user } = await openNote('Pets', 'cat and cat\n')
    selectInBody('cat')
    await user.keyboard('{Control>}h{/Control}')
    await user.type(replaceField(), 'dog{Enter}')
    expect(count()).toBe('1 of 1')
    await user.keyboard('{Enter}')
    expect(count()).toBe('Replaced 2')
    expect(body().textContent).toContain('dog and dog')
  })

  it('replaces one match at a time, keeping its formatting, and saves', async () => {
    const { server, user, id } = await openNote('Groceries', '**milk** and milk\n')
    await user.click(screen.getByRole('button', { name: 'More' }))
    await user.click(within(screen.getByRole('menu', { name: 'More' })).getByRole('menuitem', { name: /^Find and replace…/ }))

    await user.type(findField(), 'milk')
    await user.type(replaceField(), 'oat milk')
    expect(count()).toBe('1 of 2')

    // Enter in the replace field replaces and moves on, past the new text though it matches too.
    await user.keyboard('{Enter}')
    expect(count()).toBe('2 of 2')
    await user.click(within(findBar()).getByRole('button', { name: 'Replace' }))
    expect(highlighted()).toEqual(['milk', 'milk'])

    await saved()
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('**oat milk** and oat milk\n'))
  })

  it('replaces all in one undo step', async () => {
    const { server, user, id } = await openNote('Pets', 'cat, cat and cat\n')
    selectInBody('cat')
    await user.keyboard('{Control>}h{/Control}')
    expect(findField()).toHaveProperty('value', 'cat')
    // With something to find, the replace field takes focus.
    expect(document.activeElement).toBe(replaceField())

    await user.type(replaceField(), 'dog')
    await user.click(within(findBar()).getByRole('button', { name: 'Replace all' }))
    expect(count()).toBe('Replaced 3')
    // Not stranded on the now-disabled button.
    expect(document.activeElement).toBe(replaceField())
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('dog, dog and dog\n'))

    await user.keyboard('{Escape}')
    await waitFor(() => expect(document.activeElement).toBe(body()))
    await user.keyboard('{Control>}z{/Control}')
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('cat, cat and cat\n'))
  })

  it('toggles the replace row from the bar', async () => {
    const { user } = await openNote('Notes', 'text\n')
    selectInBody('text')
    await user.keyboard('{Control>}f{/Control}')
    const toggle = within(findBar()).getByRole('button', { name: 'Find and replace' })
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    await user.click(toggle)
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    expect(replaceField()).toBeTruthy()
  })
})

describe('find on a phone', () => {
  it('is not offered', async () => {
    phoneLayout()
    const { user } = await signedIn({ seed: async (s) => void (await createNote(s, 'Notes', 'text')) })
    await openInList(user, 'Notes')
    await user.click(await screen.findByRole('button', { name: 'Share' }))
    expect(within(screen.getByRole('menu', { name: 'Share' })).queryByRole('menuitem', { name: /Find/ })).toBeNull()
    selectInBody('text')
    await user.keyboard('{Control>}f{/Control}')
    expect(screen.queryByRole('search', { name: 'Find in note' })).toBeNull()
  })
})
