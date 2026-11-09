import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { Person, SessionInfo } from '../src/api/types'
import { openSettings, signedIn } from './helpers'
import { JPEG } from './image-helpers'


// jsdom can't decode or draw pictures: any picked file becomes the JPEG above, once `prepared` lets it.
let prepared: Promise<void> = Promise.resolve()
let picked = JPEG
vi.mock('../src/settings/photo', () => ({ preparePhoto: async () => (await prepared, picked.slice()) }))

const nav = () => screen.getByRole('navigation', { name: 'Settings categories' })
const accountRow = () => within(nav()).getByRole('link', { name: /^Server:/ })
const dialog = () => screen.getByRole('dialog', { name: 'Name and photo' })
const session = async (server: Awaited<ReturnType<typeof signedIn>>['server']) =>
  (await server.api<SessionInfo>('GET', '/auth/session')).body

describe('name and photo', () => {
  it('are optional, set from Server, and shown in the account row', async () => {
    const { server, user } = await signedIn()
    await openSettings(user)
    expect(accountRow().getAttribute('aria-label')).toBe(`Server: me on ${window.location.host}`)
    expect(within(accountRow()).getByText('M')).toBeTruthy()
    expect(screen.getByText('Optional. Without a name, you’re shown as me.')).toBeTruthy()

    await user.click(screen.getByRole('button', { name: 'Edit name and photo' }))
    await user.type(within(dialog()).getByLabelText('Name'), '  Maria Silva ')
    fireEvent.change(screen.getByTestId('photo-input'), { target: { files: [new File(['x'], 'me.png', { type: 'image/png' })] } })
    await within(dialog()).findByRole('button', { name: 'Change photo…' })
    await user.click(within(dialog()).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Name and photo' })).toBeNull())
    const after = await session(server)
    expect(after.displayName).toBe('Maria Silva')
    expect(after.avatar).not.toBe('')
    expect(accountRow().getAttribute('aria-label')).toBe(`Server: Maria Silva on ${window.location.host}`)
    expect(accountRow().querySelector('img')?.getAttribute('src')).toBe(`/api/v1/users/${after.userId}/avatar?v=${after.avatar}`)
    expect(screen.getByText('Shown as Maria Silva')).toBeTruthy()

    // Removing both goes back to the username and its initial.
    await user.click(screen.getByRole('button', { name: 'Edit name and photo' }))
    await user.click(within(dialog()).getByRole('button', { name: 'Remove photo' }))
    await user.clear(within(dialog()).getByLabelText('Name'))
    await user.click(within(dialog()).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(accountRow().getAttribute('aria-label')).toBe(`Server: me on ${window.location.host}`))
    expect(accountRow().querySelector('img')).toBeNull()
    expect(await session(server)).toMatchObject({ displayName: '', avatar: '' })
  })

  it('a name the server refuses stays in the dialog with why', async () => {
    const { user } = await signedIn()
    await openSettings(user)
    await user.click(screen.getByRole('button', { name: 'Edit name and photo' }))
    fireEvent.change(within(dialog()).getByLabelText('Name'), { target: { value: 'Maria\u0007' } })
    await user.click(within(dialog()).getByRole('button', { name: 'Save' }))
    expect(await within(dialog()).findByText('Use at most 64 characters, on one line.')).toBeTruthy()
  })

  it('after a name is saved but the photo isn’t, putting the name back is a change', async () => {
    const { server, user } = await signedIn()
    await openSettings(user)
    await user.click(screen.getByRole('button', { name: 'Edit name and photo' }))
    await user.type(within(dialog()).getByLabelText('Name'), 'Maria')
    picked = new TextEncoder().encode('not a jpeg') as Uint8Array<ArrayBuffer>
    try {
      fireEvent.change(screen.getByTestId('photo-input'), { target: { files: [new File(['x'], 'me.png', { type: 'image/png' })] } })
      await within(dialog()).findByRole('button', { name: 'Change photo…' })
      await user.click(within(dialog()).getByRole('button', { name: 'Save' }))
      expect(await within(dialog()).findByText('The server didn’t take that photo. Try another.')).toBeTruthy()
    } finally {
      picked = JPEG
    }
    expect((await session(server)).displayName).toBe('Maria')

    await user.clear(within(dialog()).getByLabelText('Name'))
    await user.click(within(dialog()).getByRole('button', { name: 'Remove photo' }))
    await user.click(within(dialog()).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Name and photo' })).toBeNull())
    expect((await session(server)).displayName).toBe('')
  })

  it('Save waits for a chosen photo to be ready', async () => {
    const { server, user } = await signedIn()
    await openSettings(user)
    let ready: (() => void) | undefined
    prepared = new Promise((resolve) => {
      ready = resolve
    })
    await user.click(screen.getByRole('button', { name: 'Edit name and photo' }))
    fireEvent.change(screen.getByTestId('photo-input'), { target: { files: [new File(['x'], 'me.png', { type: 'image/png' })] } })
    await waitFor(() => expect(within(dialog()).getByRole('button', { name: 'Save' }).hasAttribute('disabled')).toBe(true))
    ready?.()
    await waitFor(() => expect(within(dialog()).getByRole('button', { name: 'Save' }).hasAttribute('disabled')).toBe(false))
    await user.click(within(dialog()).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Name and photo' })).toBeNull())
    expect((await session(server)).avatar).not.toBe('')
  })

  it('a name changed on another device shows once People loads', async () => {
    const { server, user } = await signedIn({
      seed: async (s) => {
        await s.api('POST', '/users', { username: 'sam' })
      },
    })
    await server.api('PATCH', '/profile', { displayName: 'From the phone' })
    await openSettings(user)
    expect(await screen.findByText('Shown as From the phone')).toBeTruthy()
    // Opened as loaded, not as the session had it: Save leaves an untouched name alone.
    await user.click(screen.getByRole('button', { name: 'Edit name and photo' }))
    expect((within(dialog()).getByLabelText('Name') as HTMLInputElement).value).toBe('From the phone')
  })

  it('People shows names and photos, and an invitation can suggest one', async () => {
    const { server, user } = await signedIn({
      seed: async (s) => {
        await s.api('POST', '/users', { username: 'sam', displayName: 'Sam' })
      },
    })
    await openSettings(user)
    const people = await screen.findByRole('list', { name: 'People' })
    const sam = await within(people).findByText('Sam')
    expect(sam.closest('li')?.textContent).toContain('sam · Invited')

    await user.click(screen.getByRole('button', { name: 'Invite someone…' }))
    const invite = screen.getByRole('dialog', { name: 'Invite someone' })
    await user.type(within(invite).getByLabelText('Username'), 'mum')
    await user.type(within(invite).getByLabelText('Name (optional)'), 'Mum')
    await user.click(within(invite).getByRole('button', { name: 'Invite' }))
    expect(await screen.findByRole('dialog', { name: 'Send Mum this link' })).toBeTruthy()
    const { users } = (await server.api<{ users: Person[] }>('GET', '/users')).body
    expect(users.find((p) => p.username === 'mum')?.displayName).toBe('Mum')
  })
})
