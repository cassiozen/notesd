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

/** Journal as the space list shows it now. */
async function journal() {
  return (await loadedSpaces())?.find((sp) => sp.name === 'Journal')
}

/** The Website row of Space info's list, which opens its page. */
const websiteRow = (info: HTMLElement) => within(info).getByRole('button', { name: 'Website' })

/** What a row of Space info's list says: its description. */
const status = (row: HTMLElement) => document.getElementById(row.getAttribute('aria-describedby') ?? '')?.textContent

/** The Website section's page, opened in Space info's own dialog. */
async function website(user: User, info: HTMLElement) {
  await user.click(websiteRow(info))
  return screen.findByRole('dialog', { name: 'Website' })
}

/** One of the Website page's parts, by its caption: each has a Save of its own. */
const part = (page: HTMLElement, name: string) => within(page).getByRole('region', { name })

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
  const page = await website(env.user, info)
  await within(page).findByRole('textbox', { name: 'Address' })
  return { ...env, info: page }
}

describe('publishing a space', () => {
  it('publishes from Space info, on its page, from this app’s own address', async () => {
    const { server, user } = await signedIn({ seed: seedJournal })
    await act(() => reloadSpaces())
    const info = await spaceInfo(user, 'Journal')
    expect(status(websiteRow(info))).toBe('Not published')
    const page = await website(user, info)
    expect(page).toBe(info)
    expect(document.activeElement).toBe(within(page).getByRole('heading', { name: 'Website' }))
    expect(within(page).getByText(/^Anyone with the link can read Journal/)).toBeTruthy()
    await user.click(within(page).getByRole('button', { name: 'Publish' }))

    const address = await within(page).findByRole('textbox', { name: 'Address' })
    expect((address as HTMLInputElement).value).toBe('journal')
    const s = await settings(server)
    expect(s).toMatchObject({ published: true, handle: 'journal', origin: location.origin })
    expect(within(page).getByRole('link', { name: 'Open' }).getAttribute('href')).toBe(s.url)
    // Publish gives way to Stop publishing, beside Done.
    expect(within(page).queryByRole('button', { name: 'Publish' })).toBeNull()
    expect(within(page).getByRole('button', { name: 'Stop publishing…' })).toBeTruthy()
    // The trait came with the list: the row says where it is.
    await user.click(within(page).getByRole('button', { name: 'Back to Journal' }))
    await waitFor(() => expect(status(websiteRow(info))).toMatch(/^At .*@journal$/))
  })

  it('shows what the server answered when the space list can’t be read again', async () => {
    const { user } = await signedIn({ seed: seedJournal })
    await act(() => reloadSpaces())
    const page = await website(user, await spaceInfo(user, 'Journal'))
    const failing = vi.spyOn(spacesApi, 'list').mockRejectedValue(new TypeError('Failed to fetch'))
    onTestFinished(() => failing.mockRestore())
    await user.click(within(page).getByRole('button', { name: 'Publish' }))
    // Published on the server: the page says so, though the list can't yet.
    expect(await within(page).findByRole('textbox', { name: 'Address' })).toBeTruthy()
    expect(within(page).getByRole('button', { name: 'Stop publishing…' })).toBeTruthy()

    await user.click(within(page).getByRole('button', { name: 'Stop publishing…' }))
    await user.click(within(await within(page).findByRole('group', { name: 'Stop publishing Journal?' })).getByRole('button', { name: 'Stop publishing' }))
    expect(await within(page).findByRole('button', { name: 'Publish' })).toBeTruthy()
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
    expect((await journal())?.traits.map((t) => t.key)).toEqual(['published'])
    server.setOffline(false)
    await user.click(within(again).getByRole('button', { name: 'Stop publishing' }))
    await waitFor(() => expect(within(info).queryByRole('group', { name: 'Stop publishing Journal?' })).toBeNull())
    // Gone as the call goes out, not once it's done: the server may not have it yet.
    await waitFor(async () => expect(await settings(server)).toMatchObject({ published: false }))
  })

  it('shows the space published, and not, at once: the row doesn’t wait for the server', async () => {
    const { server, user } = await signedIn({ seed: seedJournal })
    await act(() => reloadSpaces())
    const info = await spaceInfo(user, 'Journal')
    const page = await website(user, info)
    // The server holds the answer until the row has been looked at.
    const { held, release } = gate()
    const put = publishingApi.put
    const slow = vi.spyOn(publishingApi, 'put').mockImplementation(async (...args) => {
      await held
      return put(...args)
    })
    onTestFinished(() => slow.mockRestore())
    await user.click(within(page).getByRole('button', { name: 'Publish' }))
    await user.click(within(page).getByRole('button', { name: 'Back to Journal' }))
    expect(status(websiteRow(info))).toBe('Publishing…')
    release()
    await waitFor(() => expect(status(websiteRow(info))).toMatch(/@journal$/))
    expect((await settings(server)).published).toBe(true)
  })

  it('with notes kept on this device, the real trait arrives with the sync that follows, not the next one', async () => {
    const { user } = await signedIn({ seed: seedJournal, onDevice: true })
    await act(() => reloadSpaces())
    const page = await website(user, await spaceInfo(user, 'Journal'))
    await user.click(within(page).getByRole('button', { name: 'Publish' }))
    await within(page).findByRole('textbox', { name: 'Address' })
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

  it('puts the row back when publishing fails', async () => {
    const { user } = await signedIn({ seed: seedJournal })
    await act(() => reloadSpaces())
    const info = await spaceInfo(user, 'Journal')
    const page = await website(user, info)
    const failing = vi.spyOn(publishingApi, 'put').mockRejectedValue(new TypeError('Failed to fetch'))
    onTestFinished(() => failing.mockRestore())
    await user.click(within(page).getByRole('button', { name: 'Publish' }))
    expect(await within(page).findByRole('alert')).toBeTruthy()
    expect(within(page).getByRole('button', { name: 'Publish' })).toBeTruthy()
    await user.click(within(page).getByRole('button', { name: 'Back to Journal' }))
    expect(status(websiteRow(info))).toBe('Not published')
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
    const page = await website(user, await spaceInfo(user, 'Journal'))
    await user.click(within(page).getByRole('button', { name: 'Publish' }))
    expect((await within(page).findByRole('alert')).textContent).toBe('Unlock its locked note to publish it.')
  })

  it('isn’t offered for the default space', async () => {
    const { user } = await signedIn()
    await act(() => reloadSpaces())
    await user.click(await waitFor(() => switcher()))
    await user.click(within(popover()).getByRole('button', { name: 'Space info' }))
    const info = await screen.findByRole('dialog')
    expect(within(info).queryByRole('button', { name: 'Website' })).toBeNull()
  })

  it('changes the address where it’s shown, once asked: links to the old one stop working', async () => {
    const { server, user, info } = await publishedInfo()
    const address = within(info).getByRole('textbox', { name: 'Address' })
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
    const address = within(info).getByRole('textbox', { name: 'Address' }) as HTMLInputElement
    await user.clear(address)
    await user.type(address, 'nope{Escape}')
    expect(address.value).toBe('journal')
    // That Escape was the field's: the page is still open.
    expect(screen.getByRole('dialog', { name: 'Website' })).toBeTruthy()

    await user.clear(address)
    await user.type(address, 'taken{Enter}')
    const confirm = await within(info).findByRole('group', { name: /^Move the site/ })
    await user.click(within(confirm).getByRole('button', { name: 'Change address' }))
    expect((await within(info).findByRole('alert')).textContent).toBe('Another space has that address. Try another.')
  })

  it('opens its page in the same dialog, and Escape goes back to the rest', async () => {
    const { user, info } = await publishedInfo()
    expect(within(info).queryByRole('button', { name: 'Website' })).toBeNull()

    await user.keyboard('{Escape}')
    expect(await screen.findByRole('dialog', { name: 'Journal' })).toBe(info)
    expect(document.activeElement).toBe(websiteRow(info))
    expect(within(info).queryByRole('heading', { name: 'Website' })).toBeNull()

    await website(user, info)
    await user.click(within(info).getByRole('button', { name: 'Back to Journal' }))
    expect(document.activeElement).toBe(websiteRow(info))
    // Escape from Space info itself still closes it.
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Journal' })).toBeNull())
  })

  it('saves custom CSS when Save is pressed, keeping what’s typed across Back', async () => {
    const { server, user, info } = await publishedInfo()
    let page = info
    const save = within(part(page, 'Custom CSS')).getByRole('button', { name: 'Save' })
    expect(save).toHaveProperty('disabled', true)
    await user.click(within(page).getByRole('textbox', { name: 'Custom CSS' }))
    await user.paste(':root { --accent: teal }')

    await user.click(within(page).getByRole('button', { name: 'Back to Journal' }))
    page = await website(user, info)
    expect((within(page).getByRole('textbox', { name: 'Custom CSS' }) as HTMLTextAreaElement).value).toBe(':root { --accent: teal }')

    await user.click(within(part(page, 'Custom CSS')).getByRole('button', { name: 'Save' }))
    await waitFor(async () => expect((await settings(server)).css).toBe(':root { --accent: teal }'))
    await waitFor(() => expect(within(part(page, 'Custom CSS')).getByRole('button', { name: 'Save' })).toHaveProperty('disabled', true))
  })

  it('discards what’s typed in the custom CSS', async () => {
    const { user, info: page } = await publishedInfo((s) => s.api('PUT', '/spaces/journal/publish', { css: 'a { color: red }' }).then(() => undefined))
    const field = within(page).getByRole('textbox', { name: 'Custom CSS' }) as HTMLTextAreaElement
    expect(field.value).toBe('a { color: red }')
    expect(within(part(page, 'Custom CSS')).queryByRole('button', { name: 'Discard' })).toBeNull()
    await user.type(field, ' b')
    await user.click(within(part(page, 'Custom CSS')).getByRole('button', { name: 'Discard' }))
    expect(field.value).toBe('a { color: red }')
  })

  it('puts the site on a domain of its own, linking to how to set it up', async () => {
    const { server, user, info: page } = await publishedInfo()
    const domain = part(page, 'Your own domain')
    const field = within(domain).getByRole('textbox', { name: 'Domain' })
    await user.type(field, 'not a domain{Enter}')
    expect((await within(domain).findByRole('alert')).textContent).toBe('Use a domain name, such as blog.example.com.')
    await user.clear(field)
    await user.type(field, 'Blog.Example.com')
    await user.click(within(domain).getByRole('button', { name: 'Save' }))
    await waitFor(async () => expect(await settings(server)).toMatchObject({ domain: 'blog.example.com', url: 'https://blog.example.com/' }))

    // Saved, the part is put back with the server's settings: looked for again, not kept from before.
    await waitFor(() => expect(within(part(page, 'Your own domain')).getByText(/^Point blog\.example\.com at this server/)).toBeTruthy())
    const saved = part(page, 'Your own domain')
    expect(within(saved).getByRole('link', { name: 'How to set it up' }).getAttribute('href')).toBe(
      'https://notesd.app/docs/guides/using/publishing.html#use-your-own-domain',
    )
    expect(within(page).getByRole('link', { name: 'Open' }).getAttribute('href')).toBe('https://blog.example.com/')
  })

  it('says when the domain is this server’s own address', async () => {
    const { user, info: page } = await publishedInfo()
    const domain = part(page, 'Your own domain')
    await user.type(within(domain).getByRole('textbox', { name: 'Domain' }), 'notes.example{Enter}')
    expect((await within(domain).findByRole('alert')).textContent).toBe('That’s this server’s own address. Use another domain.')
  })

  it('leaves the domain to an admin', async () => {
    const { user } = await signedIn({
      as: 'ana',
      seed: async (s) => {
        const ana = await s.addAccount('ana')
        expect((await ana.api('PUT', '/spaces/garden', { name: 'Garden' })).status).toBe(200)
        expect((await ana.api('PUT', '/spaces/garden/publish', { origin: 'https://notes.example' })).status).toBe(200)
      },
    })
    await act(() => reloadSpaces())
    const page = await website(user, await spaceInfo(user, 'Garden'))
    const domain = await waitFor(() => part(page, 'Your own domain'))
    expect(within(domain).getByText(/^An admin of this server can give the site a domain/)).toBeTruthy()
    expect(within(domain).queryByRole('textbox', { name: 'Domain' })).toBeNull()
  })

  it('asks before the site stops using its own domain', async () => {
    const { server, user, info: page } = await publishedInfo((s) => s.api('PUT', '/spaces/journal/publish', { domain: 'blog.example.com' }).then(() => undefined))
    const domain = part(page, 'Your own domain')
    await user.clear(within(domain).getByRole('textbox', { name: 'Domain' }))
    await user.click(within(domain).getByRole('button', { name: 'Save' }))
    const confirm = await within(domain).findByRole('group', { name: 'Stop using blog.example.com?' })
    await user.click(within(confirm).getByRole('button', { name: 'Cancel' }))
    expect((await settings(server)).domain).toBe('blog.example.com')

    await user.click(within(domain).getByRole('button', { name: 'Save' }))
    await user.click(within(await within(domain).findByRole('group', { name: 'Stop using blog.example.com?' })).getByRole('button', { name: 'Stop using it' }))
    await waitFor(async () => expect((await settings(server)).domain).toBe(''))
    await waitFor(() => expect(within(part(page, 'Your own domain')).getByText(/^Serve the site at a domain of its own/)).toBeTruthy())
  })

  it('stays on its page when the space stops being published elsewhere, to publish it again', async () => {
    const { server, info: page } = await publishedInfo()
    expect((await server.api('DELETE', '/spaces/journal/publish')).status).toBe(204)
    await act(() => reloadSpaces())
    expect(await screen.findByRole('dialog', { name: 'Website' })).toBe(page)
    expect(await within(page).findByRole('button', { name: 'Publish' })).toBeTruthy()
    expect(within(page).queryByRole('textbox', { name: 'Address' })).toBeNull()
  })

  it('stops publishing once asked; the address stays the space’s', async () => {
    const { server, user, info: page } = await publishedInfo()
    await user.click(within(page).getByRole('button', { name: 'Stop publishing…' }))
    const confirm = await within(page).findByRole('group', { name: 'Stop publishing Journal?' })
    await user.click(within(confirm).getByRole('button', { name: 'Stop publishing' }))
    // Shown at once, before the server has it.
    await within(page).findByRole('button', { name: 'Publish' })
    await waitFor(async () => expect(await settings(server)).toMatchObject({ published: false, handle: 'journal' }))
    await user.click(within(page).getByRole('button', { name: 'Back to Journal' }))
    expect(status(websiteRow(page))).toBe('Not published')
  })

  it('goes back from its page when the section itself goes: handed on, then taken down by its new owner', async () => {
    let ana: (Client & { id: number }) | undefined
    const { server, info } = await publishedInfo(async (s) => {
      ana = await s.addAccount('ana')
      expect((await s.api('POST', '/spaces/journal/invites', { userId: ana.id })).status).toBe(201)
      const [invite] = (await ana.api<{ invites: { id: string }[] }>('GET', '/invites')).body.invites
      expect((await ana.api('POST', `/invites/${invite?.id}/accept`)).status).toBe(200)
    })
    expect((await server.api('PUT', '/spaces/journal/owner', { userId: ana?.id })).status).toBe(204)
    expect((await ana?.api('DELETE', '/spaces/journal/publish'))?.status).toBe(204)
    await act(() => reloadSpaces())
    // A member of a space that isn't published has no Website row: the rest of Space info, not a blank page.
    expect(await screen.findByRole('dialog', { name: 'Journal' })).toBe(info)
    await waitFor(() => expect(within(info).queryByText('Website')).toBeNull())
    expect(within(info).getByRole('button', { name: 'People' })).toBeTruthy()
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
    // A row, not a button: there's no page to open.
    expect(within(info).getByText('At notes.example/@journal')).toBeTruthy()
    expect(within(info).getByText('Website').closest('button')).toBeNull()
    expect(within(info).queryByRole('textbox', { name: 'Address' })).toBeNull()
  })
})
