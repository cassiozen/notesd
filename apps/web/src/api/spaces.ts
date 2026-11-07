import { request } from './client'
import type { Space, SpaceTrait } from './types'

const path = (id: string) => `/spaces/${encodeURIComponent(id)}`

/** A space as the server sent it: one older than traits (§7.8) leaves them out. */
export type WireSpace = Omit<Space, 'traits'> & { traits?: SpaceTrait[] | null }

export function readSpace(s: WireSpace): Space {
  return { ...s, traits: s.traits ?? [] }
}

export const spaces = {
  list: async () => {
    const body = await request<{ spaces: WireSpace[] }>('GET', '/spaces')
    return { spaces: body.spaces.map(readSpace) }
  },
  /**
   * Creates or renames, and sets the icon unless it's undefined; answers with
   * the name as stored (a clash gets a number).
   */
  put: async (id: string, name: string, icon?: string) => readSpace(await request<WireSpace>('PUT', path(id), { name, icon })),
  /** Only an empty space: 409 space_not_empty otherwise. */
  remove: (id: string) => request<undefined>('DELETE', path(id)),
}
