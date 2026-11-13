import { request } from './client'
import type { AiModels, AiModelsInput, AiSettings, AiSettingsInput, AiTestResult, EmailSettings, UpdateStatus } from './types'

export const settings = {
  email: () => request<EmailSettings>('GET', '/settings/email'),
  /** Omit apiKey to keep the saved one. */
  saveEmail: (from: string, apiKey?: string) =>
    request<EmailSettings>('PUT', '/settings/email', { from, ...(apiKey && { apiKey }) }),
  removeEmail: () => request<undefined>('DELETE', '/settings/email'),
  ai: () => request<AiSettings>('GET', '/settings/ai'),
  saveAi: (input: AiSettingsInput) => request<AiSettings>('PUT', '/settings/ai', input),
  removeAi: () => request<undefined>('DELETE', '/settings/ai'),
  /** Tests input before it's saved, or the saved settings without it. */
  testAi: (input?: AiSettingsInput) => request<AiTestResult>('POST', '/settings/ai/test', input),
  aiModels: (input: AiModelsInput) => request<AiModels>('POST', '/settings/ai/models', input),
  update: () => request<UpdateStatus>('GET', '/update'),
  requestUpdate: () => request<undefined>('POST', '/update'),
}
