import { useEffect, useSyncExternalStore } from 'react'
import { settings } from '../api/settings'
import type { AiSettings } from '../api/types'

// As the server's llm.DefaultDailyTokenCap, which a removed setup reverts to.
export const DEFAULT_DAILY_TOKEN_CAP = 1_000_000

// One copy for the whole app, fetched once per page load and replaced when
// Settings saves.
let current: AiSettings | null = null
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

export function setAiSettings(next: AiSettings | null) {
  current = next
  listeners.forEach((l) => l())
}

function load() {
  if (loading) return
  const n = ++fetches
  loading = settings.ai().then(
    (s) => {
      if (n === fetches) setAiSettings(s)
    },
    () => {
      // Retried the next time something asks.
      if (n === fetches) loading = null
    },
  )
}

/** The AI settings, or null until they've loaded. */
export function useAiSettings(): AiSettings | null {
  const value = useSyncExternalStore(subscribe, () => current)
  // Again after a reset, so screens left open across a new sign-in fetch for it.
  useEffect(() => {
    if (value === null) load()
  }, [value])
  return value
}

/** Forgets what was loaded, and any fetch on its way: another account may sign in next. */
export function resetAiSettings() {
  current = null
  loading = null
  fetches++
  listeners.forEach((l) => l())
}
