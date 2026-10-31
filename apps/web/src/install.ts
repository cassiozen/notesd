import { useSyncExternalStore } from 'react'

/** Chromium only; not in lib.dom. */
interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>
  readonly userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

/** The manifest's display; matches only in a window opened as the installed app. */
export const STANDALONE = '(display-mode: standalone)'

export function isInstalledApp(): boolean {
  return window.matchMedia(STANDALONE).matches
}

let deferred: BeforeInstallPromptEvent | null = null
let captured = false
const listeners = new Set<() => void>()

function set(event: BeforeInstallPromptEvent | null) {
  deferred = event
  for (const l of listeners) l()
}

/**
 * Before React mounts: Chrome may fire beforeinstallprompt as soon as the page loads. Fires only when the
 * app can be installed, so not in Safari or Firefox, not once installed or opened as one, not over plain http.
 */
export function captureInstallPrompt() {
  if (captured) return
  captured = true
  window.addEventListener('beforeinstallprompt', (e) => {
    const event = e as BeforeInstallPromptEvent
    set(event)
    // Answered in Chrome's own offer as well as ours: either way this event can't prompt again.
    event.userChoice.then(
      () => {
        if (deferred === event) set(null)
      },
      () => {},
    )
  })
  window.addEventListener('appinstalled', () => set(null))
}

async function install() {
  const event = deferred
  if (!event) return
  // prompt() works once per event; dismissed, Chrome fires a fresh one on a later visit.
  set(null)
  try {
    await event.prompt()
  } catch {
    // Refused (another prompt showing, say); Chrome's own offer is still there.
  }
}

/** Opens Chrome's install dialog; null while the app can't be installed. */
export function useInstallPrompt(): (() => Promise<void>) | null {
  const can = useSyncExternalStore(
    (onChange) => {
      listeners.add(onChange)
      return () => listeners.delete(onChange)
    },
    () => deferred !== null,
  )
  return can ? install : null
}
