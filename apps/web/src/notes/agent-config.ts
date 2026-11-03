import { useEffect, useSyncExternalStore } from 'react'
import { agent, type AgentConfig, type AgentConfigInput } from '../api/agent'

// Each space's agent, keyed by space id: fetched once per page load and kept
// in step with saves. Agents run on the server, so this is never on the device.
let current: ReadonlyMap<string, AgentConfig> | null = null
let loading: Promise<void> | null = null
// Bumped on reset: a fetch started before it is for the account signed out.
let fetches = 0
const listeners = new Set<() => void>()

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

function set(next: ReadonlyMap<string, AgentConfig>) {
  current = next
  listeners.forEach((l) => l())
}

function load() {
  if (loading) return
  const n = ++fetches
  loading = agent.list().then(
    ({ spaces }) => {
      if (n === fetches) set(new Map(spaces.map((c) => [c.spaceId, c])))
    },
    () => {
      // Retried the next time something asks.
      if (n === fetches) loading = null
    },
  )
}

/** Every space's agent, or null until they've loaded (or while offline). */
export function useAgentConfigs(): ReadonlyMap<string, AgentConfig> | null {
  const value = useSyncExternalStore(subscribe, () => current)
  // Again after a reset (screens left open across a new sign-in), or once back online after a failed fetch.
  useEffect(() => {
    if (value !== null) return
    load()
    window.addEventListener('online', load)
    return () => window.removeEventListener('online', load)
  }, [value])
  return value
}

/** Saves a space's agent and keeps the list in step. */
export async function saveAgentConfig(spaceId: string, input: AgentConfigInput): Promise<AgentConfig> {
  const stored = await agent.put(spaceId, input)
  set(new Map(current ?? []).set(spaceId, stored))
  return stored
}

/** Forgets what was loaded, and any fetch on its way: another account may sign in next. */
export function resetAgentConfigs() {
  current = null
  loading = null
  fetches++
  listeners.forEach((l) => l())
}
