import { request } from './client'
import type { Session, SessionInfo } from './types'
import { deviceName } from './user-agent'

export const auth = {
  /** serverVersion and apiVersion: absent from servers before 1.0. */
  status: () => request<{ setupRequired: boolean; serverVersion?: string; apiVersion?: number }>('GET', '/auth/status'),
  setup: (username: string, password: string) =>
    request<{ status: 'created' }>('POST', '/auth/setup', { username, password }),
  login: (username: string, password: string) =>
    request<SessionInfo>('POST', '/auth/login', { username, password, client: 'web', deviceName: deviceName() }),
  logout: () => request<undefined>('POST', '/auth/logout'),
  session: () => request<SessionInfo>('GET', '/auth/session'),
  changePassword: (currentPassword: string, newPassword: string) =>
    request<undefined>('POST', '/auth/password', { currentPassword, newPassword }),
  sessions: () => request<{ sessions: Session[]; currentId: string }>('GET', '/auth/sessions'),
  revokeSession: (id: string) =>
    request<undefined>('DELETE', `/auth/sessions/${encodeURIComponent(id)}`),
}
