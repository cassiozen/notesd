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
    expect(within(dialog).getByLabelText<HTMLInputElement>('Daily tokens per space').value).toBe('5000')
    await user.click(within(dialog).getByRole('button', { name: 'Disconnect…' }))
    await user.click(within(screen.getByRole('dialog', { name: 'Disconnect AI?' })).getByRole('button', { name: 'Disconnect' }))

    await user.click(await screen.findByRole('button', { name: 'Connect AI' }))
    dialog = screen.getByRole('dialog', { name: 'Connect AI' })
    expect(within(dialog).getByLabelText<HTMLInputElement>('Daily tokens per space').value).toBe('1000000')
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

  it('fills in a provider’s address, and says where its key comes from', async () => {
    const { user } = await signedIn()
    await openSettings(user, 'Integrations')
    await user.click(await screen.findByRole('button', { name: 'Connect AI' }))
    const dialog = screen.getByRole('dialog', { name: 'Connect AI' })
    const providers = within(dialog).getByRole('radiogroup', { name: 'Provider' })
    const address = within(dialog).getByLabelText<HTMLInputElement>('API address')
    const key = within(dialog).getByLabelText<HTMLInputElement>('API key')
    expect(within(providers).queryByRole('radio', { checked: true })).toBeNull()

    await user.click(within(providers).getByRole('radio', { name: 'OpenRouter' }))
    expect(address.value).toBe('https://openrouter.ai/api/v1')
    expect(key.placeholder).toBe('sk-or-…')
    expect(within(dialog).getByRole('link', { name: 'openrouter.ai' }).getAttribute('href')).toBe('https://openrouter.ai/settings/keys')

    await user.click(within(providers).getByRole('radio', { name: 'Ollama' }))
    expect(address.value).toBe('http://localhost:11434/v1')
    expect(within(dialog).getByText('Ollama doesn’t check keys: any word will do.')).toBeTruthy()

    await user.click(within(providers).getByRole('radio', { name: 'Other' }))
    expect(address.value).toBe('')
    expect(document.activeElement).toBe(address)
    expect(within(providers).getByRole('radio', { name: 'Other', checked: true })).toBeTruthy()

    // Typing a provider's address picks it.
    await user.type(address, 'https://api.openai.com/v1/')
    expect(within(providers).getByRole('radio', { name: 'OpenAI', checked: true })).toBeTruthy()
  })

  it('lists the provider’s models once there’s a key, and the saved provider’s when managing', async () => {
    const llm = await startFakeLLM()
    llm.models = ['fake-model', 'fake-model-mini']
    const { user } = await signedIn()
    await openSettings(user, 'Integrations')
    await user.click(await screen.findByRole('button', { name: 'Connect AI' }))
    let dialog = screen.getByRole('dialog', { name: 'Connect AI' })
    await user.type(within(dialog).getByLabelText('API address'), llm.url)
    await user.type(within(dialog).getByLabelText('API key'), KEY)
    expect(llm.modelsAuth).toEqual([])

    let model = within(dialog).getByLabelText<HTMLInputElement>('Model')
    await user.click(model)
    expect(await within(dialog).findByText('Pick one of the provider’s models, or type a name.')).toBeTruthy()
    expect([...(model.list?.options ?? [])].map((o) => o.value)).toEqual(['fake-model', 'fake-model-mini'])
    expect(llm.modelsAuth).toEqual([`Bearer ${KEY}`])
    expect(llm.requests).toHaveLength(0)

    // Another address, and the list goes with it; back to the listed one, it's there again.
    await user.type(within(dialog).getByLabelText('API address'), 'x')
    expect(model.list).toBeNull()
    await user.type(within(dialog).getByLabelText('API address'), '{Backspace}')
    expect(model.list?.options).toHaveLength(2)
    expect(llm.modelsAuth).toHaveLength(1)

    await user.type(model, 'fake-model-mini')
    await user.click(within(dialog).getByRole('button', { name: 'Connect' }))
    await user.click(await within(await screen.findByRole('region', { name: 'Connected' })).findByRole('button', { name: 'Manage AI' }))
    dialog = screen.getByRole('dialog', { name: 'AI' })
    model = within(dialog).getByLabelText<HTMLInputElement>('Model')
    await waitFor(() => expect([...(model.list?.options ?? [])].map((o) => o.value)).toEqual(['fake-model', 'fake-model-mini']))
    expect(llm.modelsAuth.at(-1)).toBe(`Bearer ${KEY}`)
  })

  it('keeps the saved key from an address it wasn’t saved for', async () => {
    const llm = await startFakeLLM()
    const other = await startFakeLLM()
    const { user } = await signedIn({
      seed: async (s) => void (await s.api('PUT', '/settings/ai', { baseUrl: llm.url, apiKey: KEY, model: 'm' })),
    })
    await openSettings(user, 'Integrations')
    await user.click(await screen.findByRole('button', { name: 'Manage AI' }))
    const dialog = screen.getByRole('dialog', { name: 'AI' })
    await waitFor(() => expect(llm.modelsAuth).toHaveLength(1))
    const address = within(dialog).getByLabelText('API address')
    await user.clear(address)
    await user.type(address, other.url)
    await user.click(within(dialog).getByLabelText('Model'))
    expect(other.modelsAuth).toEqual([])
  })

  it('says why the models couldn’t be listed, and still takes a typed name', async () => {
    const llm = await startFakeLLM()
    llm.models = { refuse: 'Incorrect API key provided' }
    const { user } = await signedIn()
    await openSettings(user, 'Integrations')
    await user.click(await screen.findByRole('button', { name: 'Connect AI' }))
    const dialog = screen.getByRole('dialog', { name: 'Connect AI' })
    await user.type(within(dialog).getByLabelText('API address'), llm.url)
    await user.type(within(dialog).getByLabelText('API key'), KEY)
    const model = within(dialog).getByLabelText<HTMLInputElement>('Model')
    await user.click(model)
    expect(await within(dialog).findByText('Couldn’t list the provider’s models: Incorrect API key provided')).toBeTruthy()
    expect(model.list).toBeNull()
    await user.type(model, 'fake-model')
    await user.click(within(dialog).getByRole('button', { name: 'Test' }))
    expect(await within(dialog).findByText(/^Connected\. fake-model-1 called a tool/)).toBeTruthy()
  })
})
