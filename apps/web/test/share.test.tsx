import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { createNote, openSettings, signedIn, typeAtEnd } from './helpers'
import { startFakeResend, type TestServer } from './server'

const KEY = 're_test_123456789'

async function configureEmail(server: TestServer) {
  const res = await server.api('PUT', '/settings/email', { apiKey: KEY, from: 'Me <notes@example.com>' })
  expect(res.status).toBe(200)
}

const moreMenu = () => screen.getByRole('menu', { name: 'More' })

describe('email settings', () => {
  it('saves the key and sender, shows only the key’s end, and removes them', async () => {
    const { server, user } = await signedIn()
    await openSettings(user, 'Integrations')
    const card = await screen.findByRole('region', { name: 'Email' })

    await user.type(within(card).getByLabelText('From'), 'Me <notes@example.com>')
    await user.click(within(card).getByRole('button', { name: 'Save' }))
    expect(await within(card).findByText('Paste your Resend API key.')).toBeTruthy()

    await user.type(within(card).getByLabelText('Resend API key'), 'sk_wrong')
    await user.click(within(card).getByRole('button', { name: 'Save' }))
    expect(await within(card).findByText('A Resend API key starts with re_.')).toBeTruthy()

    await user.clear(within(card).getByLabelText('Resend API key'))
    await user.type(within(card).getByLabelText('Resend API key'), KEY)
    await user.click(within(card).getByRole('button', { name: 'Save' }))
    expect(await within(card).findByText(/^Saved\./)).toBeTruthy()
    expect(within(card).getByLabelText('Resend API key').getAttribute('placeholder')).toBe('Saved · …6789')
    expect((await server.api('GET', '/settings/email')).body).toEqual({
      configured: true,
      from: 'Me <notes@example.com>',
      keyHint: '6789',
      editable: true,
    })

    await user.click(within(card).getByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(within(card).queryByRole('button', { name: 'Remove' })).toBeNull())
    expect((await server.api<{ configured: boolean }>('GET', '/settings/email')).body.configured).toBe(false)
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
