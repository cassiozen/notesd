import { useEffect, useSyncExternalStore } from 'react'
import { settings } from '../api/settings'
import type { AiSettings } from '../api/types'

// One copy for the whole app, fetched once per page load and replaced when
// Settings saves.
let current: AiSettings | null = null
let loading: Promise<void> | null = null
const listeners = new Set<() => void>()

export function setAiSettings(next: AiSettings | null) {
  current = next
  listeners.forEach((l) => l())
}

function load() {
  loading ??= settings.ai().then(
    (s) => setAiSettings(s),
    () => {
      // Retried the next time something asks.
      loading = null
    },
  )
}

/** The AI settings, or null until they've loaded. */
export function useAiSettings(): AiSettings | null {
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

/** For tests: forget what was loaded. */
export function resetAiSettings() {
  current = null
  loading = null
}
