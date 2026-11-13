export type AiProvider = {
  id: 'openai' | 'openrouter' | 'ollama'
  name: string
  baseUrl: string
  keyPlaceholder: string
  /** Where to make a key; none for a local model, which doesn't check it. */
  keyUrl?: string
}

// No model per provider: names go stale, and the dialog lists the provider's own.
export const aiProviders: readonly AiProvider[] = [
  {
    id: 'openai',
    name: 'OpenAI',
    baseUrl: 'https://api.openai.com/v1',
    keyPlaceholder: 'sk-…',
    keyUrl: 'https://platform.openai.com/api-keys',
  },
  {
    id: 'openrouter',
    name: 'OpenRouter',
    baseUrl: 'https://openrouter.ai/api/v1',
    keyPlaceholder: 'sk-or-…',
    keyUrl: 'https://openrouter.ai/settings/keys',
  },
  { id: 'ollama', name: 'Ollama', baseUrl: 'http://localhost:11434/v1', keyPlaceholder: 'ollama' },
]

/** The provider at this address, as the server saves it: no trailing slash. */
export function providerAt(baseUrl: string): AiProvider | undefined {
  const url = baseUrl.trim().replace(/\/+$/, '')
  return aiProviders.find((p) => p.baseUrl === url)
}
