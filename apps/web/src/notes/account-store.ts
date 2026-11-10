import { useEffect, useSyncExternalStore } from 'react'

/**
 * One copy of something the server says about the signed-in account, for the
 * whole app: fetched once (again once back online after failing), replaced on save, and dropped on forget.
 */
export function accountStore<T>(fetch: () => Promise<T>) {
  let current: T | null = null
  let failed = false
  let loading: Promise<void> | null = null
  /** Moves on with each forget: an answer to a fetch made before it is for an account that's gone. */
  let fetches = 0
  const listeners = new Set<() => void>()

  const notify = () => listeners.forEach((l) => l())

  function set(next: T | null) {
    current = next
    failed = false
    notify()
  }

  function load(asked = fetches) {
    // Rendered before a forget: the render after it asks for the new session.
    if (asked !== fetches) return
    loading ??= fetch().then(
      (v) => {
        if (asked === fetches) set(v)
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

  function retry() {
    failed = false
    notify()
    load()
  }

  function subscribe(l: () => void) {
    listeners.add(l)
    return () => {
      listeners.delete(l)
    }
  }

  return {
    set,
    /** As last loaded; outside React. */
    get: (): T | null => current,
    /** Changes with each forget: a save answered after it is for an account that's gone. */
    session: () => fetches,

    /** The value, or null until it's loaded. */
    use(): T | null {
      const value = useSyncExternalStore(subscribe, () => current)
      const asked = useSyncExternalStore(subscribe, () => fetches)
      // Again after a forget, so screens left open across a new sign-in fetch for it,
      // even one still waiting on the dropped fetch (null before and after).
      useEffect(() => {
        if (value === null) load(asked)
      }, [value, asked])
      // A fetch that failed offline isn't asked again by anything else: what hangs on it would stay off.
      useEffect(() => {
        if (value !== null) return
        window.addEventListener('online', retry)
        return () => window.removeEventListener('online', retry)
      }, [value])
      return value
    },

    /** Whether the last fetch failed, with nothing loaded; retry asks again. */
    useFailed(): boolean {
      return useSyncExternalStore(subscribe, () => failed && current === null)
    },

    retry,

    /** Drops what was loaded, and any fetch on its way: the next account sees its own. */
    forget() {
      current = null
      failed = false
      loading = null
      fetches++
      notify()
    },
  }
}
