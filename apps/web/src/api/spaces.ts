import { request } from './client'
import type { Space } from './types'

const path = (id: string) => `/spaces/${encodeURIComponent(id)}`

export const spaces = {
  list: () => request<{ spaces: Space[] }>('GET', '/spaces'),
  /** Creates or renames; answers with the name as stored (a clash gets a number). */
  put: (id: string, name: string) => request<Space>('PUT', path(id), { name }),
  /** Only an empty space: 409 space_not_empty otherwise. */
  remove: (id: string) => request<undefined>('DELETE', path(id)),
}
