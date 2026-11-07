import { request } from './client'

/** An event a space's agent can act on; the keys of AgentConfig.triggers. */
export type AgentTrigger =
  | 'noteCreated'
  | 'editStarted'
  | 'newNoteDone'
  | 'editDone'
  | 'noteDeleted'
  | 'movedIn'
  | 'movedOut'
  | 'menu'

/** A space's agent. A trigger without a prompt does nothing. */
export type AgentConfig = {
  spaceId: string
  enabled: boolean
  prompt: string
  menuLabel: string
  triggers: Partial<Record<AgentTrigger, string>>
  updatedAt: number
}

export type AgentConfigInput = Pick<AgentConfig, 'enabled' | 'prompt' | 'menuLabel' | 'triggers'>

/** GET /settings/account. */
export type AccountSettings = { timeZone: string; timeZoneSource: 'default' | 'auto' | 'user' }

const path = (id: string) => `/agent/spaces/${encodeURIComponent(id)}`

export const agent = {
  /** Every space whose agent has been set up. */
  list: () => request<{ spaces: AgentConfig[] }>('GET', '/agent/spaces'),
  /** Replaces the space's agent; 404 not_found if the server doesn't have the space. */
  put: (spaceId: string, config: AgentConfigInput) => request<AgentConfig>('PUT', path(spaceId), config),
  account: () => request<AccountSettings>('GET', '/settings/account'),
  setTimeZone: (timeZone: string) => request<AccountSettings>('PUT', '/settings/account', { timeZone }),
}
