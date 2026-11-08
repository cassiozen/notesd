import { request } from './client'
import type { Account, Invite, Space, SpaceTrait } from './types'

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

/**
 * Sharing a space (§7.9). Only its owner invites, cancels, removes others and
 * hands it on (403 not_space_owner); a member removes only themselves (leaving).
 */
export const sharing = {
  /** Every account on the server, to invite. */
  people: async () => (await request<{ people: Account[] }>('GET', '/people')).people,
  /** The first shares the space, its notes moving to its own file (409 space_default, space_has_locked). */
  invite: (space: string, userId: number) =>
    request<{ id: string; userId: number; username: string }>('POST', `${path(space)}/invites`, { userId }),
  cancel: (space: string, invite: string) => request<undefined>('DELETE', `${path(space)}/invites/${encodeURIComponent(invite)}`),
  /** The owner removes a member, or a member leaves (the owner can't: 409 owner_cannot_leave). */
  remove: (space: string, userId: number) => request<undefined>('DELETE', `${path(space)}/members/${userId}`),
  /** Hands the space to another member. */
  transfer: (space: string, userId: number) => request<undefined>('PUT', `${path(space)}/owner`, { userId }),
  /** Waiting for this account. */
  invites: async () => (await request<{ invites: Invite[] }>('GET', '/invites')).invites,
  /** Joins: answers with the space. */
  accept: async (invite: string) => readSpace(await request<WireSpace>('POST', `/invites/${encodeURIComponent(invite)}/accept`)),
  decline: (invite: string) => request<undefined>('POST', `/invites/${encodeURIComponent(invite)}/decline`),
}
