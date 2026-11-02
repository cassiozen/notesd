import { useEffect, useSyncExternalStore } from 'react'
import { agent, type AgentConfig, type AgentConfigInput } from '../api/agent'

// Each space's agent, keyed by space id: fetched once per page load and kept
// in step with saves. Agents run on the server, so this is never on the device.
let current: ReadonlyMap<string, AgentConfig> | null = null
let loading: Promise<void> | null = null
const listeners = new Set<() => void>()

function set(next: ReadonlyMap<string, AgentConfig>) {
  current = next
  listeners.forEach((l) => l())
}

function load() {
  loading ??= agent.list().then(
    ({ spaces }) => set(new Map(spaces.map((c) => [c.spaceId, c]))),
    () => {
      // Retried the next time something asks.
      loading = null
    },
  )
}

/** Every space's agent, or null until they've loaded (or while offline). */
export function useAgentConfigs(): ReadonlyMap<string, AgentConfig> | null {
  const value = useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => current,
  )
  useEffect(load, [])
  return value
}

/** Saves a space's agent and keeps the list in step. */
export async function saveAgentConfig(spaceId: string, input: AgentConfigInput): Promise<AgentConfig> {
  const stored = await agent.put(spaceId, input)
  set(new Map(current ?? []).set(spaceId, stored))
  return stored
}

/** For tests: forget what was loaded. */
export function resetAgentConfigs() {
  current = null
  loading = null
}
