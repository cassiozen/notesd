import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { App } from '../src/app'
import {
  body,
  bodyText,
  createNote,
  deleteNote,
  editElsewhere,
  getNote,
  list,
  listNotes,
  newNoteButton,
  noteTitle,
  op,
  openNote,
  phoneLayout,
  row,
  saved,
  signedIn,
  typeAtEnd,
  typeInTitle,
  type Session,
} from './helpers'
import { createOwner, startServer } from './server'

describe('creating', () => {
  it('saves a new note on its first edit: first line title, the rest body', async () => {
    const { server, user } = await signedIn()
    expect(screen.getByText('No notes yet', { selector: 'section span' })).toBeTruthy()

    await user.click(newNoteButton())
    expect(within(list()).getByRole('link', { name: /^New note/ })).toBeTruthy()
    // Not in the URL until the server has it.
    expect(window.location.pathname).toBe('/')

    await typeInTitle(user, 'Groceries{Enter}Milk and eggs')
    expect(noteTitle()).toBe('Groceries')
    expect(bodyText()).toBe('Milk and eggs')
    await waitFor(() => expect(window.location.pathname).toMatch(/^\/notes\//))
    await waitFor(async () => {
      const [note] = await listNotes(server)
      expect(note?.title).toBe('Groceries')
      expect(note?.preview).toBe('Milk and eggs')
    })
    await waitFor(() => expect(within(row('Groceries')).getByText('Milk and eggs')).toBeTruthy())
    expect(within(list()).getByText('1 note')).toBeTruthy()
  })

  it('drops a new note nobody typed into', async () => {
    const { server, user } = await signedIn({
      seed: async (s) => {
        await createNote(s, 'Keep')
      },
    })

    await user.click(newNoteButton())
    expect(row('New note')).toBeTruthy()
    await user.click(row('Keep'))

    await waitFor(() => expect(within(list()).queryByRole('link', { name: /^New note/ })).toBeNull())
    expect((await listNotes(server)).map((n) => n.title)).toEqual(['Keep'])
  })

  it('opens a note from its URL on reload', async () => {
    const server = await startServer()
    const { username, password } = await createOwner(server)
    await server.api('POST', '/auth/login', { username, password, client: 'web' })
    const id = await createNote(server, 'Deep link', 'Hello')
    await createNote(server, 'Newer')
    window.history.replaceState(null, '', `/notes/${id}`)
    const user = userEvent.setup()
    render(<App />)
    await user.type(await screen.findByLabelText('Username'), username)
    await user.type(screen.getByLabelText('Password'), `${password}{Enter}`)

    await waitFor(() => expect(noteTitle()).toBe('Deep link'))
    expect(bodyText()).toBe('Hello')
    await waitFor(() => expect(row('Deep link').getAttribute('aria-current')).toBe('page'))
  })

  it('says so when the linked note is gone', async () => {
    await signedIn({ path: '/notes/does-not-exist' })
    expect(await screen.findByRole('heading', { name: 'This note doesn’t exist.' })).toBeTruthy()
  })
})

describe('the first line', () => {
  it('Enter inside the first line moves the rest of it into the body', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Shopping Buy milk')
      },
    })
    await waitFor(() => expect(noteTitle()).toBe('Shopping Buy milk'))

    const text = body().querySelector('[data-title]')?.firstChild
    if (!text) throw new Error('no title text')
    body().focus()
    const range = document.createRange()
    range.setStart(text, 'Shopping '.length)
    range.collapse(true)
    window.getSelection()?.removeAllRanges()
    window.getSelection()?.addRange(range)
    document.dispatchEvent(new Event('selectionchange'))
    await user.keyboard('{Enter}')

    expect(noteTitle()).toBe('Shopping ')
    expect(bodyText()).toBe('Buy milk')
    await waitFor(async () => expect(await getNote(server, id)).toMatchObject({ title: 'Shopping ', content: 'Buy milk\n' }))
  })

  it('keeps formatting out of the title', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Plain', 'Text')
      },
    })
    await waitFor(() => expect(noteTitle()).toBe('Plain'))
    await typeInTitle(user, '{Control>}b{/Control} title')
    await saved()
    await waitFor(async () => expect(await getNote(server, id)).toMatchObject({ title: 'Plain title', content: 'Text\n' }))
  })
})

describe('editing', () => {
  it('autosaves edits to an existing note', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Plan', 'Step one')
      },
    })
    await openNote(user, 'Plan')

    await waitFor(() => expect(bodyText()).toBe('Step one'))
    await typeAtEnd(user, ', step two')
    await saved()
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('Step one, step two\n'))
    expect((await getNote(server, id)).revision).toBeGreaterThan(1)
  })

  it('updates the list row while typing, before the save lands', async () => {
    const { server, user } = await signedIn({
      seed: async (s) => {
        await createNote(s, 'Old title', 'Old body')
        // Distinct timestamps, so the order is certain.
        await new Promise((r) => setTimeout(r, 5))
        await createNote(s, 'Newest')
      },
    })
    await openNote(user, 'Old title')
    expect(within(list()).getAllByRole('link')[0]?.textContent).toMatch(/^Newest/)

    server.setOffline(true)
    await typeInTitle(user, ' renamed')
    await typeAtEnd(user, ' and more')

    await waitFor(() => expect(row('Old title renamed').textContent).toMatch(/Old body and more$/))
    // Edited just now: it moves to the top.
    expect(within(list()).getAllByRole('link')[0]).toBe(row('Old title renamed'))
    server.setOffline(false)
  })

  it('merges a title changed elsewhere with content changed here', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Shopping', 'Buy milk')
      },
    })
    await openNote(user, 'Shopping')
    await waitFor(() => expect(bodyText()).toBe('Buy milk'))

    await editElsewhere(server, id, { title: 'Groceries' })
    await typeAtEnd(user, ' and bread')

    await waitFor(() => expect(noteTitle()).toBe('Groceries'))
    await saved()
    await waitFor(async () => {
      const note = await getNote(server, id)
      expect(note).toMatchObject({ title: 'Groceries', content: 'Buy milk and bread\n' })
    })
    expect(screen.queryByText(/Changed on another device/)).toBeNull()
  })

  it('keeps both versions when the content conflicts', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Trip', 'Pack light')
      },
    })
    await openNote(user, 'Trip')
    await waitFor(() => expect(bodyText()).toBe('Pack light'))

    await editElsewhere(server, id, { content: 'Pack light, bring a charger' })
    await typeAtEnd(user, ', no laptop')

    expect(await screen.findByText(/Changed on another device/)).toBeTruthy()
    expect(screen.getByText('Conflict')).toBeTruthy()
    // Nothing is overwritten while the user decides.
    expect((await getNote(server, id)).content).toBe('Pack light, bring a charger')

    await user.click(screen.getByRole('button', { name: 'Keep mine' }))
    await waitFor(() => expect(bodyText()).toBe('Pack light, bring a charger'))
    await waitFor(async () => {
      const copy = (await listNotes(server)).find((n) => n.title === 'Trip (Conflicted copy)')
      expect(copy?.preview).toBe('Pack light, no laptop')
      expect((await getNote(server, copy?.id ?? '')).conflictOf).toBe(id)
    })
    expect((await getNote(server, id)).content).toBe('Pack light, bring a charger')
    expect(await within(list()).findByRole('link', { name: /^Trip \(Conflicted copy\)/ })).toBeTruthy()
  })

  it('can take the other version instead', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Trip', 'Pack light')
      },
    })
    await openNote(user, 'Trip')
    await waitFor(() => expect(bodyText()).toBe('Pack light'))

    await editElsewhere(server, id, { content: 'Theirs' })
    await typeAtEnd(user, ' mine')
    await user.click(await screen.findByRole('button', { name: 'Use theirs' }))

    expect(bodyText()).toBe('Theirs')
    expect(screen.getByText('Saved')).toBeTruthy()
    // Editing continues against their revision.
    await typeAtEnd(user, ' and more')
    await saved()
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('Theirs and more\n'))
    expect((await listNotes(server)).map((n) => n.title)).toEqual(['Trip'])
  })

  it('recovers edits to a note deleted elsewhere', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Ideas', 'One')
      },
    })
    await openNote(user, 'Ideas')
    await waitFor(() => expect(bodyText()).toBe('One'))

    const note = await getNote(server, id)
    await server.api('DELETE', `/notes/${id}`, { operationId: op(), baseRevision: note.revision })
    await typeAtEnd(user, ', two')

    expect(await screen.findByText('This note was deleted on another device.')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Keep as new note' }))

    await waitFor(() => expect(noteTitle()).toBe('Ideas (Recovered copy)'))
    expect(bodyText()).toBe('One, two')
    await waitFor(async () => {
      const notes = await listNotes(server)
      expect(notes.map((n) => [n.title, n.preview])).toEqual([['Ideas (Recovered copy)', 'One, two']])
    })
    await waitFor(() => expect(window.location.pathname).not.toBe(`/notes/${id}`))
    expect(within(list()).queryByRole('link', { name: /^Ideas One/ })).toBeNull()
  })
})

describe('failures', () => {
  it('keeps edits through a lost connection and saves them once it is back', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Offline', 'Start')
      },
    })
    await openNote(user, 'Offline')
    await waitFor(() => expect(bodyText()).toBe('Start'))

    server.setOffline(true)
    await typeAtEnd(user, ' typed offline')
    expect(await screen.findByText(/Couldn’t reach the server/)).toBeTruthy()
    expect(screen.getByText('Couldn’t save')).toBeTruthy()

    server.setOffline(false)
    await saved()
    expect(screen.queryByText(/Couldn’t reach the server/)).toBeNull()
    expect((await getNote(server, id)).content).toBe('Start typed offline\n')
  })

  it('replays a save whose response was lost instead of applying it twice', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Replay', 'A')
      },
    })
    await openNote(user, 'Replay')
    await waitFor(() => expect(bodyText()).toBe('A'))

    server.loseNextResponse((method) => method === 'PUT')
    await typeAtEnd(user, 'B')
    await saved()

    const note = await getNote(server, id)
    expect(note.content).toBe('AB\n')
    // One update: the retry was answered from the idempotency record, not as a conflict.
    expect(note.revision).toBe(2)
    expect(screen.queryByText(/Changed on another device/)).toBeNull()
  })

  it('reports a save the server refuses instead of retrying it as offline', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Refused', 'A')
      },
    })
    await openNote(user, 'Refused')

    // What a dev proxy that rewrites Host looks like to the server.
    server.setOrigin('http://localhost:5173')
    await typeAtEnd(user, 'B')

    expect(await screen.findByText(/The server didn’t accept this save: cross-origin request rejected/)).toBeTruthy()
    expect(screen.getByText('Couldn’t save')).toBeTruthy()
    expect(screen.queryByText(/Couldn’t reach the server/)).toBeNull()
    expect((await getNote(server, id)).content).toBe('A')

    server.setOrigin(server.url)
    await user.click(screen.getByRole('button', { name: 'Retry' }))
    await saved()
    expect((await getNote(server, id)).content).toBe('AB\n')
  })

  it('asks to sign in again when the session ends mid-edit, then saves', async () => {
    let id = ''
    const { server, user, password } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Session', 'Before')
      },
    })
    await openNote(user, 'Session')
    await waitFor(() => expect(bodyText()).toBe('Before'))

    // Revoke the browser's session from the other client.
    const { body: sessions } = await server.api<{ sessions: Session[]; currentId: string }>('GET', '/auth/sessions')
    const browserSession = sessions.sessions.find((s) => s.id !== sessions.currentId)
    await server.api('DELETE', `/auth/sessions/${browserSession?.id}`)

    await typeAtEnd(user, ' after')
    const dialog = await screen.findByRole('dialog', { name: 'Sign in again' })
    // The editor is still there underneath with the unsaved text.
    expect(bodyText()).toBe('Before after')
    // A reopened app shouldn't draw the shell for a session that's gone.
    expect(localStorage.getItem('notes.signed-in')).toBeNull()

    await user.type(within(dialog).getByLabelText('Password'), `${password}{Enter}`)
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Sign in again' })).toBeNull())
    expect(localStorage.getItem('notes.signed-in')).toBe('true')
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('Before after\n'))
    await saved()
  })
})

describe('deleting', () => {
  it('hides the note at once, offers Undo, and deletes it after a moment', async () => {
    const { server, user } = await signedIn({
      seed: async (s) => {
        await createNote(s, 'Keep')
        await createNote(s, 'Remove me', 'Some text')
      },
    })
    await openNote(user, 'Remove me')

    await deleteNote(user)
    expect(within(list()).queryByRole('link', { name: /^Remove me/ })).toBeNull()
    expect(screen.getByText('Note deleted')).toBeTruthy()
    // The next note opens in its place.
    await waitFor(() => expect(noteTitle()).toBe('Keep'))
    expect(within(list()).getByText('1 note')).toBeTruthy()

    await user.click(screen.getByRole('button', { name: 'Undo' }))
    await waitFor(() => expect(noteTitle()).toBe('Remove me'))
    // The editor reopens it before the list's refresh puts the row back.
    await waitFor(() => expect(row('Remove me').getAttribute('aria-current')).toBe('page'))
    expect((await listNotes(server)).map((n) => n.title).toSorted()).toEqual(['Keep', 'Remove me'])

    await deleteNote(user)
    await waitFor(async () => expect((await listNotes(server)).map((n) => n.title)).toEqual(['Keep']))
    await waitFor(() => expect(screen.queryByText('Note deleted')).toBeNull())
    expect(within(list()).getByText('1 note')).toBeTruthy()
  })

  it('keeps a note that changed elsewhere during the undo window', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Shared', 'Mine')
      },
    })
    await waitFor(() => expect(noteTitle()).toBe('Shared'))

    await deleteNote(user)
    await editElsewhere(server, id, { content: 'Theirs' })

    expect(await screen.findByText('Not deleted: it changed on another device.')).toBeTruthy()
    expect(row('Shared')).toBeTruthy()
    expect((await getNote(server, id)).deletedAt).toBeNull()
  })
})

describe('the list', () => {
  it('previews formatted notes block by block, the same before and after a refetch', async () => {
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        id = await createNote(s, 'Trip', '# Lisbon\n\nLanding **Friday**.\n\n```sh\nmake web\n```\n\n- [x] Book room\n')
      },
    })
    expect(within(row('Trip')).getByText('Lisbon · Landing Friday. · make web · Book room')).toBeTruthy()
    await waitFor(() => expect(noteTitle()).toBe('Trip'))
    expect(within(list()).getByRole('heading', { name: 'Today' })).toBeTruthy()

    await typeAtEnd(user, ' near the river')
    await saved()

    const local = 'Lisbon · Landing Friday. · make web · Book room near the river'
    await waitFor(() => expect(within(row('Trip')).getByText(local)).toBeTruthy())
    const [fromServer] = await listNotes(server)
    expect(fromServer?.id).toBe(id)
    expect(fromServer?.preview.split('\n').join(' · ')).toBe(local)
  })

  it('pages through more than 50 notes and counts them all', async () => {
    const server = await startServer()
    const { username, password } = await createOwner(server)
    await server.api('POST', '/auth/login', { username, password, client: 'web' })
    for (let i = 0; i < 55; i++) await createNote(server, `Note ${i}`)
    const user = userEvent.setup()
    render(<App />)
    await user.type(await screen.findByLabelText('Username'), username)
    await user.type(screen.getByLabelText('Password'), `${password}{Enter}`)

    await waitFor(() => expect(within(list()).getAllByRole('link')).toHaveLength(50))
    expect(within(list()).getByText('55 notes')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(within(list()).getAllByRole('link')).toHaveLength(55))
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull()
  })
})

describe('on a phone', () => {
  it('shows the list or the note, one at a time', async () => {
    phoneLayout()
    const { user } = await signedIn({
      seed: async (s) => {
        await createNote(s, 'Pocket', 'Small screen')
      },
    })
    // Nothing opens by itself.
    expect(screen.queryByRole('textbox', { name: 'Note' })).toBeNull()

    await openNote(user, 'Pocket')
    expect(screen.queryByRole('complementary', { name: 'Notes' })).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Notes' }))
    expect(await screen.findByRole('complementary', { name: 'Notes' })).toBeTruthy()
    expect(screen.queryByRole('textbox', { name: 'Note' })).toBeNull()
    expect(window.location.pathname).toBe('/')
  })
})

describe('window title', () => {
  it('names the open note', async () => {
    const { user } = await signedIn({
      seed: async (s) => {
        await createNote(s, 'Groceries')
        await createNote(s, 'Trip')
      },
    })

    await openNote(user, 'Groceries')
    expect(document.title).toBe('Groceries — notesd')
    await typeInTitle(user, ' list')
    expect(document.title).toBe('Groceries list — notesd')

    await openNote(user, 'Trip')
    expect(document.title).toBe('Trip — notesd')

    await user.click(newNoteButton())
    await waitFor(() => expect(document.title).toBe('New note — notesd'))
  })
})
