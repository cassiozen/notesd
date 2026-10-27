import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Session } from '../src/api/types'
import { App } from '../src/app'
import { reconnectTiming } from '../src/auth/auth-context'
import { keepOnDevice } from '../src/auth/session-hint'
import { closeNotes } from '../src/offline/device'
import type { Engine } from '../src/offline/tab'
import { inProcessEngine } from './engine/helpers'
import { bodyText, createNote, getNote, list, openNote, saved, signedIn, startEngines, typeAtEnd } from './helpers'
import type { TestServer } from './server'

// Booting from this device's copy of the notes, and the session ending under sync.

afterEach(() => {
  reconnectTiming.everyMs = 30_000
})

/**
 * This device keeps "me"'s notes. Every start gets the same engine, as a
 * reloaded page gets the same database; [fresh]: a new, empty one each time.
 */
function onThisDevice({ fresh = false } = {}) {
  keepOnDevice('me')
  let shared: ReturnType<typeof inProcessEngine> | undefined
  const starts: string[] = []
  startEngines((user, csrf) => {
    starts.push(csrf)
    const engine = fresh ? inProcessEngine(user, csrf) : (shared ??= inProcessEngine(user, csrf))
    if (csrf) engine.setCsrfToken(csrf)
    return engine
  })
  return { engine: () => shared, starts }
}

/** As a reload: the page's state gone, the device's database kept. */
function reload() {
  cleanup()
  closeNotes()
  render(<App />)
}

const listShown = () => screen.findByRole('complementary', { name: 'Notes' })

async function revokeBrowserSession(server: TestServer) {
  const { body } = await server.api<{ sessions: Session[]; currentId: string }>('GET', '/auth/sessions')
  const browser = body.sessions.find((s) => s.id !== body.currentId)
  await server.api('DELETE', `/auth/sessions/${browser?.id}`)
}

describe('booting from this device', () => {
  it('opens offline from the notes kept here, and sends what was typed once the server is back', async () => {
    const device = onThisDevice()
    const persist = vi.fn(async () => true)
    Object.defineProperty(navigator, 'storage', { configurable: true, value: { persist } })
    let id = ''
    const { server, user } = await signedIn({ seed: async (s) => void (id = await createNote(s, 'Plan', 'Step one\n')) })
    await waitFor(async () => expect(await device.engine()?.call('lastSyncAt')).not.toBeNull())
    expect(persist).toHaveBeenCalled()

    server.setOffline(true)
    reload()
    await listShown()
    await openNote(user, 'Plan')
    expect(bodyText()).toBe('Step one')
    expect(screen.queryByText('Can’t reach the server')).toBeNull()
    await typeAtEnd(user, ', step two')
    await saved()

    server.setOffline(false)
    window.dispatchEvent(new Event('online'))
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('Step one, step two\n'), { timeout: 5000 })
    // The token the server gave once reachable again, for the engine's writes.
    expect(device.starts.at(-1)).toBe('')
  })

  it('says the server can’t be reached when nothing has synced here yet', async () => {
    onThisDevice({ fresh: true })
    const { server } = await signedIn()
    server.setOffline(true)

    reload()

    expect(await screen.findByText('Can’t reach the server')).toBeTruthy()
  })

  it('asks to sign in again when the server ended the session while offline', async () => {
    const device = onThisDevice()
    reconnectTiming.everyMs = 100
    const { server } = await signedIn({ seed: (s) => createNote(s, 'Plan').then(() => undefined) })
    await waitFor(async () => expect(await device.engine()?.call('lastSyncAt')).not.toBeNull())
    server.setOffline(true)
    reload()
    await within(await listShown()).findByRole('link', { name: /^Plan/ })

    await revokeBrowserSession(server)
    server.setOffline(false)

    expect(await screen.findByRole('dialog', { name: 'Sign in again' })).toBeTruthy()
    expect(within(list()).getByRole('link', { name: /^Plan/ })).toBeTruthy()
  })
})

describe('the session ending under sync', () => {
  it('asks to sign in again, then sync sends what waited', async () => {
    const device = onThisDevice()
    let id = ''
    const { server, user, password } = await signedIn({ seed: async (s) => void (id = await createNote(s, 'Plan', 'One\n')) })
    await openNote(user, 'Plan')
    await revokeBrowserSession(server)

    await typeAtEnd(user, ', two')
    await saved()
    expect(await device.engine()?.call('sync')).toBe('SignedOut')

    const dialog = await screen.findByRole('dialog', { name: 'Sign in again' })
    await user.type(within(dialog).getByLabelText('Password'), `${password}{Enter}`)
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('One, two\n'), { timeout: 5000 })
  })
})

describe('storage on this device', () => {
  it('says when the device is out of space', async () => {
    const { engine, user } = await signedIn({ onDevice: true, seed: (s) => createNote(s, 'Plan', 'One').then(() => undefined) })
    await openNote(user, 'Plan')
    if (!engine) throw new Error('no engine')
    const call = engine.call as (method: string, ...args: unknown[]) => Promise<unknown>
    engine.call = ((method: string, ...args: unknown[]) =>
      method === 'save' ? Promise.reject(new Error('SQLITE_FULL: database or disk is full')) : call(method, ...args)) as Engine['call']

    await typeAtEnd(user, ', two')

    expect(
      await screen.findByText('This device couldn’t store this save: it’s out of storage space. Free some up, then retry.'),
    ).toBeTruthy()
  })
})
