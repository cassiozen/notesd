// Passkeys that unlock locked notes (§10.1), through WebAuthn's PRF
// extension: the authenticator turns a salt into a secret only it can make,
// after the user verifies (fingerprint, face, device PIN). The server never
// checks an assertion; the secret unwraps the key or it doesn't.
import type { LockKey } from '../api/types'
import { inDesktopApp } from '../desktop'
import { LockedError, fromBase64Url, randomBytes, toBase64Url } from './crypto'

/** The passkey was dismissed, or this browser or authenticator can't give a PRF secret. */
export class PasskeyError extends Error {
  constructor(
    message: string,
    readonly reason: 'cancelled' | 'unsupported',
  ) {
    super(message)
    this.name = 'PasskeyError'
  }
}

/**
 * WebAuthn exists here; whether an authenticator supports PRF is only known once one is used. The macOS app's
 * WebKit has the API but refuses every call: only browsers Apple entitles may use passkeys.
 */
export function passkeysAvailable(): boolean {
  if (typeof window === 'undefined' || inDesktopApp()) return false
  return typeof window.PublicKeyCredential === 'function' && !!navigator.credentials
}

type PrfOutputs = { enabled?: boolean; results?: { first?: BufferSource } }
type PrfExtensions = AuthenticationExtensionsClientInputs & {
  prf?: { eval?: { first: BufferSource }; evalByCredential?: Record<string, { first: BufferSource }> }
}

function prfOf(cred: PublicKeyCredential): PrfOutputs | undefined {
  return (cred.getClientExtensionResults() as { prf?: PrfOutputs }).prf
}

function bytes(b: BufferSource): Uint8Array<ArrayBuffer> {
  const view = b instanceof ArrayBuffer ? new Uint8Array(b) : new Uint8Array(b.buffer, b.byteOffset, b.byteLength)
  return new Uint8Array(view)
}

/** navigator.credentials, with a dismissed prompt (NotAllowedError) as PasskeyError. */
async function call<T>(fn: () => Promise<Credential | null>): Promise<T> {
  try {
    const cred = await fn()
    if (!cred) throw new PasskeyError('No passkey was chosen.', 'cancelled')
    return cred as T
  } catch (err) {
    if (err instanceof PasskeyError) throw err
    if (err instanceof DOMException && (err.name === 'NotAllowedError' || err.name === 'AbortError')) {
      throw new PasskeyError('The passkey prompt was dismissed.', 'cancelled')
    }
    throw err
  }
}

/** A new passkey and its PRF secret for [salt]. */
export async function createPasskey(username: string, salt: Uint8Array<ArrayBuffer>): Promise<{ credentialId: string; secret: Uint8Array<ArrayBuffer> }> {
  const extensions: PrfExtensions = { prf: { eval: { first: salt } } }
  const cred = await call<PublicKeyCredential>(() =>
    navigator.credentials.create({
      publicKey: {
        rp: { name: 'notesd' },
        user: { id: randomBytes(16), name: username, displayName: `${username} (locked notes)` },
        challenge: randomBytes(32),
        pubKeyCredParams: [
          { type: 'public-key', alg: -7 },
          { type: 'public-key', alg: -257 },
        ],
        authenticatorSelection: { residentKey: 'preferred', userVerification: 'required' },
        extensions,
      },
    }),
  )
  const credentialId = toBase64Url(new Uint8Array(cred.rawId))
  const prf = prfOf(cred)
  if (!prf?.enabled && !prf?.results?.first) {
    throw new PasskeyError('This passkey can’t unlock notes: it doesn’t support the PRF extension.', 'unsupported')
  }
  // Some authenticators evaluate PRF only on sign-in, not at creation.
  const first = prf.results?.first
  if (first) return { credentialId, secret: bytes(first) }
  const { secret } = await signInWithPasskey([{ credentialId, salt: toBase64Url(salt) }])
  return { credentialId, secret }
}

/** Signs in with one of [keys]' passkeys; its id and PRF secret. */
export async function signInWithPasskey(
  keys: readonly Pick<LockKey, 'credentialId' | 'salt'>[],
): Promise<{ credentialId: string; secret: Uint8Array<ArrayBuffer> }> {
  const known = keys.filter((k): k is { credentialId: string; salt: string } => !!k.credentialId)
  if (known.length === 0) throw new LockedError('No passkeys can unlock notes')
  const extensions: PrfExtensions = {
    prf: { evalByCredential: Object.fromEntries(known.map((k) => [k.credentialId, { first: fromBase64Url(k.salt) }])) },
  }
  const cred = await call<PublicKeyCredential>(() =>
    navigator.credentials.get({
      publicKey: {
        challenge: randomBytes(32),
        allowCredentials: known.map((k) => ({ type: 'public-key', id: fromBase64Url(k.credentialId) })),
        userVerification: 'required',
        extensions,
      },
    }),
  )
  const first = prfOf(cred)?.results?.first
  if (!first) throw new PasskeyError('This passkey didn’t give a key for locked notes.', 'unsupported')
  return { credentialId: toBase64Url(new Uint8Array(cred.rawId)), secret: bytes(first) }
}
