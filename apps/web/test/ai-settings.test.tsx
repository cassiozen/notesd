import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { openSettings, signedIn } from './helpers'
import { startFakeLLM } from './server'

const KEY = 'sk-test-123456789'

describe('AI settings', () => {
  it('tests before saving, saves, shows only the key’s end, and removes them', async () => {
    const llm = await startFakeLLM()
    const { server, user } = await signedIn()
    await openSettings(user, 'Integrations')
    const card = await screen.findByRole('region', { name: 'AI' })

    await user.type(within(card).getByLabelText('API address'), llm.url)
    await user.type(within(card).getByLabelText('Model'), 'fake-model')
    await user.click(within(card).getByRole('button', { name: 'Save' }))
    expect(await within(card).findByText('Paste the provider’s API key.')).toBeTruthy()

    await user.type(within(card).getByLabelText('API key'), KEY)
    await user.click(within(card).getByRole('button', { name: 'Test' }))
    expect(await within(card).findByText(/^Connected\. fake-model-1 called a tool/)).toBeTruthy()
    expect(llm.requests).toHaveLength(1)
    expect(llm.requests[0]?.model).toBe('fake-model')
    // Testing doesn't save.
    expect((await server.api<{ configured: boolean }>('GET', '/settings/ai')).body.configured).toBe(false)

    await user.click(within(card).getByRole('button', { name: 'Save' }))
    expect(await within(card).findByText(/^Saved\./)).toBeTruthy()
    expect(within(card).getByLabelText('API key').getAttribute('placeholder')).toBe('Saved · …6789')
    expect((await server.api('GET', '/settings/ai')).body).toEqual({
      configured: true,
      baseUrl: llm.url,
      model: 'fake-model',
      keyHint: '6789',
      dailyTokenCap: 1_000_000,
      editable: true,
    })

    await user.click(within(card).getByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(within(card).queryByRole('button', { name: 'Remove' })).toBeNull())
    expect((await server.api<{ configured: boolean }>('GET', '/settings/ai')).body.configured).toBe(false)
  })

  it('shows the default daily tokens again after Remove', async () => {
    const llm = await startFakeLLM()
    const { user } = await signedIn({
      seed: async (s) =>
        void (await s.api('PUT', '/settings/ai', { baseUrl: llm.url, apiKey: KEY, model: 'm', dailyTokenCap: 5000 })),
    })
    await openSettings(user, 'Integrations')
    const card = await screen.findByRole('region', { name: 'AI' })
    const cap = await within(card).findByLabelText<HTMLInputElement>('Daily tokens per account')
    await waitFor(() => expect(cap.value).toBe('5000'))
    await user.click(within(card).getByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(cap.value).toBe('1000000'))
  })

  it('says why a test failed', async () => {
    const llm = await startFakeLLM()
    llm.textOnly = true
    const { user } = await signedIn({
      seed: async (s) => void (await s.api('PUT', '/settings/ai', { baseUrl: llm.url, apiKey: KEY, model: 'm' })),
    })
    await openSettings(user, 'Integrations')
    const card = await screen.findByRole('region', { name: 'AI' })
    await user.click(await within(card).findByRole('button', { name: 'Test' }))
    expect((await within(card).findByRole('alert')).textContent).toMatch(/^The test failed: .*supports tool calls/)
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
