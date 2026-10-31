const KEY = 'notes.signed-in'

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
    else localStorage.removeItem(KEY)
  } catch {
    // Storage blocked: boot just shows nothing until the server answers.
  }
}

const DEVICE_KEY = 'notes.on-device'

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
    if (username) localStorage.setItem(DEVICE_KEY, username)
    else localStorage.removeItem(DEVICE_KEY)
  } catch {
    // Storage blocked: notes stay on the server only.
  }
}
