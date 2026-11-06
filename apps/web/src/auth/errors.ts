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

/** A server message as a sentence: capitalised, with a full stop. */
export function sentence(message: string): string {
  const s = message.charAt(0).toUpperCase() + message.slice(1)
  return s.endsWith('.') ? s : `${s}.`
}
