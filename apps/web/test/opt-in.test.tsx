import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { App } from '../src/app'
import { timing } from '../src/notes/saver'
import { deviceUser, keepOnDevice } from '../src/auth/session-hint'
import type { Engine } from '../src/offline/tab'
import { inProcessEngine } from './engine/helpers'
import { createNote, getNote, list, listNotes, openNote, openSettings, saved, signedIn, startEngines, typeAtEnd } from './helpers'
import { createOwner, startServer } from './server'

// Choosing to keep the notes in this browser, and leaving it again.

const defaults = { ...timing }
afterEach(() => {
  Object.assign(timing, defaults)
})

/** Engines run in-process; every one started is kept, newest last. */
function engines({ failing = false } = {}) {
  const started: ReturnType<typeof inProcessEngine>[] = []
  startEngines((user, csrf) => {
    const e = inProcessEngine(user, csrf)
    started.push(e)
    if (!failing) return e
    return Object.assign(Object.create(e) as Engine, { call: () => Promise.reject(new Error('No OPFS here')) })
  })
  return started
}

const keepBox = () => screen.queryByRole('checkbox', { name: /Keep my notes on this device/ })

async function signInFromScratch({ keep }: { keep: boolean }) {
  const server = await startServer()
  const { username, password } = await createOwner(server)
  await server.api('POST', '/auth/login', { username, password, client: 'web' })
  const id = await createNote(server, 'Plan', 'One\n')
  const user = userEvent.setup()
  render(<App />)
  await user.type(await screen.findByLabelText('Username'), username)
  await user.type(screen.getByLabelText('Password'), password)
  const box = await screen.findByRole('checkbox', { name: /Keep my notes on this device/ })
  if (keep) await user.click(box)
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
  // Queried afresh each time: the screens remount when the source changes.
  await waitFor(() => expect(within(list()).getByRole('link', { name: /^Plan/ })).toBeTruthy(), { timeout: 3000 })
  return { server, user, id, username }
}

describe('keeping notes on this device', () => {
  it('is offered at sign-in; ticked, the notes come from and save to this device', async () => {
    const started = engines()
    const { server, user, id, username } = await signInFromScratch({ keep: true })
    expect(deviceUser()).toBe(username)
    expect(started).toHaveLength(1)

    await openNote(user, 'Plan')
    server.setOffline(true)
    await typeAtEnd(user, ', two')
    await saved()
    expect((await started[0]?.call('get', id))?.content).toBe('One, two\n')
  })

  it('left unticked, nothing is kept', async () => {
    const started = engines()
    const { username } = await signInFromScratch({ keep: false })
    expect(deviceUser()).not.toBe(username)
    expect(started).toHaveLength(0)
  })

  it('isn’t offered where the browser can’t keep notes', async () => {
    // jsdom has no OPFS, workers or Web Locks: the real check says no.
    const server = await startServer()
    await createOwner(server)
    render(<App />)
    await screen.findByLabelText('Username')
    await new Promise((r) => setTimeout(r, 50))
    expect(keepBox()).toBeNull()
  })

  it('isn’t offered again where the notes are kept already; signing in opens them', async () => {
    const started = engines()
    const server = await startServer()
    const { username, password } = await createOwner(server)
    await server.api('POST', '/auth/login', { username, password, client: 'web' })
    await createNote(server, 'Plan', 'One\n')
    keepOnDevice(username)
    const user = userEvent.setup()
    render(<App />)
    await user.type(await screen.findByLabelText('Username'), username)
    await new Promise((r) => setTimeout(r, 50))
    expect(keepBox()).toBeNull()

    await user.type(screen.getByLabelText('Password'), password)
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    await waitFor(() => expect(within(list()).getByRole('link', { name: /^Plan/ })).toBeTruthy(), { timeout: 3000 })
    expect(started).toHaveLength(1)
  })

  it('turns on in Settings, and off again, removing what was kept', async () => {
    const started = engines()
    const { user } = await signedIn({ seed: (s) => createNote(s, 'Plan').then(() => undefined) })
    await openSettings(user, 'Sync & data')

    await user.click(await screen.findByRole('checkbox', { name: /Keep my notes on this device/ }))
    await waitFor(() => expect(started).toHaveLength(1))
    expect(await screen.findByRole('button', { name: 'Sync now' })).toBeTruthy()
    await waitFor(async () => expect((await started[0]?.call('list', null, null))?.map((n) => n.title)).toEqual(['Plan']))
    expect(await within(list()).findByRole('link', { name: /^Plan/ })).toBeTruthy()

    await user.click(screen.getByRole('checkbox', { name: /Keep my notes on this device/ }))
    // Gone from the screens once the wipe is done.
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Sync now' })).toBeNull())
    expect(deviceUser()).toBeNull()
    expect(await started[0]?.call('list', null, null)).toEqual([])
  })

  it('says what turning off would lose, and Cancel keeps it', async () => {
    const started = engines()
    let id = ''
    const { user, server } = await signedIn({ seed: async (s) => void (id = await createNote(s, 'Plan', 'One\n')) })
    await openSettings(user, 'Sync & data')
    await user.click(await screen.findByRole('checkbox', { name: /Keep my notes on this device/ }))
    await screen.findByRole('button', { name: 'Sync now' })
    await user.click(screen.getByRole('button', { name: 'Done' }))
    await openNote(user, 'Plan')
    server.setOffline(true)
    await typeAtEnd(user, ', two')
    await saved()

    await openSettings(user, 'Sync & data')
    await user.click(await screen.findByRole('checkbox', { name: /Keep my notes on this device/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Stop keeping notes here?' })
    expect(within(dialog).getByText(/1 note was changed in this browser/)).toBeTruthy()
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    expect(deviceUser()).toBe('me')
    expect((await started[0]?.call('get', id))?.content).toBe('One, two\n')
  })

  it('counts typing not yet saved', async () => {
    engines()
    const { user, server } = await signedIn({ seed: (s) => createNote(s, 'Plan', 'One\n').then(() => undefined) })
    await openSettings(user, 'Sync & data')
    await user.click(await screen.findByRole('checkbox', { name: /Keep my notes on this device/ }))
    await screen.findByRole('button', { name: 'Sync now' })
    await user.click(screen.getByRole('button', { name: 'Done' }))
    await openNote(user, 'Plan')
    server.setOffline(true)
    timing.debounceMs = 60_000
    await typeAtEnd(user, ', two')

    await openSettings(user, 'Sync & data')
    await user.click(await screen.findByRole('checkbox', { name: /Keep my notes on this device/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Stop keeping notes here?' })
    expect(within(dialog).getByText(/1 note was changed in this browser/)).toBeTruthy()
  })
})

describe('signing out', () => {
  it('removes the notes kept here', async () => {
    const started = engines()
    const { user } = await signInFromScratch({ keep: true })
    await openSettings(user)

    await user.click(screen.getByRole('button', { name: 'Sign out' }))

    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeTruthy()
    expect(deviceUser()).toBeNull()
    expect(await started[0]?.call('list', null, null)).toEqual([])
  })

  it('asks first when notes haven’t reached the server', async () => {
    const started = engines()
    const { user, server, id } = await signInFromScratch({ keep: true })
    await openNote(user, 'Plan')
    server.setOffline(true)
    await typeAtEnd(user, ', two')
    await saved()
    await openSettings(user)

    await user.click(screen.getByRole('button', { name: 'Sign out' }))
    const dialog = await screen.findByRole('dialog', { name: 'Sign out anyway?' })
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect((await started[0]?.call('get', id))?.content).toBe('One, two\n')

    server.setOffline(false)
    await user.click(screen.getByRole('button', { name: 'Sign out' }))
    // Back online, the one more sync before signing out sends it: nothing to ask about.
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeTruthy()
    expect((await getNote(server, id)).content).toBe('One, two\n')
  })
})

describe('a device that can’t open its notes', () => {
  it('shows the server’s notes, and says why in Settings', async () => {
    engines({ failing: true })
    const { user, server } = await signInFromScratch({ keep: true })
    expect((await listNotes(server)).map((n) => n.title)).toEqual(['Plan'])

    await openSettings(user, 'Sync & data')
    expect(await screen.findByText(/The notes kept here couldn’t be opened \(No OPFS here\)/)).toBeTruthy()
  })
})
