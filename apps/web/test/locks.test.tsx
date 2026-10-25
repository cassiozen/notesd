import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { LockKey } from '../src/api/types'
import {
  LOCKED_PREFIX,
  decryptBody,
  fromBase64Url,
  importKey,
  passwordKek,
  randomBytes,
  toBase64Url,
  unwrapRaw,
  verifier,
  wrap,
} from '../src/locks/crypto'
import { lockSession, lockTiming } from '../src/locks/session'
import { timing } from '../src/notes/saver'
import {
  bodyText,
  createNote,
  getNote,
  list,
  listNotes,
  newNoteButton,
  noteTitle,
  openSettings,
  saved,
  signedIn,
  typeAtEnd,
  typeInTitle,
} from './helpers'
import type { TestServer } from './server'
import { fakeAuthenticator } from './webauthn'

const LOCK_PASSWORD = 'lock pass 123'
const moreMenu = () => screen.getByRole('menu', { name: 'More' })

/** Decrypts a note's stored body the way another device would: keyring from the server, then the password. */
async function readLocked(server: TestServer, id: string, password = LOCK_PASSWORD): Promise<string> {
  const { keys } = (await server.api<{ keys: LockKey[] }>('GET', '/locks')).body
  const entry = keys.find((k) => k.kind === 'password')
  if (!entry) throw new Error('no password entry')
  const kek = await passwordKek(password, fromBase64Url(entry.salt), entry.iterations)
  const key = await importKey(await unwrapRaw(kek, entry.wrapped, 'password'))
  return decryptBody(key, (await getNote(server, id)).content)
}

type Session = Awaited<ReturnType<typeof signedIn>>

/** Locks the open note from its menu, creating the lock password on first use. */
async function lockOpenNote({ user }: Session) {
  await user.click(await screen.findByRole('button', { name: 'More' }))
  await user.click(within(moreMenu()).getByRole('menuitem', { name: 'Lock note' }))
  const dialog = await screen.findByRole('dialog', { name: 'Create a lock password' })
  await user.type(within(dialog).getByLabelText('Lock password'), LOCK_PASSWORD)
  await user.type(within(dialog).getByLabelText('Confirm'), LOCK_PASSWORD)
  await user.click(within(dialog).getByRole('button', { name: 'Create and lock' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  await saved()
}

const sidebar = () => screen.getByRole('navigation', { name: 'Sidebar' })

async function seeded() {
  let id = ''
  const session = await signedIn({ seed: async (s) => void (id = await createNote(s, 'Bank', 'PIN 1234 #money')) })
  await waitFor(() => expect(noteTitle()).toBe('Bank'))
  return { ...session, id }
}

describe('locked notes', () => {
  it('locking stores only ciphertext; the list and search show the title alone', async () => {
    const s = await seeded()
    await lockOpenNote(s)

    const stored = await getNote(s.server, s.id)
    expect(stored.content.startsWith(LOCKED_PREFIX)).toBe(true)
    expect(stored.content).not.toContain('1234')
    expect(await readLocked(s.server, s.id)).toBe('PIN 1234 #money\n')

    const [item] = await listNotes(s.server)
    expect(item).toMatchObject({ title: 'Bank', locked: true, preview: '' })
    expect(within(list()).getByRole('link', { name: /^Bank/ }).textContent).toContain('Locked')
    // The body's tag went with the body.
    expect((await s.server.api<{ tags: unknown[] }>('GET', '/filters')).body.tags).toEqual([])
    expect((await s.server.api<{ results: unknown[] }>('GET', '/search?q=1234')).body.results).toEqual([])
  })

  it('a Locked row in the sidebar, once there are locked notes, shows only them', async () => {
    let id = ''
    const s = await signedIn({
      seed: async (server) => {
        await createNote(server, 'Groceries', 'milk\n')
        id = await createNote(server, 'Bank', 'PIN 1234\n')
      },
    })
    await waitFor(() => expect(noteTitle()).toBe('Bank'))
    expect(within(sidebar()).queryByRole('button', { name: 'Locked' })).toBeNull()

    await lockOpenNote(s)
    await s.user.click(await within(sidebar()).findByRole('button', { name: 'Locked' }, { timeout: 3000 }))

    expect(await within(list()).findByRole('heading', { name: /Locked/ })).toBeTruthy()
    await waitFor(() => expect(within(list()).queryByRole('link', { name: /^Groceries/ })).toBeNull())
    // find, not get: the list can be empty between Groceries leaving and the Locked results arriving.
    expect((await within(list()).findByRole('link', { name: /^Bank/ })).getAttribute('href')).toBe(`/notes/${id}`)
  })

  it('locks again on request; opens with the lock password; edits stay encrypted', async () => {
    const s = await seeded()
    await lockOpenNote(s)

    await s.user.click(screen.getByRole('button', { name: 'Lock now' }))
    expect(await screen.findByText('This note is locked.')).toBeTruthy()
    expect(screen.queryByRole('textbox', { name: 'Note' })).toBeNull()
    expect(screen.getByRole('heading', { name: 'Bank' })).toBeTruthy()

    await s.user.type(screen.getByLabelText('Lock password'), 'nope{Enter}')
    expect(await screen.findByText('Wrong password.')).toBeTruthy()
    await s.user.clear(screen.getByLabelText('Lock password'))
    await s.user.type(screen.getByLabelText('Lock password'), `${LOCK_PASSWORD}{Enter}`)
    await waitFor(() => expect(bodyText()).toBe('PIN 1234 #money'))

    await typeAtEnd(s.user, ' and 5678')
    await saved()
    const stored = await getNote(s.server, s.id)
    expect(stored.content.startsWith(LOCKED_PREFIX)).toBe(true)
    expect(await readLocked(s.server, s.id)).toBe('PIN 1234 #money and 5678\n')
  })

  it('locks by itself after a while idle', async () => {
    const s = await seeded()
    lockTiming.idleMs = 150
    await lockOpenNote(s)
    expect(await screen.findByText('This note is locked.', {}, { timeout: 2000 })).toBeTruthy()
  })

  it('removing the lock stores plain text again', async () => {
    const s = await seeded()
    await lockOpenNote(s)
    await s.user.click(screen.getByRole('button', { name: 'More' }))
    await s.user.click(within(moreMenu()).getByRole('menuitem', { name: 'Remove lock' }))
    await waitFor(async () => expect((await getNote(s.server, s.id)).content).toBe('PIN 1234 #money\n'))
    expect(screen.queryByRole('button', { name: 'Lock now' })).toBeNull()
  })

  it('changing the lock password re-wraps the same key: notes still open', async () => {
    const s = await seeded()
    await lockOpenNote(s)
    await openSettings(s.user, 'Security')
    const card = await screen.findByRole('region', { name: 'Locked notes' })
    await s.user.type(within(card).getByLabelText('Current lock password'), LOCK_PASSWORD)
    await s.user.type(within(card).getByLabelText('New lock password'), 'brand new pass')
    await s.user.type(within(card).getByLabelText('Confirm new lock password'), 'brand new pass')
    await s.user.click(within(card).getByRole('button', { name: 'Change lock password' }))
    expect(await within(card).findByText(/^Lock password changed\./)).toBeTruthy()
    expect(await readLocked(s.server, s.id, 'brand new pass')).toBe('PIN 1234 #money\n')
  })

  it('locking another note asks for the existing lock password, not a new one', async () => {
    let other = ''
    const s = await signedIn({
      seed: async (srv) => {
        other = await createNote(srv, 'Diary', 'Dear diary')
        await createNote(srv, 'Bank', 'PIN 1234 #money')
      },
    })
    await waitFor(() => expect(noteTitle()).toBe('Bank'))
    await lockOpenNote(s)
    await s.user.click(screen.getByRole('button', { name: 'Lock now' }))
    await screen.findByText('This note is locked.')

    await s.user.click(within(list()).getByRole('link', { name: /^Diary/ }))
    await waitFor(() => expect(noteTitle()).toBe('Diary'))
    await s.user.click(screen.getByRole('button', { name: 'More' }))
    await s.user.click(within(moreMenu()).getByRole('menuitem', { name: 'Lock note' }))
    const dialog = await screen.findByRole('dialog', { name: 'Lock this note' })
    expect(screen.queryByRole('dialog', { name: 'Create a lock password' })).toBeNull()
    await s.user.type(within(dialog).getByLabelText('Lock password'), LOCK_PASSWORD)
    await s.user.click(within(dialog).getByRole('button', { name: 'Lock' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await saved()
    expect(await readLocked(s.server, other)).toBe('Dear diary\n')
  })

  it('a locked note can’t be emailed', async () => {
    const s = await seeded()
    await s.server.api('PUT', '/settings/email', { apiKey: 're_test_123456789', from: 'Me <notes@example.com>' })
    await lockOpenNote(s)
    await s.user.click(screen.getByRole('button', { name: 'More' }))
    expect(within(moreMenu()).queryByRole('menuitem', { name: 'Email…' })).toBeNull()
    expect(within(moreMenu()).getByRole('menuitem', { name: 'Remove lock' })).toBeTruthy()
  })
})

async function addPasskey(s: Session) {
  await openSettings(s.user, 'Security')
  const group = await screen.findByRole('group', { name: 'Passkeys' })
  await s.user.click(within(group).getByRole('button', { name: 'Add a passkey' }))
  await s.user.type(within(group).getByLabelText('Lock password'), LOCK_PASSWORD)
  await s.user.click(within(group).getByRole('button', { name: 'Continue' }))
  return group
}

async function closeSettings(s: Session) {
  await s.user.click(screen.getByRole('button', { name: 'Done' }))
}

describe('passkeys for locked notes', () => {
  it('a passkey added in Settings unlocks locked notes', async () => {
    fakeAuthenticator()
    const s = await seeded()
    await lockOpenNote(s)
    const group = await addPasskey(s)
    expect(await within(group).findByText(/^Passkey added\./)).toBeTruthy()
    const { keys } = (await s.server.api<{ keys: LockKey[] }>('GET', '/locks')).body
    expect(keys.map((k) => k.kind)).toEqual(['password', 'passkey'])

    await closeSettings(s)
    await s.user.click(screen.getByRole('button', { name: 'Lock now' }))
    await screen.findByText('This note is locked.')
    await s.user.click(screen.getByRole('button', { name: 'Unlock with passkey' }))
    await waitFor(() => expect(bodyText()).toBe('PIN 1234 #money'))
  })

  it('with a passkey, the lock password waits behind "Use password instead"', async () => {
    fakeAuthenticator()
    const s = await seeded()
    await lockOpenNote(s)
    await addPasskey(s)
    await closeSettings(s)
    await s.user.click(screen.getByRole('button', { name: 'Lock now' }))
    await screen.findByText('This note is locked.')
    expect(screen.getByRole('button', { name: 'Unlock with passkey' })).toBeTruthy()
    expect(screen.queryByLabelText('Lock password')).toBeNull()

    await s.user.click(screen.getByRole('button', { name: 'Use password instead' }))
    expect(document.activeElement).toBe(screen.getByLabelText('Lock password'))
    expect(screen.queryByRole('button', { name: 'Unlock with passkey' })).toBeNull()
    await s.user.type(screen.getByLabelText('Lock password'), `${LOCK_PASSWORD}{Enter}`)
    await waitFor(() => expect(bodyText()).toBe('PIN 1234 #money'))
  })

  it('dismissing the passkey prompt changes nothing', async () => {
    const auth = fakeAuthenticator()
    const s = await seeded()
    await lockOpenNote(s)
    await addPasskey(s)
    await closeSettings(s)
    await s.user.click(screen.getByRole('button', { name: 'Lock now' }))
    await screen.findByText('This note is locked.')
    auth.dismissNext = true
    await s.user.click(screen.getByRole('button', { name: 'Unlock with passkey' }))
    expect(screen.getByText('This note is locked.')).toBeTruthy()
    expect(screen.queryByText(/couldn’t unlock/)).toBeNull()
  })

  it('an authenticator without PRF is refused and nothing is stored', async () => {
    fakeAuthenticator({ prf: false })
    const s = await seeded()
    await lockOpenNote(s)
    const group = await addPasskey(s)
    expect(await within(group).findByText(/doesn’t support the PRF extension/)).toBeTruthy()
    const { keys } = (await s.server.api<{ keys: LockKey[] }>('GET', '/locks')).body
    expect(keys.map((k) => k.kind)).toEqual(['password'])
  })

  it('a wrong lock password adds no passkey; a passkey can be removed', async () => {
    fakeAuthenticator()
    const s = await seeded()
    await lockOpenNote(s)
    await openSettings(s.user, 'Security')
    const group = await screen.findByRole('group', { name: 'Passkeys' })
    await s.user.click(within(group).getByRole('button', { name: 'Add a passkey' }))
    await s.user.type(within(group).getByLabelText('Lock password'), 'not it')
    await s.user.click(within(group).getByRole('button', { name: 'Continue' }))
    expect(await within(group).findByText('That isn’t your lock password.')).toBeTruthy()

    await s.user.clear(within(group).getByLabelText('Lock password'))
    await s.user.type(within(group).getByLabelText('Lock password'), LOCK_PASSWORD)
    await s.user.click(within(group).getByRole('button', { name: 'Continue' }))
    await within(group).findByText(/^Passkey added\./)
    await s.user.click(within(group).getByRole('button', { name: /^Remove passkey/ }))
    await waitFor(() => expect(within(group).queryByRole('button', { name: /^Remove passkey/ })).toBeNull())
    const { keys } = (await s.server.api<{ keys: LockKey[] }>('GET', '/locks')).body
    expect(keys.map((k) => k.kind)).toEqual(['password'])
  })
})

/** The lock password changed on another device: same key, new wrap. */
async function changePasswordElsewhere(server: TestServer, next: string) {
  const { keys } = (await server.api<{ keys: LockKey[] }>('GET', '/locks')).body
  const entry = keys.find((k) => k.kind === 'password')
  if (!entry) throw new Error('no password entry')
  const raw = await unwrapRaw(await passwordKek(LOCK_PASSWORD, fromBase64Url(entry.salt), entry.iterations), entry.wrapped, 'password')
  const salt = randomBytes(16)
  await server.api('PUT', '/locks/keys/password', {
    kind: 'password',
    salt: toBase64Url(salt),
    iterations: 10_000,
    wrapped: await wrap(await passwordKek(next, salt, 10_000), raw, 'password'),
    verifier: await verifier(raw),
  })
}

/** Another device resets locked notes and makes a new lock password: a new key, not ours. */
async function resetElsewhere(s: Session, next: string) {
  await s.server.api('DELETE', '/locks', { accountPassword: s.password })
  const raw = randomBytes(32)
  const salt = randomBytes(16)
  await s.server.api('PUT', '/locks/keys/password', {
    kind: 'password',
    salt: toBase64Url(salt),
    iterations: 10_000,
    wrapped: await wrap(await passwordKek(next, salt, 10_000), raw, 'password'),
    verifier: await verifier(raw),
  })
}

describe('locked notes, when things happen meanwhile', () => {
  it('coming back to the tab fetches the keyring, even with a fresh list: a reset elsewhere locks it', async () => {
    const s = await seeded()
    await lockOpenNote(s)
    await resetElsewhere(s, 'someone else')
    act(() => void window.dispatchEvent(new Event('focus')))
    await waitFor(() => expect(lockSession.key()).toBeNull())
  })

  it('an unlock still deriving when a reset lands doesn’t take the forgotten key', async () => {
    const s = await seeded()
    await lockOpenNote(s)
    await s.user.click(screen.getByRole('button', { name: 'Lock now' }))
    await screen.findByText('This note is locked.')

    // Hold the first derivation (the old password entry's) until the new keyring is in.
    const real = crypto.subtle.deriveKey.bind(crypto.subtle)
    let started!: () => void
    const deriving = new Promise<void>((r) => (started = r))
    let release!: () => void
    const gate = new Promise<void>((r) => (release = r))
    const spy = vi.spyOn(crypto.subtle, 'deriveKey').mockImplementationOnce(async (...args) => {
      started()
      await gate
      return real(...args)
    })
    try {
      await act(async () => {
        const unlock = lockSession.unlock(LOCK_PASSWORD)
        await deriving
        await resetElsewhere(s, 'someone else')
        await lockSession.load(true)
        release()
        // Tried against the new keyring, the old password is wrong.
        await expect(unlock).rejects.toThrow()
      })
    } finally {
      spy.mockRestore()
    }
    expect(lockSession.key()).toBeNull()
    expect(lockSession.getStatus()).toBe('locked')
  })

  it('a password change answered after a newer keyring arrived isn’t installed over it', async () => {
    const s = await seeded()
    await lockOpenNote(s)
    await s.user.click(screen.getByRole('button', { name: 'Lock now' }))
    await screen.findByText('This note is locked.')

    // The server takes the change, then another device resets and starts over,
    // and this tab fetches that, all before the change's answer is read.
    const real = globalThis.fetch
    const spy = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const res = await real(input, init)
      if (init?.method === 'PUT' && String(input).endsWith('/api/v1/locks/keys/password')) {
        spy.mockImplementation(real)
        await resetElsewhere(s, 'someone else')
        await lockSession.load(true)
      }
      return res
    })
    try {
      await act(async () => {
        await expect(lockSession.changePassword(LOCK_PASSWORD, 'brand new pass')).rejects.toThrow(/another device/)
      })
    } finally {
      spy.mockRestore()
    }
    expect(lockSession.key()).toBeNull()
    const { keys } = (await s.server.api<{ keys: LockKey[] }>('GET', '/locks')).body
    await waitFor(() => expect(lockSession.keys()).toEqual(keys))
  })

  it('Lock now during a password change keeps notes locked', async () => {
    const s = await seeded()
    await lockOpenNote(s)
    await act(async () => {
      const change = lockSession.changePassword(LOCK_PASSWORD, 'brand new pass')
      await lockSession.lock()
      await change
    })
    expect(lockSession.key()).toBeNull()
    expect(lockSession.getStatus()).toBe('locked')
    expect(await screen.findByText('This note is locked.')).toBeTruthy()
    // The change itself went through.
    expect(await readLocked(s.server, s.id, 'brand new pass')).toBe('PIN 1234 #money\n')
  })

  it('an unlock or keyring fetch finishing after sign-out changes nothing', async () => {
    const s = await seeded()
    await lockOpenNote(s)
    await s.user.click(screen.getByRole('button', { name: 'Lock now' }))
    await screen.findByText('This note is locked.')
    await act(async () => {
      const unlock = lockSession.unlock(LOCK_PASSWORD)
      const load = lockSession.load(true)
      lockSession.forget()
      // Whether they resolve or give up, they land nowhere.
      await Promise.allSettled([unlock, load])
    })
    expect(lockSession.key()).toBeNull()
    expect(lockSession.getStatus()).toBe('unknown')
    expect(lockSession.keys()).toEqual([])
  })

  it('a note waiting on the placeholder opens when notes are unlocked elsewhere', async () => {
    const s = await seeded()
    await lockOpenNote(s)
    await s.user.click(screen.getByRole('button', { name: 'Lock now' }))
    await screen.findByText('This note is locked.')
    await act(() => lockSession.unlock(LOCK_PASSWORD))
    await waitFor(() => expect(bodyText()).toBe('PIN 1234 #money'))
    expect(screen.queryByText(/can’t be opened/)).toBeNull()
  })

  it('a lock password changed elsewhere unlocks with the new one', async () => {
    const s = await seeded()
    await lockOpenNote(s)
    await s.user.click(screen.getByRole('button', { name: 'Lock now' }))
    await screen.findByText('This note is locked.')
    await changePasswordElsewhere(s.server, 'brand new pass')
    await s.user.type(screen.getByLabelText('Lock password'), 'brand new pass{Enter}')
    await waitFor(() => expect(bodyText()).toBe('PIN 1234 #money'))
  })

  it('a reset elsewhere locks this tab: nothing new is locked with the old key', async () => {
    const s = await seeded()
    await lockOpenNote(s)
    await s.server.api('DELETE', '/locks', { accountPassword: s.password })
    await openSettings(s.user, 'Security')
    const card = await screen.findByRole('region', { name: 'Locked notes' })
    // Settings fetched the keyring afresh rather than trusting the one from sign-in.
    expect(await within(card).findByText(/^No lock password yet\./)).toBeTruthy()
    expect(lockSession.key()).toBeNull()
  })

  it('Settings says when the keyring can’t be fetched, and tries again', async () => {
    const s = await seeded()
    act(() => lockSession.forget())
    const real = globalThis.fetch
    const spy = vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) =>
      String(input).endsWith('/api/v1/locks') ? Promise.reject(new TypeError('offline')) : real(input, init),
    )
    await openSettings(s.user, 'Security')
    const card = await screen.findByRole('region', { name: 'Locked notes' })
    expect(await within(card).findByRole('alert')).toBeTruthy()
    spy.mockRestore()
    await s.user.click(within(card).getByRole('button', { name: 'Try again' }))
    expect(await within(card).findByText(/^No lock password yet\./)).toBeTruthy()
  })

  it('Lock note waits for the keyring before choosing between first lock and unlock', async () => {
    const s = await seeded()
    act(() => lockSession.forget())
    await s.user.click(screen.getByRole('button', { name: 'More' }))
    await s.user.click(within(moreMenu()).getByRole('menuitem', { name: 'Lock note' }))
    expect(await screen.findByRole('dialog', { name: 'Create a lock password' })).toBeTruthy()
  })

  it('a new note locked and then locked again before saving goes to the server encrypted, then shows locked', async () => {
    const s = await seeded()
    await lockOpenNote(s)
    await s.user.click(newNoteButton())
    await s.user.click(await screen.findByRole('button', { name: 'More' }))
    await s.user.click(within(moreMenu()).getByRole('menuitem', { name: 'Lock note' }))
    // Still a draft when Lock now comes: locking must save it (encrypted), then hide it.
    timing.debounceMs = 60_000
    try {
      await typeInTitle(s.user, 'Diary{Enter}secret words')
      await s.user.click(screen.getByRole('button', { name: 'Lock now' }))
    } finally {
      timing.debounceMs = 40
    }
    expect(await screen.findByText('This note is locked.')).toBeTruthy()
    expect(screen.queryByRole('textbox', { name: 'Note' })).toBeNull()
    const all = await listNotes(s.server)
    const diary = all.find((n) => n.title === 'Diary')
    if (!diary) throw new Error(JSON.stringify(all))
    const stored = await getNote(s.server, diary.id)
    expect(stored.content.startsWith(LOCKED_PREFIX)).toBe(true)
    expect(await readLocked(s.server, diary.id)).toBe('secret words\n')
  })

  it('a new, still empty note marked locked closes when notes lock', async () => {
    const s = await seeded()
    await lockOpenNote(s)
    await s.user.click(newNoteButton())
    await s.user.click(await screen.findByRole('button', { name: 'More' }))
    await s.user.click(within(moreMenu()).getByRole('menuitem', { name: 'Lock note' }))
    await s.user.click(screen.getByRole('button', { name: 'Lock now' }))
    await waitFor(() => expect(screen.queryByRole('textbox', { name: 'Note' })).toBeNull())
    expect((await listNotes(s.server)).map((n) => n.title)).toEqual(['Bank'])
  })

  it('a new note that couldn’t be saved hides behind the lock, and comes back on unlock', async () => {
    const s = await seeded()
    await lockOpenNote(s)
    const real = globalThis.fetch
    const spy = vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) =>
      init?.method === 'POST' && String(input).endsWith('/api/v1/notes')
        ? Promise.reject(new TypeError('offline'))
        : real(input, init),
    )
    try {
      await s.user.click(newNoteButton())
      await s.user.click(await screen.findByRole('button', { name: 'More' }))
      await s.user.click(within(moreMenu()).getByRole('menuitem', { name: 'Lock note' }))
      await typeInTitle(s.user, 'Diary{Enter}secret words')
      await s.user.click(screen.getByRole('button', { name: 'Lock now' }))
      expect(await screen.findByText('This note is locked.')).toBeTruthy()
      expect(screen.queryByText('secret words')).toBeNull()
    } finally {
      spy.mockRestore()
    }
    await s.user.type(screen.getByLabelText('Lock password'), `${LOCK_PASSWORD}{Enter}`)
    await waitFor(() => expect(bodyText()).toBe('secret words'))
  })

  it('scrolling counts as activity', async () => {
    const s = await seeded()
    await lockOpenNote(s)
    lockTiming.idleMs = 600
    fireEvent.wheel(document.body)
    for (let i = 0; i < 5; i++) {
      await new Promise((r) => setTimeout(r, 300))
      if (i % 2) fireEvent.wheel(document.body)
      else fireEvent.scroll(body())
    }
    expect(lockSession.getStatus()).toBe('unlocked')
    // And without it, the timer still runs out.
    expect(await screen.findByText('This note is locked.', {}, { timeout: 2000 })).toBeTruthy()
  })
})

const body = () => screen.getByRole('textbox', { name: 'Note' })
