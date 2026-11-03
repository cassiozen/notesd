import { useEffect, useSyncExternalStore } from 'react'
import { settings } from '../api/settings'
import type { EmailSettings } from '../api/types'

// One copy for the share menu and Settings, fetched once per page load and
// replaced when Settings saves.
let current: EmailSettings | null = null
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

export function setEmailSettings(next: EmailSettings | null) {
  current = next
  listeners.forEach((l) => l())
}

function load() {
  if (loading) return
  const n = ++fetches
  loading = settings.email().then(
    (s) => {
      if (n === fetches) setEmailSettings(s)
    },
    () => {
      // Retried the next time something asks.
      if (n === fetches) loading = null
    },
  )
}

/** The email settings, or null until they've loaded. */
export function useEmailSettings(): EmailSettings | null {
  const value = useSyncExternalStore(subscribe, () => current)
  // Again after a reset, so screens left open across a new sign-in fetch for it.
  useEffect(() => {
    if (value === null) load()
  }, [value])
  return value
}

/** Forgets what was loaded, and any fetch on its way: another account may sign in next. */
export function resetEmailSettings() {
  current = null
  loading = null
  fetches++
  listeners.forEach((l) => l())
}
