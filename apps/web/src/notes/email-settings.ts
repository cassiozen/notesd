import { useEffect, useSyncExternalStore } from 'react'
import { settings } from '../api/settings'
import type { EmailSettings } from '../api/types'

// One copy for the share menu and Settings, fetched once per account and
// replaced when Settings saves.
let current: EmailSettings | null = null
let failed = false
let loading: Promise<void> | null = null
/** Moves on with each forget: an answer to a fetch made before it is for an account that's gone. */
let fetches = 0
const listeners = new Set<() => void>()

const notify = () => listeners.forEach((l) => l())

export function setEmailSettings(next: EmailSettings | null) {
  current = next
  failed = false
  notify()
}

function load() {
  const asked = fetches
  loading ??= settings.email().then(
    (s) => {
      if (asked === fetches) setEmailSettings(s)
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

/** The email settings, or null until they've loaded. */
export function useEmailSettings(): EmailSettings | null {
  const value = useSyncExternalStore(subscribe, () => current)
  useEffect(load, [])
  return value
}

/** Whether the last fetch failed, with nothing loaded; retryEmailSettings asks again. */
export function useEmailSettingsFailed(): boolean {
  return useSyncExternalStore(subscribe, () => failed && current === null)
}

export function retryEmailSettings() {
  failed = false
  notify()
  load()
}

/** Drops what was loaded, and any fetch on its way: the next account sees its own (admins can change them, others can't). */
export function forgetEmailSettings() {
  current = null
  failed = false
  loading = null
  fetches++
  notify()
}
