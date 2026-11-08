const KEY = 'notes.signed-in'

/**
 * The default space's theme (notes/space-theme.ts), for public/boot.js's first frame. Part of the
 * hint: it belongs to the account signed in, so it goes when the session does.
 */
export const SPACE_THEME_KEY = 'notes.space-theme'

// Only says the last visit ended signed in, so boot can draw the shell before
// the server confirms. Not a credential.
export function hadSession(): boolean {
  try {
    return localStorage.getItem(KEY) === 'true'
  } catch {
    return false
  }
}

export function rememberSession(signedIn: boolean) {
  try {
    if (signedIn) localStorage.setItem(KEY, 'true')
    else {
      localStorage.removeItem(KEY)
      localStorage.removeItem(SPACE_THEME_KEY)
    }
  } catch {
    // Storage blocked: boot just shows nothing until the server answers.
  }
}

const DEVICE_KEY = 'notes.on-device'
// Apart from DEVICE_KEY's absence, which is also "never chose": the installed app keeps notes unless told not to.
const DECLINED_KEY = 'notes.on-device-declined'

/** The user whose notes this browser keeps a copy of, if any. */
export function deviceUser(): string | null {
  try {
    return localStorage.getItem(DEVICE_KEY)
  } catch {
    return null
  }
}

export function keepOnDevice(username: string | null) {
  try {
    if (username) {
      localStorage.setItem(DEVICE_KEY, username)
      localStorage.removeItem(DECLINED_KEY)
    } else localStorage.removeItem(DEVICE_KEY)
  } catch {
    // Storage blocked: notes stay on the server only.
  }
}

/** Whether keeping notes here was turned off, or left unticked where it starts ticked. Signing out doesn't count. */
export function deviceDeclined(): boolean {
  try {
    return localStorage.getItem(DECLINED_KEY) === 'true'
  } catch {
    return false
  }
}

export function declineDevice() {
  try {
    localStorage.setItem(DECLINED_KEY, 'true')
  } catch {
    // Storage blocked: nothing is kept anyway.
  }
}

const USER_ID_KEY = 'notes.user-id'

/** The account's id from its last session, for telling its edits from others' (§7.9) while offline. */
export function userIdOf(username: string): number | undefined {
  try {
    const saved = JSON.parse(localStorage.getItem(USER_ID_KEY) ?? 'null') as { username?: string; id?: number } | null
    return saved?.username === username && typeof saved.id === 'number' ? saved.id : undefined
  } catch {
    return undefined
  }
}

/** Signed out, the device's copy goes too: nothing needs the id offline. */
export function forgetUserId() {
  try {
    localStorage.removeItem(USER_ID_KEY)
  } catch {
    // Storage blocked: nothing was kept anyway.
  }
}

export function rememberUserId(username: string, id: number | undefined) {
  try {
    if (id === undefined) localStorage.removeItem(USER_ID_KEY)
    else localStorage.setItem(USER_ID_KEY, JSON.stringify({ username, id }))
  } catch {
    // Offline, this account's own edits may show as someone's until the server answers.
  }
}
