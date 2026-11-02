// Locked notes crypto (architecture §10.1), pinned by
// shared/crypto-fixtures/locked.json. The Android core implements the same
// rules in notes.core.crypto.LockedNotes; keep the two in step.

import type { Kdf } from '../api/types'

/** Every locked body, whatever its version, starts with this: the version, ':', then the payload. */
export const LOCKED_FAMILY = 'notesd:locked:'
/** The one version this release opens. */
export const LOCK_VERSION = 'v1'
/** A v1 locked note's content starts with this; the rest is base64url(nonce ‖ ciphertext ‖ tag). */
export const LOCKED_PREFIX = `${LOCKED_FAMILY}${LOCK_VERSION}:`
const BODY_AAD = 'notesd:locked:v1'
const IMAGE_AAD = 'notesd:image:v1'
const PASSKEY_INFO = 'notesd:passkey:v1'
// PBKDF2 cost for new wraps; existing wraps carry their own. Tests lower it.
export const kdfCost = { password: 600_000, recovery: 10_000 }
const NONCE = 12
const TAG = 16

export type WrapKind = 'password' | 'recovery' | 'passkey'

/** The body failed to decrypt: wrong key, tampering, or not an envelope at all. */
/** Another device changed locked notes (a new password, a reset) while a change here was on its way. */
export class KeyringChangedError extends Error {
  constructor(message = 'Locked notes were changed on another device meanwhile. Try again.') {
    super(message)
    this.name = 'KeyringChangedError'
  }
}

export class LockedError extends Error {
  constructor(message = 'Could not open the locked note') {
    super(message)
    this.name = 'LockedError'
  }
}

/** Locked by a newer notesd, in a format this one can't open: no key or password would help. */
export class UnsupportedLockError extends Error {
  constructor(message = 'This note was locked by a newer version of notesd. Update notesd to open it.') {
    super(message)
    this.name = 'UnsupportedLockError'
  }
}

/** A keyring entry whose key derivation this notesd doesn't know: written by a newer one. */
export class UnsupportedKdfError extends Error {
  constructor(message = 'This lock was set up by a newer version of notesd. Update notesd to unlock.') {
    super(message)
    this.name = 'UnsupportedKdfError'
  }
}

/** A keyring entry that can't be right (zero iterations, a passkey's kdf on a password). */
export class MalformedKeyError extends Error {
  constructor(message = 'This lock’s settings are damaged. It can’t be unlocked here.') {
    super(message)
    this.name = 'MalformedKeyError'
  }
}

const enc = new TextEncoder()
// ignoreBOM: a body may start with U+FEFF, and it must come back as it went in.
const dec = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true })

/** Any locked body, including versions this release can't open. */
export function isLocked(content: string): boolean {
  return content.startsWith(LOCKED_FAMILY)
}

/** "v1", "v2"…: the version of a locked body; null when it isn't one, or the version can't be read. */
export function lockVersion(content: string): string | null {
  if (!isLocked(content)) return null
  const rest = content.slice(LOCKED_FAMILY.length)
  const end = rest.indexOf(':')
  const v = end < 0 ? '' : rest.slice(0, end)
  return /^v[1-9][0-9]*$/.test(v) ? v : null
}

/** Locked in a version this release can open. */
export function isSupportedLock(content: string): boolean {
  return lockVersion(content) === LOCK_VERSION
}

export function toBase64Url(bytes: Uint8Array): string {
  let s = ''
  for (const b of bytes) s += String.fromCharCode(b)
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

export function fromBase64Url(s: string): Uint8Array<ArrayBuffer> {
  if (!/^[A-Za-z0-9_-]*$/.test(s)) throw new LockedError('Not base64url')
  const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (s.length % 4)) % 4))
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

export function randomBytes(n: number): Uint8Array<ArrayBuffer> {
  return crypto.getRandomValues(new Uint8Array(n))
}

/** A 256-bit AES-GCM key. Not extractable unless asked, so page script can't read it back out. */
export function importKey(raw: Uint8Array<ArrayBuffer>, extractable = false): Promise<CryptoKey> {
  return crypto.subtle.importKey('raw', raw, { name: 'AES-GCM' }, extractable, ['encrypt', 'decrypt'])
}

async function seal(key: CryptoKey, plain: Uint8Array<ArrayBuffer>, aad: string): Promise<Uint8Array<ArrayBuffer>> {
  const nonce = randomBytes(NONCE)
  const ct = new Uint8Array(
    await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce, additionalData: enc.encode(aad) }, key, plain),
  )
  const out = new Uint8Array(NONCE + ct.length)
  out.set(nonce)
  out.set(ct, NONCE)
  return out
}

async function open(key: CryptoKey, sealed: Uint8Array<ArrayBuffer>, aad: string): Promise<Uint8Array<ArrayBuffer>> {
  if (sealed.length < NONCE + TAG) throw new LockedError('Too short')
  try {
    return new Uint8Array(
      await crypto.subtle.decrypt(
        { name: 'AES-GCM', iv: sealed.subarray(0, NONCE), additionalData: enc.encode(aad) },
        key,
        sealed.subarray(NONCE),
      ),
    )
  } catch {
    throw new LockedError()
  }
}

/** Encrypts a note's Markdown body into its stored content. */
export async function encryptBody(key: CryptoKey, markdown: string): Promise<string> {
  return LOCKED_PREFIX + toBase64Url(await seal(key, enc.encode(markdown), BODY_AAD))
}

/**
 * Decrypts stored content; throws UnsupportedLockError for a newer version's body,
 * LockedError unless it is an envelope this key sealed.
 */
export async function decryptBody(key: CryptoKey, content: string): Promise<string> {
  if (!isLocked(content)) throw new LockedError('Not a locked note')
  if (!isSupportedLock(content)) throw new UnsupportedLockError()
  const plain = await open(key, fromBase64Url(content.slice(LOCKED_PREFIX.length)), BODY_AAD)
  try {
    return dec.decode(plain)
  } catch {
    throw new LockedError('Not UTF-8')
  }
}

/** Encrypts a locked note's image file (architecture §7.6): nonce ‖ ciphertext ‖ tag, binary. */
export function sealImage(key: CryptoKey, file: Uint8Array<ArrayBuffer>): Promise<Uint8Array<ArrayBuffer>> {
  return seal(key, file, IMAGE_AAD)
}

/** Decrypts a locked note's image; throws LockedError unless this key sealed it as an image. */
export function openImage(key: CryptoKey, sealed: Uint8Array<ArrayBuffer>): Promise<Uint8Array<ArrayBuffer>> {
  return open(key, sealed, IMAGE_AAD)
}

/** How a keyring entry's kek is derived, once checked: the only ones this release knows. */
export type KnownKdf = { name: 'pbkdf2-sha256'; iterations: number } | { name: 'hkdf-sha256' }

/** The kdf written on new entries of [kind]. */
export function kdfFor(kind: WrapKind, iterations: number): KnownKdf {
  return kind === 'passkey' ? { name: 'hkdf-sha256' } : { name: 'pbkdf2-sha256', iterations }
}

/**
 * An entry's kdf: its own, or, written before entries had one, the one its kind implies.
 * Throws UnsupportedKdfError for one a newer notesd wrote, MalformedKeyError for one that can't be right.
 */
export function entryKdf(entry: { kind: WrapKind; iterations?: number; kdf?: Kdf | null }): KnownKdf {
  const kdf = entry.kdf ?? kdfFor(entry.kind, entry.iterations ?? 0)
  if (typeof kdf.name !== 'string') throw new MalformedKeyError()
  const passkey = entry.kind === 'passkey'
  switch (kdf.name) {
    case 'pbkdf2-sha256': {
      const { iterations } = kdf
      if (passkey || typeof iterations !== 'number' || !Number.isSafeInteger(iterations) || iterations <= 0) {
        throw new MalformedKeyError()
      }
      return { name: 'pbkdf2-sha256', iterations }
    }
    case 'hkdf-sha256':
      if (!passkey) throw new MalformedKeyError()
      return { name: 'hkdf-sha256' }
    default:
      throw new UnsupportedKdfError()
  }
}

/** PBKDF2-HMAC-SHA256 of the NFC-normalized password: the key that wraps the locked-notes key. */
export async function passwordKek(password: string, salt: Uint8Array<ArrayBuffer>, iterations: number): Promise<CryptoKey> {
  const base = await crypto.subtle.importKey('raw', enc.encode(password.normalize('NFC')), 'PBKDF2', false, [
    'deriveKey',
  ])
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', hash: 'SHA-256', salt, iterations },
    base,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  )
}

/** HKDF-SHA256 of a passkey's PRF output: the key that wraps the locked-notes key. */
export async function passkeyKek(prf: Uint8Array<ArrayBuffer>, salt: Uint8Array<ArrayBuffer>): Promise<CryptoKey> {
  const base = await crypto.subtle.importKey('raw', prf, 'HKDF', false, ['deriveKey'])
  return crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt, info: enc.encode(PASSKEY_INFO) },
    base,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  )
}

/** Wraps the raw locked-notes key for storage in the keyring. */
export async function wrap(kek: CryptoKey, raw: Uint8Array<ArrayBuffer>, kind: WrapKind): Promise<string> {
  return toBase64Url(await seal(kek, raw, `notesd:lnk:v1:${kind}`))
}

/** Unwraps a keyring entry into the locked-notes key; LockedError means the password (or passkey) was wrong. */
export async function unwrap(
  kek: CryptoKey,
  wrapped: string,
  kind: WrapKind,
  extractable = false,
): Promise<CryptoKey> {
  const raw = await open(kek, fromBase64Url(wrapped), `notesd:lnk:v1:${kind}`)
  if (raw.length !== 32) throw new LockedError('Bad key length')
  return importKey(raw, extractable)
}

/** Unwraps to raw bytes, for re-wrapping under another kek (adding a passkey, changing the password). */
export async function unwrapRaw(kek: CryptoKey, wrapped: string, kind: WrapKind): Promise<Uint8Array<ArrayBuffer>> {
  const raw = await open(kek, fromBase64Url(wrapped), `notesd:lnk:v1:${kind}`)
  if (raw.length !== 32) throw new LockedError('Bad key length')
  return raw
}

/** HMAC(key, "notesd:verifier:v1"): proves to the server that this client holds the key, without revealing it. */
export async function verifier(raw: Uint8Array<ArrayBuffer>): Promise<string> {
  const mac = await crypto.subtle.importKey('raw', raw, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
  return toBase64Url(new Uint8Array(await crypto.subtle.sign('HMAC', mac, enc.encode('notesd:verifier:v1'))))
}

const RECOVERY_ALPHABET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ' // no 0/O, 1/I

/** A random 120-bit recovery code, shown once: eight groups of three, e.g. "7KQ-M2D-…". */
export function newRecoveryCode(): string {
  const chars = Array.from(randomBytes(24), (x) => RECOVERY_ALPHABET[x & 31]).join('')
  return (chars.match(/.{3}/g) ?? []).join('-')
}

/** Recovery codes are typed by hand: ignore case, spaces and dashes. */
export function normalizeRecoveryCode(code: string): string {
  return code.toUpperCase().replace(/[^0-9A-Z]/g, '')
}
