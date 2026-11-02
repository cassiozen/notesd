import { describe, expect, it } from 'vitest'
import fixtures from '../../../../shared/crypto-fixtures/locked.json'
import { LOCKED_IMAGE, isLockedImageType } from '../api/images'
import type { Kdf } from '../api/types'
import {
  LockedError,
  MalformedKeyError,
  UnsupportedKdfError,
  UnsupportedLockError,
  decryptBody,
  encryptBody,
  entryKdf,
  fromBase64Url,
  importKey,
  isLocked,
  isSupportedLock,
  lockVersion,
  newRecoveryCode,
  normalizeRecoveryCode,
  openImage,
  passkeyKek,
  passwordKek,
  sealImage,
  toBase64Url,
  unwrapRaw,
  verifier,
  wrap,
  type WrapKind,
} from './crypto'

const exportable = async (raw: string) => importKey(fromBase64Url(raw), true)
/** The locked-notes key every wrap in the fixtures holds. */
const lnk = fixtures.wraps[0]?.key ?? ''

describe('locked notes crypto (shared fixtures)', () => {
  // 600k iterations is slow under jsdom; the Kotlin suite checks that vector.
  for (const v of fixtures.kdf.filter((k) => k.iterations < 100_000)) {
    it(`kdf: ${v.name}`, async () => {
      const kek = await passwordKek(v.password, fromBase64Url(v.salt), v.iterations)
      // The kek isn't extractable: prove it equals the vector by unwrapping with both.
      const wrapped = await wrap(kek, fromBase64Url(lnk), 'password')
      const viaVector = await unwrapRaw(await importKey(fromBase64Url(v.key)), wrapped, 'password')
      expect(toBase64Url(viaVector)).toBe(lnk)
    })
  }

  for (const v of fixtures.hkdf) {
    it(`hkdf: ${v.name}`, async () => {
      const kek = await passkeyKek(fromBase64Url(v.prf), fromBase64Url(v.salt))
      const wrapped = await wrap(kek, fromBase64Url(lnk), 'passkey')
      const viaVector = await unwrapRaw(await importKey(fromBase64Url(v.key)), wrapped, 'passkey')
      expect(toBase64Url(viaVector)).toBe(lnk)
    })
  }

  for (const v of fixtures.wraps) {
    it(`unwrap: ${v.name}`, async () => {
      const kek = await importKey(fromBase64Url(v.kek))
      const raw = await unwrapRaw(kek, v.wrapped, v.kind as WrapKind)
      expect(toBase64Url(raw)).toBe(v.key)
      const other: WrapKind = v.kind === 'password' ? 'recovery' : 'password'
      await expect(unwrapRaw(kek, v.wrapped, other)).rejects.toBeInstanceOf(LockedError)
    })
  }

  for (const v of fixtures.envelopes) {
    it(`envelope: ${v.name}`, async () => {
      const key = await exportable(v.key)
      expect(isLocked(v.envelope)).toBe(true)
      expect(lockVersion(v.envelope)).toBe('v1')
      expect(isSupportedLock(v.envelope)).toBe(true)
      expect(await decryptBody(key, v.envelope)).toBe(v.plaintext)
      const again = await encryptBody(key, v.plaintext)
      expect(again).not.toBe(v.envelope) // fresh nonce every time
      expect(await decryptBody(key, again)).toBe(v.plaintext)
    })
  }

  for (const v of fixtures.invalid) {
    it(`refuses: ${v.name}`, async () => {
      await expect(decryptBody(await exportable(v.key), v.envelope)).rejects.toBeInstanceOf(LockedError)
    })
  }

  for (const v of fixtures.unsupported) {
    it(`unsupported: ${v.name}`, async () => {
      // Locked, so never shown or indexed as plain text, but not openable by any key here.
      expect(isLocked(v.envelope)).toBe(true)
      expect(lockVersion(v.envelope)).toBe(v.version)
      expect(isSupportedLock(v.envelope)).toBe(false)
      await expect(decryptBody(await exportable(lnk), v.envelope)).rejects.toBeInstanceOf(UnsupportedLockError)
    })
  }

  for (const v of fixtures.imageTypes) {
    it(`image type: ${v.type}`, () => {
      expect(isLockedImageType(v.type)).toBe(v.locked)
      // Supported locked images are the one type this release seals and opens.
      if (v.locked) expect(v.type === LOCKED_IMAGE).toBe(v.supported)
    })
  }

  for (const v of fixtures.keyring) {
    it(`keyring: ${v.name}`, () => {
      const entry = {
        kind: v.kind as WrapKind,
        ...('iterations' in v && { iterations: v.iterations }),
        kdf: v.kdf as Kdf | null,
      }
      const verdict = (() => {
        try {
          entryKdf(entry)
          return 'ok'
        } catch (err) {
          if (err instanceof UnsupportedKdfError) return 'unsupported'
          if (err instanceof MalformedKeyError) return 'invalid'
          throw err
        }
      })()
      expect(verdict).toBe(v.verdict)
    })
  }

  for (const v of fixtures.images) {
    it(`image: ${v.name}`, async () => {
      const key = await exportable(v.key)
      const sealed = fromBase64Url(v.sealed)
      if (v.plain === null) {
        await expect(openImage(key, sealed)).rejects.toBeInstanceOf(LockedError)
        return
      }
      expect(toBase64Url(await openImage(key, sealed))).toBe(v.plain)
      const again = await sealImage(key, fromBase64Url(v.plain))
      expect(toBase64Url(again)).not.toBe(v.sealed)
      expect(toBase64Url(await openImage(key, again))).toBe(v.plain)
    })
  }
})

describe('verifier (shared fixtures)', () => {
  for (const v of fixtures.verifiers) {
    it(v.name, async () => {
      expect(await verifier(fromBase64Url(v.key))).toBe(v.verifier)
    })
  }
})

describe('recovery codes', () => {
  it('are 24 unambiguous characters in groups of three', () => {
    const code = newRecoveryCode()
    expect(code).toMatch(/^([2-9A-HJ-NP-Z]{3}-){7}[2-9A-HJ-NP-Z]{3}$/)
    expect(normalizeRecoveryCode(code.toLowerCase().replace(/-/g, ' '))).toBe(code.replace(/-/g, ''))
  })
})

describe('bodies', () => {
  it('a leading byte order mark survives the round trip', async () => {
    const key = await importKey(fromBase64Url(lnk))
    expect(await decryptBody(key, await encryptBody(key, '﻿secret\n'))).toBe('﻿secret\n')
  })
})

describe('lock versions', () => {
  it('reads the version up to the next colon, and only v<n> as one', () => {
    expect(lockVersion('notesd:locked:v12:abc')).toBe('v12')
    expect(lockVersion('notesd:locked:v0:abc')).toBeNull()
    expect(lockVersion('notesd:locked:v1')).toBeNull()
    expect(lockVersion('plain text')).toBeNull()
    expect(isLocked('notesd:locked:')).toBe(true)
  })
})

describe('keyring kdf', () => {
  it('uses an entry’s own iterations, or the legacy ones its kind implies', () => {
    expect(entryKdf({ kind: 'password', iterations: 600_000, kdf: null })).toEqual({ name: 'pbkdf2-sha256', iterations: 600_000 })
    expect(entryKdf({ kind: 'recovery', iterations: 1, kdf: { name: 'pbkdf2-sha256', iterations: 20_000 } })).toEqual({
      name: 'pbkdf2-sha256',
      iterations: 20_000,
    })
    expect(entryKdf({ kind: 'passkey', iterations: 0 })).toEqual({ name: 'hkdf-sha256' })
  })

  it('treats a legacy entry without iterations as malformed, not as a wrong password', () => {
    expect(() => entryKdf({ kind: 'password', iterations: 0, kdf: null })).toThrow(MalformedKeyError)
    expect(() => entryKdf({ kind: 'password', kdf: { name: 'pbkdf2-sha256', iterations: -5 } })).toThrow(MalformedKeyError)
  })
})
