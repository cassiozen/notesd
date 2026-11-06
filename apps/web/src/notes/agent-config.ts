import { useEffect, useSyncExternalStore } from 'react'
import { agent, type AgentConfig, type AgentConfigInput } from '../api/agent'

// Each space's agent, keyed by space id: fetched once per page load and kept
// in step with saves. Agents run on the server, so this is never on the device.
let current: ReadonlyMap<string, AgentConfig> | null = null
let loading: Promise<void> | null = null
// Bumped on forget: a fetch or save started before it is for the account signed out.
let generation = 0
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
  const g = generation
  loading = agent.list().then(
    ({ spaces }) => {
      if (g === generation) set(new Map(spaces.map((c) => [c.spaceId, c])))
    },
    () => {
      // Retried the next time something asks.
      if (g === generation) loading = null
    },
  )
}

/** Every space's agent, or null until they've loaded (or while offline). */
export function useAgentConfigs(): ReadonlyMap<string, AgentConfig> | null {
  const value = useSyncExternalStore(subscribe, () => current)
  // Again after a forget (screens left open across a new sign-in), or once back online after a failed fetch.
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
  const g = generation
  const stored = await agent.put(spaceId, input)
  if (g === generation) set(new Map(current ?? []).set(spaceId, stored))
  return stored
}

/** Drops what was loaded, and any fetch on its way: another account may sign in next. */
export function forgetAgentConfigs() {
  current = null
  loading = null
  generation++
  listeners.forEach((l) => l())
}
