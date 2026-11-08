import { act, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { forgetEmailSettings, setEmailSettings } from '../src/notes/email-settings'
import { formatFrom, parseFrom } from '../src/settings/resend'
import { createNote, interceptApi, openSettings, signedIn, typeAtEnd } from './helpers'
import { startFakeResend, type TestServer } from './server'

const KEY = 're_test_123456789'

async function configureEmail(server: TestServer) {
  const res = await server.api('PUT', '/settings/email', { apiKey: KEY, from: 'Me <notes@example.com>' })
  expect(res.status).toBe(200)
}

const moreMenu = () => screen.getByRole('menu', { name: 'More' })

describe('email settings', () => {
  it('connects Resend in two steps, checking the key before asking for the sender', async () => {
    const { server, user } = await signedIn()
    await openSettings(user, 'Integrations')
    const available = await screen.findByRole('region', { name: 'Available' })
    await user.click(within(available).getByRole('button', { name: 'Connect Resend' }))

    let dialog = screen.getByRole('dialog', { name: 'Connect Resend' })
    await user.click(within(dialog).getByRole('button', { name: 'Continue' }))
    expect(within(dialog).getByText('Paste your Resend API key.')).toBeTruthy()
    await user.type(within(dialog).getByLabelText('API key'), 'sk_wrong')
    await user.click(within(dialog).getByRole('button', { name: 'Continue' }))
    expect(within(dialog).getByText('A Resend API key starts with re_.')).toBeTruthy()

    await user.clear(within(dialog).getByLabelText('API key'))
    await user.type(within(dialog).getByLabelText('API key'), KEY)
    await user.click(within(dialog).getByRole('button', { name: 'Continue' }))
    dialog = screen.getByRole('dialog', { name: 'Who it’s from' })
    await user.click(within(dialog).getByRole('button', { name: 'Connect' }))
    expect(within(dialog).getByText('Enter the address emails come from.')).toBeTruthy()
    await user.type(within(dialog).getByLabelText('Name'), 'Me, Myself')
    await user.type(within(dialog).getByLabelText('Address'), 'notes@example.com')
    await user.click(within(dialog).getByRole('button', { name: 'Connect' }))

    dialog = await screen.findByRole('dialog', { name: 'Ready to send' })
    expect((await server.api('GET', '/settings/email')).body).toEqual({
      configured: true,
      from: '"Me, Myself" <notes@example.com>',
      keyHint: '6789',
      editable: true,
    })
    await user.click(within(dialog).getByRole('button', { name: 'Done' }))
    const connected = screen.getByRole('region', { name: 'Connected' })
    // The Connect button went with the row; focus lands on the row's new one.
    await waitFor(() => expect(document.activeElement).toBe(within(connected).getByRole('button', { name: 'Manage Resend' })))
    expect(within(connected).getByText('Sending as "Me, Myself" <notes@example.com>')).toBeTruthy()
    expect(screen.queryByRole('region', { name: 'Available' })).toBeNull()
  })

  it('goes on by itself once a whole key is pasted', async () => {
    const { user } = await signedIn()
    await openSettings(user, 'Integrations')
    await user.click(await screen.findByRole('button', { name: 'Connect Resend' }))
    await user.click(screen.getByLabelText('API key'))
    await user.paste(KEY)
    expect(await screen.findByRole('dialog', { name: 'Who it’s from' })).toBeTruthy()
  })

  it('changes the sender, keeping the key, and disconnects', async () => {
    const { server, user } = await signedIn({ seed: configureEmail })
    await openSettings(user, 'Integrations')
    await user.click(await screen.findByRole('button', { name: 'Manage Resend' }))
    const dialog = screen.getByRole('dialog', { name: 'Resend' })
    expect(within(dialog).getByLabelText<HTMLInputElement>('Name').value).toBe('Me')
    expect(within(dialog).getByLabelText<HTMLInputElement>('Address').value).toBe('notes@example.com')
    expect(within(dialog).getByLabelText('API key').getAttribute('placeholder')).toBe('Saved · …6789')

    await user.clear(within(dialog).getByLabelText('Name'))
    await user.type(within(dialog).getByLabelText('Name'), 'Notes')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Resend' })).toBeNull())
    expect((await server.api<{ from: string; keyHint: string }>('GET', '/settings/email')).body).toMatchObject({
      from: 'Notes <notes@example.com>',
      keyHint: '6789',
    })

    await user.click(screen.getByRole('button', { name: 'Manage Resend' }))
    await user.click(within(screen.getByRole('dialog', { name: 'Resend' })).getByRole('button', { name: 'Disconnect…' }))
    await user.click(within(screen.getByRole('dialog', { name: 'Disconnect Resend?' })).getByRole('button', { name: 'Disconnect' }))
    expect(await screen.findByRole('region', { name: 'Available' })).toBeTruthy()
    expect((await server.api<{ configured: boolean }>('GET', '/settings/email')).body.configured).toBe(false)
  })

  it('offers no way to connect or change it to someone who isn’t an admin', async () => {
    const { user } = await signedIn()
    await openSettings(user, 'Integrations')
    const available = await screen.findByRole('region', { name: 'Available' })
    // As the server says it to someone else; set once loaded, so the load can't overwrite it.
    act(() => setEmailSettings({ configured: false, from: '', keyHint: '', editable: false }))
    const row = within(available).getByText('Resend').closest('li') as HTMLElement
    expect(within(row).getByText(/An admin can connect it\.$/)).toBeTruthy()
    expect(within(row).queryByRole('button')).toBeNull()
  })
})

describe('email settings across accounts and failures', () => {
  it('asks the server again after signing out: the next account may not be an admin', async () => {
    const { user, password } = await signedIn()
    await openSettings(user, 'Integrations')
    expect(await screen.findByRole('button', { name: 'Connect Resend' })).toBeTruthy()
    await user.click(screen.getByRole('link', { name: /^Server/ }))
    await user.click(screen.getByRole('button', { name: 'Sign out' }))

    interceptApi(
      (method, path) => method === 'GET' && path === '/api/v1/settings/email',
      () => Response.json({ configured: false, from: '', keyHint: '', editable: false }),
    )
    await user.type(await screen.findByLabelText('Username'), 'me')
    await user.type(screen.getByLabelText('Password'), `${password}{Enter}`)
    await within(await screen.findByRole('complementary', { name: 'Notes' })).findByText(/^\d+ notes?$/)
    await openSettings(user, 'Integrations')
    expect(await screen.findByText(/An admin can connect it\.$/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Connect Resend' })).toBeNull()
  })

  it('says when integrations couldn’t be loaded, and tries again when asked', async () => {
    const { user } = await signedIn()
    act(() => forgetEmailSettings())
    const stop = interceptApi(
      (method, path) => method === 'GET' && path === '/api/v1/settings/email',
      () => Response.json({ code: 'internal', message: 'boom' }, { status: 500 }),
    )
    await openSettings(user, 'Integrations')
    expect(await screen.findByText('Couldn’t load integrations.')).toBeTruthy()

    stop()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('button', { name: 'Connect Resend' })).toBeTruthy()
    expect(screen.queryByText('Couldn’t load integrations.')).toBeNull()
  })
})

describe('sender address', () => {
  it('quotes a name the server would otherwise refuse, and reads it back', () => {
    expect(formatFrom('Me', 'a@b.c')).toBe('Me <a@b.c>')
    expect(formatFrom(' ', ' a@b.c ')).toBe('a@b.c')
    expect(formatFrom('Doe, J. "Jo"', 'a@b.c')).toBe('"Doe, J. \\"Jo\\"" <a@b.c>')
    expect(parseFrom('"Doe, J. \\"Jo\\"" <a@b.c>')).toEqual({ name: 'Doe, J. "Jo"', address: 'a@b.c' })
    expect(parseFrom('Me <a@b.c>')).toEqual({ name: 'Me', address: 'a@b.c' })
    expect(parseFrom('a@b.c')).toEqual({ name: '', address: 'a@b.c' })
  })
})

describe('note menu', () => {
  it('offers email only once a Resend key is set', async () => {
    const { user } = await signedIn({ seed: async (s) => void (await createNote(s, 'Plans', 'Bike')) })
    await user.click(await screen.findByRole('button', { name: 'More' }))
    expect(within(moreMenu()).queryByRole('menuitem', { name: 'Email…' })).toBeNull()
    expect(within(moreMenu()).getByRole('menuitem', { name: 'Copy as Markdown' })).toBeTruthy()
  })

  it('emails what the editor shows, after saving it', async () => {
    const resend = await startFakeResend()
    const { user } = await signedIn({
      resendUrl: resend.url,
      seed: async (s) => {
        await configureEmail(s)
        await createNote(s, 'Plans', 'Bike')
      },
    })
    await screen.findByRole('textbox', { name: 'Note' })
    // Not saved yet when Send is pressed: sending waits for the save.
    await typeAtEnd(user, ' and park')

    await user.click(await screen.findByRole('button', { name: 'More' }))
    await user.click(await within(moreMenu()).findByRole('menuitem', { name: 'Email…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Email this note' })
    expect(within(dialog).getByText('From Me <notes@example.com>. Separate addresses with commas.')).toBeTruthy()

    await user.type(within(dialog).getByLabelText('To'), 'sam@example.org, kim@example.org')
    await user.click(within(dialog).getByRole('button', { name: 'Send' }))
    expect(await screen.findByText('Sent to sam@example.org, kim@example.org.')).toBeTruthy()

    expect(resend.sent).toHaveLength(1)
    const [mail] = resend.sent
    expect(mail?.to).toEqual(['sam@example.org', 'kim@example.org'])
    expect(mail?.subject).toBe('Plans')
    expect(mail?.text).toBe('# Plans\n\nBike and park\n')
  })

  it('shows Resend’s reason when it refuses', async () => {
    const resend = await startFakeResend()
    resend.refuse = { status: 403, message: 'The example.com domain is not verified.' }
    const { user } = await signedIn({
      resendUrl: resend.url,
      seed: async (s) => {
        await configureEmail(s)
        await createNote(s, 'Plans', 'Bike')
      },
    })
    await user.click(await screen.findByRole('button', { name: 'More' }))
    await user.click(await within(moreMenu()).findByRole('menuitem', { name: 'Email…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Email this note' })
    await user.type(within(dialog).getByLabelText('To'), 'sam@example.org')
    await user.click(within(dialog).getByRole('button', { name: 'Send' }))
    expect(
      await within(dialog).findByText('Resend didn’t send it: The example.com domain is not verified.'),
    ).toBeTruthy()
  })
})
