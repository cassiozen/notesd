import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { openSettings, signedIn } from './helpers'
import { startFakeLLM } from './server'

const KEY = 'sk-test-123456789'

describe('AI settings', () => {
  it('tests before connecting, connects, shows only the key’s end, and disconnects', async () => {
    const llm = await startFakeLLM()
    const { server, user } = await signedIn()
    await openSettings(user, 'Integrations')
    await user.click(await screen.findByRole('button', { name: 'Connect AI' }))
    let dialog = screen.getByRole('dialog', { name: 'Connect AI' })

    await user.type(within(dialog).getByLabelText('API address'), llm.url)
    await user.type(within(dialog).getByLabelText('Model'), 'fake-model')
    await user.click(within(dialog).getByRole('button', { name: 'Connect' }))
    expect(await within(dialog).findByText('Paste the provider’s API key.')).toBeTruthy()

    await user.type(within(dialog).getByLabelText('API key'), KEY)
    await user.click(within(dialog).getByRole('button', { name: 'Test' }))
    expect(await within(dialog).findByText(/^Connected\. fake-model-1 called a tool/)).toBeTruthy()
    expect(llm.requests).toHaveLength(1)
    expect(llm.requests[0]?.model).toBe('fake-model')
    // Testing doesn't save.
    expect((await server.api<{ configured: boolean }>('GET', '/settings/ai')).body.configured).toBe(false)

    await user.click(within(dialog).getByRole('button', { name: 'Connect' }))
    const connected = await screen.findByRole('region', { name: 'Connected' })
    expect(within(connected).getByText(`fake-model at ${new URL(llm.url).host}`)).toBeTruthy()
    expect((await server.api('GET', '/settings/ai')).body).toEqual({
      configured: true,
      baseUrl: llm.url,
      model: 'fake-model',
      keyHint: '6789',
      dailyTokenCap: 1_000_000,
      editable: true,
    })

    await user.click(within(connected).getByRole('button', { name: 'Manage AI' }))
    dialog = screen.getByRole('dialog', { name: 'AI' })
    expect(within(dialog).getByLabelText('API key').getAttribute('placeholder')).toBe('Saved · …6789')
    await user.click(within(dialog).getByRole('button', { name: 'Disconnect…' }))
    await user.click(within(screen.getByRole('dialog', { name: 'Disconnect AI?' })).getByRole('button', { name: 'Disconnect' }))
    expect(await screen.findByRole('button', { name: 'Connect AI' })).toBeTruthy()
    expect((await server.api<{ configured: boolean }>('GET', '/settings/ai')).body.configured).toBe(false)
  })

  it('shows the default daily tokens again after a disconnect', async () => {
    const llm = await startFakeLLM()
    const { user } = await signedIn({
      seed: async (s) =>
        void (await s.api('PUT', '/settings/ai', { baseUrl: llm.url, apiKey: KEY, model: 'm', dailyTokenCap: 5000 })),
    })
    await openSettings(user, 'Integrations')
    await user.click(await screen.findByRole('button', { name: 'Manage AI' }))
    let dialog = screen.getByRole('dialog', { name: 'AI' })
    expect(within(dialog).getByLabelText<HTMLInputElement>('Daily tokens per account').value).toBe('5000')
    await user.click(within(dialog).getByRole('button', { name: 'Disconnect…' }))
    await user.click(within(screen.getByRole('dialog', { name: 'Disconnect AI?' })).getByRole('button', { name: 'Disconnect' }))

    await user.click(await screen.findByRole('button', { name: 'Connect AI' }))
    dialog = screen.getByRole('dialog', { name: 'Connect AI' })
    expect(within(dialog).getByLabelText<HTMLInputElement>('Daily tokens per account').value).toBe('1000000')
  })

  it('says why a test failed', async () => {
    const llm = await startFakeLLM()
    llm.textOnly = true
    const { user } = await signedIn({
      seed: async (s) => void (await s.api('PUT', '/settings/ai', { baseUrl: llm.url, apiKey: KEY, model: 'm' })),
    })
    await openSettings(user, 'Integrations')
    await user.click(await screen.findByRole('button', { name: 'Manage AI' }))
    const dialog = screen.getByRole('dialog', { name: 'AI' })
    await user.click(within(dialog).getByRole('button', { name: 'Test' }))
    expect((await within(dialog).findByRole('alert')).textContent).toMatch(/^The test failed: .*supports tool calls/)
  })

  it('shows in the category’s value', async () => {
    const llm = await startFakeLLM()
    const { user } = await signedIn({
      seed: async (s) => void (await s.api('PUT', '/settings/ai', { baseUrl: llm.url, apiKey: KEY, model: 'm' })),
    })
    await openSettings(user)
    await waitFor(() =>
      expect(screen.getByRole('link', { name: /^Integrations/ }).textContent).toBe('IntegrationsAI'),
    )
  })
})
