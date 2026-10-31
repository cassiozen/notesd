import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { captureInstallPrompt } from '../src/install'
import { navigate } from '../src/router'
import { inProcessEngine } from './engine/helpers'
import { createNote, noteTitle, openSettings, phoneLayout, signedIn, startEngines } from './helpers'

/** Signed in, with Settings open at one of its categories. */
async function inSettings(category?: string) {
  const ctx = await signedIn()
  await openSettings(ctx.user, category)
  return ctx
}

const nav = () => screen.getByRole('navigation', { name: 'Settings categories' })
const category = (name: string) => within(nav()).getByRole('link', { name: new RegExp(`^${name}`) })

describe('settings', () => {
  it('switches the theme and remembers it', async () => {
    // index.html's; jsdom doesn't load it.
    const metas = (['light', 'dark'] as const).map((scheme) => {
      const meta = document.createElement('meta')
      meta.name = 'theme-color'
      meta.media = `(prefers-color-scheme: ${scheme})`
      document.head.append(meta)
      return meta
    })
    const colors = () => metas.map((m) => m.content)
    const { user } = await inSettings('Appearance')
    const group = screen.getByRole('radiogroup', { name: 'Appearance' })
    expect(within(group).getByRole('radio', { name: 'System' }).getAttribute('aria-checked')).toBe('true')

    await user.click(within(group).getByRole('radio', { name: 'Dark' }))
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(localStorage.getItem('notes.theme')).toBe('dark')
    // The title bar follows the picked theme, whatever the system's.
    expect(colors()).toEqual(['#121416', '#121416'])

    await user.click(within(group).getByRole('radio', { name: 'System' }))
    expect(document.documentElement.dataset.theme).toBeUndefined()
    expect(localStorage.getItem('notes.theme')).toBeNull()
    expect(colors()).toEqual(['#F5F3F1', '#121416'])
    for (const m of metas) m.remove()
  })

  it('picks the font for notes and remembers it', async () => {
    const { user } = await inSettings('Appearance')
    const group = screen.getByRole('radiogroup', { name: 'Font' })
    const radio = (name: string) => within(group).getByRole<HTMLInputElement>('radio', { name })
    expect(radio('Sans').checked).toBe(true)

    await user.click(radio('Mono'))
    expect(radio('Mono').checked).toBe(true)
    expect(document.documentElement.dataset.font).toBe('mono')
    expect(localStorage.getItem('notes.font')).toBe('mono')

    // Sans is the default: nothing to store.
    await user.click(within(group).getByRole('radio', { name: 'Sans' }))
    expect(document.documentElement.dataset.font).toBeUndefined()
    expect(localStorage.getItem('notes.font')).toBeNull()
  })

  it('changes the password and signs out every other session', async () => {
    const { server, user, password } = await inSettings()

    await user.type(screen.getByLabelText('Current password'), 'not it')
    await user.type(screen.getByLabelText('New password'), 'a brand new one')
    await user.type(screen.getByLabelText('Confirm new password'), 'a brand new one')
    await user.click(screen.getByRole('button', { name: 'Change password' }))
    expect(await screen.findByText('That isn’t your current password.')).toBeTruthy()

    await user.clear(screen.getByLabelText('Current password'))
    await user.type(screen.getByLabelText('Current password'), password)
    await user.click(screen.getByRole('button', { name: 'Change password' }))
    expect(await screen.findByText(/Password changed\. Every other browser and device was signed out/)).toBeTruthy()

    // The other client was signed out; the new password works.
    expect((await server.api('GET', '/auth/session')).status).toBe(401)
    const login = await server.api('POST', '/auth/login', { username: 'me', password: 'a brand new one', client: 'web' })
    expect(login.status).toBe(200)
    // This browser stays signed in.
    expect(screen.getByRole('heading', { name: 'Settings' })).toBeTruthy()
  })

  it('lists signed-in devices and signs one out', async () => {
    const { server, user } = await inSettings('Security')
    const devices = screen.getByRole('region', { name: 'Signed-in devices' })
    await within(devices).findByText('2 devices')
    expect(within(devices).getByText('This browser')).toBeTruthy()

    const other = within(devices).getByRole('button', { name: /^Sign out / })
    await user.click(other)
    await within(devices).findByText('1 device')
    expect((await server.api('GET', '/auth/session')).status).toBe(401)
  })
})

describe('settings categories', () => {
  it('opens on Server and shows each category’s state beside it', async () => {
    await signedIn({
      seed: async (s) => {
        await s.api('PUT', '/settings/email', { apiKey: 're_test_123456789', from: 'Me <notes@example.com>' })
      },
    })
    await openSettings(userEvent.setup())
    // Who's signed in, and where, opens Server.
    expect(category('Server').getAttribute('aria-label')).toBe(`Server: me on ${window.location.host}`)
    expect(category('Server').getAttribute('aria-current')).toBe('page')
    expect(screen.getByRole('heading', { name: 'Server', level: 3 })).toBeTruthy()
    await waitFor(() => expect(category('Security').textContent).toBe('SecurityOff'))
    await waitFor(() => expect(category('Spaces').textContent).toBe('Spaces1'))
    await waitFor(() => expect(category('Integrations').textContent).toBe('IntegrationsEmail'))
    expect(category('Appearance').textContent).toBe('AppearanceSystem')
    expect(category('Sync & data').textContent).toBe('Sync & dataOff')
    // Each group says where its settings are kept.
    expect(within(nav()).getAllByRole('list', { name: 'Synced to your account' })).toHaveLength(1)
    expect(within(within(nav()).getByRole('list', { name: 'This browser only' })).getAllByRole('link')).toHaveLength(2)
  })

  it('moves between categories without stacking up history, and Done still goes back', async () => {
    const { user } = await signedIn()
    await openSettings(user, 'Security')
    expect(window.location.pathname).toBe('/settings/security')
    expect(await screen.findByRole('region', { name: 'Signed-in devices' })).toBeTruthy()
    expect(screen.queryByRole('region', { name: 'Password' })).toBeNull()

    const entries = window.history.length
    await user.click(category('Appearance'))
    expect(window.location.pathname).toBe('/settings/appearance')
    expect(window.history.length).toBe(entries)
    expect(category('Appearance').getAttribute('aria-current')).toBe('page')
    expect(screen.getByRole('radiogroup', { name: 'Appearance' })).toBeTruthy()

    await user.click(screen.getByRole('button', { name: 'Done' }))
    expect(window.location.pathname).toBe('/')
  })

  it('opens a category from its address, and Server for one it doesn’t know', async () => {
    await signedIn()
    act(() => navigate('/settings/integrations'))
    expect(await screen.findByRole('region', { name: 'Email' })).toBeTruthy()
    act(() => navigate('/settings/nope'))
    expect(await screen.findByText('Signed in as me')).toBeTruthy()
  })

  it('on a phone, shows the list, then a category with a way back to it', async () => {
    phoneLayout()
    const { user } = await signedIn()
    // On a phone, Settings is on the tags screen.
    act(() => navigate('/settings'))
    await screen.findByRole('navigation', { name: 'Settings categories' })
    expect(screen.queryByRole('region', { name: 'Server' })).toBeNull()

    await user.click(category('Spaces'))
    expect(await screen.findByRole('heading', { name: 'Spaces', level: 2 })).toBeTruthy()
    expect(screen.queryByRole('navigation', { name: 'Settings categories' })).toBeNull()
    expect(screen.getByRole('region', { name: 'Spaces' })).toBeTruthy()
    // The page starts at its title.
    expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Spaces', level: 2 }))

    // Back pops the page's entry, so browser Back doesn't reopen it.
    const entries = window.history.length
    await user.click(screen.getByRole('button', { name: 'Back to Settings' }))
    await waitFor(() => expect(window.location.pathname).toBe('/settings'))
    expect(nav()).toBeTruthy()
    // And the list returns to the category that was open.
    expect(window.history.length).toBe(entries)
    await waitFor(() => expect(document.activeElement).toBe(category('Spaces')))
  })

  it('on a phone, goes back to the list from a category opened by its address', async () => {
    phoneLayout()
    const { user } = await signedIn()
    act(() => navigate('/settings/spaces'))
    await screen.findByRole('heading', { name: 'Spaces', level: 2 })
    const entries = window.history.length
    await user.click(screen.getByRole('button', { name: 'Back to Settings' }))
    expect(window.location.pathname).toBe('/settings')
    expect(window.history.length).toBe(entries)
    expect(nav()).toBeTruthy()
  })
})

describe('closing settings', () => {
  it('returns to the note that was open behind it', async () => {
    const { user } = await signedIn({
      seed: async (s) => {
        await createNote(s, 'Behind')
      },
    })
    await waitFor(() => expect(noteTitle()).toBe('Behind'))
    const path = window.location.pathname
    await user.click(screen.getByRole('button', { name: 'Settings' }))
    expect(noteTitle()).toBe('Behind')

    await user.click(await screen.findByRole('button', { name: 'Done' }))
    expect(window.location.pathname).toBe(path)
    expect(screen.queryByRole('heading', { name: 'Settings' })).toBeNull()
  })
})

describe('connection', () => {
  it('says nothing about an unreachable server until something the user did fails', async () => {
    const { server, user } = await signedIn()
    server.setOffline(true)
    // Refetches in the background, which fails.
    window.dispatchEvent(new Event('online'))
    await new Promise((r) => setTimeout(r, 100))
    expect(screen.queryByText(/connection|reach the server/i)).toBeNull()

    await user.type(screen.getByRole('searchbox', { name: 'Search notes' }), 'x')
    expect(await screen.findByText('Search didn’t work.')).toBeTruthy()
  })

  it('offers installing as an app only while Chrome says it can be', async () => {
    // main.tsx's; tests render App alone.
    captureInstallPrompt()
    // A browser that can keep notes: jsdom's real check says no.
    startEngines(inProcessEngine)
    const { user } = await inSettings('Sync & data')
    expect(screen.queryByRole('button', { name: 'Install notesd' })).toBeNull()

    const prompt = vi.fn(async () => {})
    act(() => {
      const event = Object.assign(new Event('beforeinstallprompt', { cancelable: true }), {
        prompt,
        userChoice: Promise.resolve({ outcome: 'accepted' }),
      })
      window.dispatchEvent(event)
      // Chrome's own install offer still shows; Settings' is an extra way in.
      expect(event.defaultPrevented).toBe(false)
    })
    const button = await screen.findByRole('button', { name: 'Install notesd' })
    // What the installed app does on its first open.
    expect(screen.getByText(/keeps your notes on this device too/)).toBeTruthy()
    await user.click(button)
    expect(prompt).toHaveBeenCalledOnce()
    // prompt() works once per event.
    expect(screen.queryByRole('button', { name: 'Install notesd' })).toBeNull()

    // A fresh event, then installed from the browser's own menu.
    act(() => {
      window.dispatchEvent(Object.assign(new Event('beforeinstallprompt', { cancelable: true }), { prompt }))
    })
    expect(await screen.findByRole('button', { name: 'Install notesd' })).toBeTruthy()
    act(() => {
      window.dispatchEvent(new Event('appinstalled'))
    })
    expect(screen.queryByRole('button', { name: 'Install notesd' })).toBeNull()
  })
})
