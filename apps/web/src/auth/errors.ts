import { ApiError, NetworkError } from '../api/client'
import { KeyringChangedError, MalformedKeyError, UnsupportedKdfError, UnsupportedLockError } from '../locks/crypto'

/** Copy for errors any auth form can hit that aren't specific to it. */
export function genericMessage(err: unknown): string {
  if (
    err instanceof KeyringChangedError ||
    err instanceof UnsupportedKdfError ||
    err instanceof MalformedKeyError ||
    err instanceof UnsupportedLockError
  ) {
    return err.message
  }
  if (err instanceof NetworkError) return "Can't reach the server. Check that it's running, then try again."
  if (err instanceof ApiError && err.status === 429) {
    return `Too many attempts. Try again in ${err.retryAfter ?? 60} seconds.`
  }
  return 'Something went wrong on the server. Try again.'
}

/** A server error's message as a sentence, e.g. under a field. */
export function sentence(message: string): string {
  const s = message.charAt(0).toUpperCase() + message.slice(1)
  return s.endsWith('.') ? s : `${s}.`
}

/** A new password and its confirmation, checked as every form checks them before the server does. */
export function newPasswordErrors(password: string, confirm: string): { password?: string; confirm?: string } {
  if (password.length < 8) return { password: 'Use at least 8 characters.' }
  if (confirm !== password) return { confirm: "The passwords don't match." }
  return {}
}
