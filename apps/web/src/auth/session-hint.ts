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
