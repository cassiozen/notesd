import { act, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, onTestFinished, vi } from 'vitest'
import { publishing as publishingApi, spaces as spacesApi, type PublishSettings } from '../src/api/spaces'
import { encryptBody } from '../src/locks/crypto'
import { expectPublished, loadedSpaces, reloadSpaces, unexpectPublished } from '../src/notes/spaces'
import { op, signedIn } from './helpers'
import type { Client, TestServer } from './server'

// Publishing a space (architecture §7.10) from Space info, against a real server.

const panel = () => screen.getByRole('navigation', { name: 'Sidebar' })
const switcher = () => within(panel()).getByRole('button', { name: /^Space: / })
const popover = () => screen.getByRole('dialog', { name: 'Spaces' })

type User = Awaited<ReturnType<typeof signedIn>>['user']

/** Space info for a space, from the switcher. */
async function spaceInfo(user: User, name: string) {
  await user.click(await waitFor(() => switcher()))
  await user.click(within(popover()).getByRole('button', { name: new RegExp(`^${name}(,|$)`) }))
  await user.click(switcher())
  await user.click(within(popover()).getByRole('button', { name: 'Space info' }))
  return screen.findByRole('dialog', { name })
}

/** A promise and what settles it: a server answer held back until the test lets it go. */
const nothing = () => {}

function gate() {
  let release: () => void = nothing
  const held = new Promise<void>((resolve) => {
    release = resolve
  })
  return { held, release }
}

const section = (info: HTMLElement) => within(info).getByRole('region', { name: 'Published' })

/** Journal as the space list shows it now. */
async function journal() {
  return (await loadedSpaces())?.find((sp) => sp.name === 'Journal')
}

async function seedJournal(server: TestServer) {
  await server.api('PUT', '/spaces/journal', { name: 'Journal' })
  const res = await server.api('POST', '/notes', { operationId: op(), id: crypto.randomUUID(), title: 'Hello', content: 'World', spaceId: 'journal' })
  expect(res.status).toBe(201)
}

async function settings(server: Client, space = 'journal') {
  return (await server.api<PublishSettings>('GET', `/spaces/${space}/publish`)).body
}

async function publishedInfo(seed?: (server: TestServer) => Promise<void>) {
  const env = await signedIn({
    seed: async (s) => {
      await seedJournal(s)
      expect((await s.api('PUT', '/spaces/journal/publish', { origin: 'https://notes.example' })).status).toBe(200)
      await seed?.(s)
    },
  })
  await act(() => reloadSpaces())
  const info = await spaceInfo(env.user, 'Journal')
  await within(section(info)).findByRole('textbox', { name: 'Address' })
  return { ...env, info }
}

describe('publishing a space', () => {
  it('publishes from Space info, in place, from this app’s own address', async () => {
    const { server, user } = await signedIn({ seed: seedJournal })
    await act(() => reloadSpaces())
    const info = await spaceInfo(user, 'Journal')
    const published = section(info)
    expect(within(published).getByText(/^Publish Journal as a website anyone can read/)).toBeTruthy()
    await user.click(within(published).getByRole('button', { name: 'Publish' }))

    const address = await within(published).findByRole('textbox', { name: 'Address' })
    expect((address as HTMLInputElement).value).toBe('journal')
    const s = await settings(server)
    expect(s).toMatchObject({ published: true, handle: 'journal', origin: location.origin })
    expect(within(published).getByRole('link', { name: 'Open' }).getAttribute('href')).toBe(s.url)
    // The trait came with the list: its glyph in the header, and Stop publishing under the section.
    expect(within(info).getByRole('img', { name: 'Published' })).toBeTruthy()
    expect(within(info).getByRole('button', { name: 'Stop publishing…' })).toBeTruthy()
  })

  it('shows what the server answered when the space list can’t be read again', async () => {
    const { user } = await signedIn({ seed: seedJournal })
    await act(() => reloadSpaces())
    const info = await spaceInfo(user, 'Journal')
    const failing = vi.spyOn(spacesApi, 'list').mockRejectedValue(new TypeError('Failed to fetch'))
    onTestFinished(() => failing.mockRestore())
    await user.click(within(section(info)).getByRole('button', { name: 'Publish' }))
    // Published on the server: the section says so, though the list (and the header's glyph) can't yet.
    expect(await within(section(info)).findByRole('textbox', { name: 'Address' })).toBeTruthy()
    expect(within(info).getByRole('button', { name: 'Stop publishing…' })).toBeTruthy()

    await user.click(within(info).getByRole('button', { name: 'Stop publishing…' }))
    await user.click(within(await within(info).findByRole('group', { name: 'Stop publishing Journal?' })).getByRole('button', { name: 'Stop publishing' }))
    expect(await within(section(info)).findByRole('button', { name: 'Publish' })).toBeTruthy()
  })

  it('keeps Stop publishing open to try again when it fails', async () => {
    const { server, user, info } = await publishedInfo()
    await user.click(within(info).getByRole('button', { name: 'Stop publishing…' }))
    const confirm = await within(info).findByRole('group', { name: 'Stop publishing Journal?' })
    server.setOffline(true)
    await user.click(within(confirm).getByRole('button', { name: 'Stop publishing' }))
    expect(await within(info).findByRole('alert')).toBeTruthy()
    // Shown unpublished while the call was out, then put back: the confirmation is there again, to retry.
    const again = within(info).getByRole('group', { name: 'Stop publishing Journal?' })
    expect(within(info).getByRole('img', { name: 'Published' })).toBeTruthy()
    server.setOffline(false)
    await user.click(within(again).getByRole('button', { name: 'Stop publishing' }))
    await waitFor(() => expect(within(info).queryByRole('group', { name: 'Stop publishing Journal?' })).toBeNull())
    expect(await settings(server)).toMatchObject({ published: false })
  })

  it('shows the space published, and not, at once: the glyph doesn’t wait for the server', async () => {
    const { server, user } = await signedIn({ seed: seedJournal })
    await act(() => reloadSpaces())
    const info = await spaceInfo(user, 'Journal')
    // The server holds the answer until the glyph has been looked for.
    const { held, release } = gate()
    const put = publishingApi.put
    const slow = vi.spyOn(publishingApi, 'put').mockImplementation(async (...args) => {
      await held
      return put(...args)
    })
    onTestFinished(() => slow.mockRestore())
    await user.click(within(section(info)).getByRole('button', { name: 'Publish' }))
    expect(within(info).getByRole('img', { name: 'Published' })).toBeTruthy()
    expect(within(section(info)).getByText('Publishing…')).toBeTruthy()
    release()
    await within(section(info)).findByRole('textbox', { name: 'Address' })
    expect((await settings(server)).published).toBe(true)
  })

  it('with notes kept on this device, the real trait arrives with the sync that follows, not the next one', async () => {
    const { user } = await signedIn({ seed: seedJournal, onDevice: true })
    await act(() => reloadSpaces())
    const info = await spaceInfo(user, 'Journal')
    await user.click(within(section(info)).getByRole('button', { name: 'Publish' }))
    await within(section(info)).findByRole('textbox', { name: 'Address' })
    // The device's own copy of the list has the server's trait, address and all.
    await waitFor(async () => {
      expect((await journal())?.traits.find((t) => t.key === 'published')?.summary).toMatch(/^At .*@journal$/)
    })
  })

  it('a call that fails clears only what it expected, not a newer call’s', async () => {
    await signedIn({ seed: seedJournal })
    await act(() => reloadSpaces())
    const id = (await journal())?.id ?? ''
    let older = 0
    let newer = 0
    act(() => {
      older = expectPublished(id, false)
      newer = expectPublished(id, true)
    })
    act(() => unexpectPublished(id, older))
    expect((await journal())?.traits.map((t) => t.summary)).toEqual(['Publishing…'])
    act(() => unexpectPublished(id, newer))
    expect((await journal())?.traits).toEqual([])
  })

  it('puts the glyph back when publishing fails', async () => {
    const { user } = await signedIn({ seed: seedJournal })
    await act(() => reloadSpaces())
    const info = await spaceInfo(user, 'Journal')
    const failing = vi.spyOn(publishingApi, 'put').mockRejectedValue(new TypeError('Failed to fetch'))
    onTestFinished(() => failing.mockRestore())
    await user.click(within(section(info)).getByRole('button', { name: 'Publish' }))
    expect(await within(info).findByRole('alert')).toBeTruthy()
    expect(within(info).queryByRole('img', { name: 'Published' })).toBeNull()
    expect(within(section(info)).getByRole('button', { name: 'Publish' })).toBeTruthy()
  })

  it('says why a space with locked notes can’t be published', async () => {
    const { user } = await signedIn({
      seed: async (s) => {
        await seedJournal(s)
        const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
        const content = await encryptBody(key, 'Dear diary\n')
        expect((await s.api('POST', '/notes', { operationId: op(), id: crypto.randomUUID(), title: 'Diary', content, spaceId: 'journal' })).status).toBe(201)
      },
    })
    await act(() => reloadSpaces())
    const info = await spaceInfo(user, 'Journal')
    await user.click(within(section(info)).getByRole('button', { name: 'Publish' }))
    expect((await within(info).findByRole('alert')).textContent).toBe('Unlock its locked note to publish it.')
  })

  it('isn’t offered for the default space', async () => {
    const { user } = await signedIn()
    await act(() => reloadSpaces())
    await user.click(await waitFor(() => switcher()))
    await user.click(within(popover()).getByRole('button', { name: 'Space info' }))
    const info = await screen.findByRole('dialog')
    expect(within(info).queryByRole('region', { name: 'Published' })).toBeNull()
  })

  it('changes the address where it’s shown, once asked: links to the old one stop working', async () => {
    const { server, user, info } = await publishedInfo()
    const address = within(section(info)).getByRole('textbox', { name: 'Address' })
    await user.clear(address)
    await user.type(address, 'My-Days{Enter}')
    const confirm = await within(info).findByRole('group', { name: 'Move the site to notes.example/@my-days?' })
    expect(confirm.textContent).toContain('Links to the current address will stop working.')
    await user.click(within(confirm).getByRole('button', { name: 'Change address' }))
    await waitFor(async () => expect((await settings(server)).handle).toBe('my-days'))
    await waitFor(() => expect(within(info).queryByRole('group', { name: /^Move the site/ })).toBeNull())
  })

  it('puts the address back on Escape, and says when another space has it', async () => {
    const { user, info } = await publishedInfo(async (s) => {
      await s.api('PUT', '/spaces/taken', { name: 'Taken' })
      await s.api('PUT', '/spaces/taken/publish', { origin: 'https://notes.example', handle: 'taken' })
    })
    const address = within(section(info)).getByRole('textbox', { name: 'Address' }) as HTMLInputElement
    await user.clear(address)
    await user.type(address, 'nope{Escape}')
    expect(address.value).toBe('journal')
    expect(screen.getByRole('dialog', { name: 'Journal' })).toBeTruthy()

    await user.clear(address)
    await user.type(address, 'taken{Enter}')
    const confirm = await within(info).findByRole('group', { name: /^Move the site/ })
    await user.click(within(confirm).getByRole('button', { name: 'Change address' }))
    expect((await within(info).findByRole('alert')).textContent).toBe('Another space has that address. Try another.')
  })

  it('saves custom CSS when Save is pressed', async () => {
    const { server, user, info } = await publishedInfo()
    const published = section(info)
    await user.click(within(published).getByText('Custom CSS', { selector: 'summary' }))
    const save = within(published).getByRole('button', { name: 'Save' })
    expect(save).toHaveProperty('disabled', true)
    await user.click(within(published).getByRole('textbox', { name: 'Custom CSS' }))
    await user.paste(':root { --accent: teal }')
    await user.click(save)
    await waitFor(async () => expect((await settings(server)).css).toBe(':root { --accent: teal }'))
    await waitFor(() => expect(within(section(info)).getByRole('button', { name: 'Save' })).toHaveProperty('disabled', true))
  })

  it('stops publishing once asked; the address stays the space’s', async () => {
    const { server, user, info } = await publishedInfo()
    await user.click(within(info).getByRole('button', { name: 'Stop publishing…' }))
    const confirm = await within(info).findByRole('group', { name: 'Stop publishing Journal?' })
    await user.click(within(confirm).getByRole('button', { name: 'Stop publishing' }))
    await within(section(info)).findByRole('button', { name: 'Publish' })
    expect(await settings(server)).toMatchObject({ published: false, handle: 'journal' })
    expect(within(info).queryByRole('img', { name: 'Published' })).toBeNull()
  })

  it('shows a member where the space is, and nothing to change', async () => {
    const { user } = await signedIn({
      as: 'ana',
      seed: async (s) => {
        await seedJournal(s)
        const ana = await s.addAccount('ana')
        expect((await s.api('POST', '/spaces/journal/invites', { userId: ana.id })).status).toBe(201)
        const [invite] = (await ana.api<{ invites: { id: string }[] }>('GET', '/invites')).body.invites
        expect((await ana.api('POST', `/invites/${invite?.id}/accept`)).status).toBe(200)
        expect((await s.api('PUT', '/spaces/journal/publish', { origin: 'https://notes.example' })).status).toBe(200)
      },
    })
    await act(() => reloadSpaces())
    const info = await spaceInfo(user, 'Journal')
    const published = section(info)
    expect(within(published).getByText('At notes.example/@journal')).toBeTruthy()
    expect(within(published).queryByRole('textbox')).toBeNull()
    expect(within(info).queryByRole('button', { name: 'Stop publishing…' })).toBeNull()
  })
})
