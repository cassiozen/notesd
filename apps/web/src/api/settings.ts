import { request } from './client'
import type { EmailSettings, UpdateStatus } from './types'

export const settings = {
  email: () => request<EmailSettings>('GET', '/settings/email'),
  /** Omit apiKey to keep the saved one. */
  saveEmail: (from: string, apiKey?: string) =>
    request<EmailSettings>('PUT', '/settings/email', { from, ...(apiKey && { apiKey }) }),
  removeEmail: () => request<undefined>('DELETE', '/settings/email'),
  update: () => request<UpdateStatus>('GET', '/update'),
  requestUpdate: () => request<undefined>('POST', '/update'),
}
