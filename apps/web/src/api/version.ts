// Which notesd this page is, and what the server said about it (spec §C).

/** This build's version, without the "v". */
export const APP_VERSION = __NOTESD_VERSION__

/** X-Notesd-Client: how the server tells which app (and how old) is asking. */
export const CLIENT_ID = `web/${APP_VERSION}`

// Unreleased builds on either side: comparing them would only cry wolf in development.
const UNVERSIONED = new Set(['', 'dev', '0.0.0-dev'])

/** Whether X-Notesd-Server names another build than this page: the server was updated under it. */
export function serverDiffers(server: string | null, app = APP_VERSION): boolean {
  if (server === null) return false
  const s = server.trim().replace(/^v/, '')
  return !UNVERSIONED.has(s) && !UNVERSIONED.has(app) && s !== app
}

type Listener = () => void

/** Server facts that only a reload (or an update) changes; never cleared once seen. */
export type VersionState = {
  /** The server runs another build than this page: reloading gets the new one. */
  reload: boolean
  /** The server refused this app as too old (426). minVersion null when it didn't say. */
  upgrade: { minVersion: string | null } | null
}

let state: VersionState = { reload: false, upgrade: null }
const listeners = new Set<Listener>()

function set(next: VersionState) {
  state = next
  for (const l of listeners) l()
}

export const versionState = {
  get: (): VersionState => state,
  subscribe(listener: Listener): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },
  /** An API response's X-Notesd-Server. */
  sawServer(server: string | null) {
    if (!state.reload && serverDiffers(server)) set({ ...state, reload: true })
  },
  upgradeRequired(minVersion: string | null) {
    if (state.upgrade?.minVersion !== minVersion) set({ ...state, upgrade: { minVersion } })
  },
  /** Tests only: a fresh page. */
  reset() {
    set({ reload: false, upgrade: null })
  },
}
