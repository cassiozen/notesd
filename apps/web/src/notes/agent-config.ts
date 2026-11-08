import { agent, type AgentConfig, type AgentConfigInput } from '../api/agent'
import { accountStore } from './account-store'

// Each space's agent, keyed by space id. Agents run on the server, so this is never on the device.
const store = accountStore(async () => {
  const { spaces } = await agent.list()
  return new Map(spaces.map((c) => [c.spaceId, c])) as ReadonlyMap<string, AgentConfig>
})

/** A space's agent, as last loaded; outside React. */
export function agentConfigFor(spaceId: string): AgentConfig | undefined {
  return store.get()?.get(spaceId)
}

/** Every space's agent, or null until they've loaded (or while offline). */
export const useAgentConfigs: () => ReadonlyMap<string, AgentConfig> | null = store.use

/** Saves a space's agent and keeps the list in step. */
export async function saveAgentConfig(spaceId: string, input: AgentConfigInput): Promise<AgentConfig> {
  const session = store.session()
  const stored = await agent.put(spaceId, input)
  if (session === store.session()) store.set(new Map(store.get() ?? []).set(spaceId, stored))
  return stored
}

export const forgetAgentConfigs = store.forget
