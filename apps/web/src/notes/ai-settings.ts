import { useEffect, useSyncExternalStore } from 'react'
import { settings } from '../api/settings'
import type { AiSettings } from '../api/types'

// As the server's llm.DefaultDailyTokenCap, which a removed setup reverts to.
export const DEFAULT_DAILY_TOKEN_CAP = 1_000_000

// One copy for the whole app, fetched once per account and replaced when
// Settings saves.
let current: AiSettings | null = null
let failed = false
let loading: Promise<void> | null = null
/** Moves on with each forget: an answer to a fetch made before it is for an account that's gone. */
let fetches = 0
const listeners = new Set<() => void>()

const notify = () => listeners.forEach((l) => l())

export function setAiSettings(next: AiSettings | null) {
  current = next
  failed = false
  notify()
}

function load() {
  const asked = fetches
  loading ??= settings.ai().then(
    (s) => {
      if (asked === fetches) setAiSettings(s)
    },
    () => {
      if (asked !== fetches) return
      // Retried the next time something asks.
      loading = null
      failed = true
      notify()
    },
  )
}

function subscribe(l: () => void) {
  listeners.add(l)
  return () => listeners.delete(l)
}

/** The AI settings, or null until they've loaded. */
export function useAiSettings(): AiSettings | null {
  const value = useSyncExternalStore(subscribe, () => current)
  // Again after a forget, so screens left open across a new sign-in fetch for it.
  useEffect(() => {
    if (value === null) load()
  }, [value])
  return value
}

/** Whether the last fetch failed, with nothing loaded; retryAiSettings asks again. */
export function useAiSettingsFailed(): boolean {
  return useSyncExternalStore(subscribe, () => failed && current === null)
}

export function retryAiSettings() {
  failed = false
  notify()
  load()
}

/** Drops what was loaded, and any fetch on its way: the next account sees its own (admins can change them, others can't). */
export function forgetAiSettings() {
  current = null
  failed = false
  loading = null
  fetches++
  notify()
}
