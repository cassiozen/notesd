import { request } from './client'
import type { Person, SetupLink } from './types'

const path = (id: number) => `/users/${id}`

/** Admins only: everyone else gets 403 admin_only. */
export const users = {
  list: () => request<{ users: Person[] }>('GET', '/users'),
  invite: (username: string) => request<{ user: Person; link: SetupLink }>('POST', '/users', { username }),
  /** Replaces the account's link; its password keeps working until the new one is used. */
  newLink: (id: number) => request<SetupLink>('POST', `${path(id)}/link`),
  setAdmin: (id: number, isAdmin: boolean) => request<Person>('PATCH', path(id), { isAdmin }),
  remove: (id: number) => request<undefined>('DELETE', path(id)),
}

/** The page a setup link opens, before anyone is signed in. */
export const setupLinks = {
  /** 410 link_invalid once used, replaced or expired. */
  peek: (token: string) => request<{ username: string; pending: boolean }>('POST', '/auth/setup-link', { token }),
  /** Signs the account out everywhere; sign in with the new password after. */
  use: (token: string, password: string) => request<undefined>('POST', '/auth/setup-link/use', { token, password }),
}
