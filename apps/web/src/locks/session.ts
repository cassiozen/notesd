import { useSyncExternalStore } from 'react'
import { locks as api } from '../api/locks'
import type { LockKey } from '../api/types'
import {
  KeyringChangedError,
  LockedError,
  kdfCost,
  importKey,
  passkeyKek,
  passwordKek,
  randomBytes,
  toBase64Url,
  fromBase64Url,
  unwrapRaw,
  verifier as verifierOf,
  wrap,
} from './crypto'
import { notesSource } from '../notes/source'
import { createPasskey, signInWithPasskey } from './passkey'

/**
 * - unknown: the keyring hasn't been fetched
 * - none: no lock password yet (nothing has ever been locked)
 * - locked: locked notes exist but their key isn't in memory
 * - unlocked: the key is in memory; locked notes open without asking
 */
export type LockStatus = 'unknown' | 'none' | 'locked' | 'unlocked'

// Tests shorten these; nothing else should touch them.
export const lockTiming = { idleMs: 5 * 60_000, hiddenMs: 5 * 60_000 }

type BeforeLock = () => Promise<void>

/**
 * The locked-notes key for this page (§10.1). It lives only in memory, as a
 * non-extractable CryptoKey, and is dropped after a while idle or hidden, on
 * sign-out, or when the user locks. Independent of React, like NoteSaver,
 * since savers encrypt with it.
 */
class LockSession {
  private status: LockStatus = 'unknown'
  private cryptoKey: CryptoKey | null = null
  /** Proves to the server that we hold the key, for keyring changes. */
  private proof: string | null = null
  private keyring: LockKey[] = []
  private loading: Promise<void> | null = null
  /** The password entry's wrap when the key was adopted: another one means a reset or a new password elsewhere. */
  private boundTo: string | null = null
  /**
   * Generations that make slow work give up instead of undoing what came
   * after it: [epoch] moves on every lock and sign-out (no key may be adopted
   * by work started before), [account] on sign-out (nothing fetched for the
   * old session may land), [loadSeq] on every fetch or keyring change here
   * (an older response mustn't replace a newer keyring).
   */
  private epoch = 0
  private account = 0
  private loadSeq = 0
  private listeners = new Set<() => void>()
  private beforeLock = new Set<BeforeLock>()
  private idleTimer: ReturnType<typeof setTimeout> | undefined
  private hiddenTimer: ReturnType<typeof setTimeout> | undefined

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  getStatus = (): LockStatus => this.status

  /** The key, while unlocked. */
  key(): CryptoKey | null {
    return this.cryptoKey
  }

  keys(): readonly LockKey[] {
    return this.keyring
  }

  /** Runs before the key is dropped, e.g. to save edits to locked notes while they can still be encrypted. */
  onBeforeLock(fn: BeforeLock): () => void {
    this.beforeLock.add(fn)
    return () => this.beforeLock.delete(fn)
  }

  /** Fetches the keyring (once, unless forced). */
  load(force = false): Promise<void> {
    if (this.loading && !force) return this.loading
    const seq = ++this.loadSeq
    const account = this.account
    const p = notesSource().keyring.load().then(
      (keys) => {
        if (seq !== this.loadSeq || account !== this.account) return
        this.keyring = keys
        if (this.status === 'unlocked' && this.passwordWrap() !== this.boundTo) {
          // Reset, or a new password made elsewhere: this key may no longer be the
          // account's, and nothing new may be locked with it.
          return this.lock()
        }
        // Always notifies: the keyring itself may have changed (a passkey added elsewhere).
        this.set(this.status === 'unlocked' ? 'unlocked' : this.passwordWrap() ? 'locked' : 'none')
      },
      (err: unknown) => {
        if (this.loading === p) this.loading = null
        throw err
      },
    )
    this.loading = p
    return p
  }

  /** First use: a new locked-notes key, wrapped under the lock password. */
  async setup(password: string): Promise<void> {
    const at = this.at()
    const from = this.passwordWrap()
    const raw = randomBytes(32)
    const salt = randomBytes(16)
    const kek = await passwordKek(password, salt, kdfCost.password)
    const proof = await verifierOf(raw)
    const key = await api.put('password', {
      kind: 'password',
      salt: toBase64Url(salt),
      iterations: kdfCost.password,
      wrapped: await wrap(kek, raw, 'password'),
      verifier: proof,
    })
    if (!this.changed(at, () => [key], { from, ours: key.wrapped })) return
    if (!(await this.adopt(raw, proof, at, key.wrapped))) throw new KeyringChangedError()
  }

  /** Asks for one of the keyring's passkeys. Throws PasskeyError when dismissed or unsupported. */
  async unlockWithPasskey(): Promise<void> {
    const at = this.at()
    await this.load()
    const passkeys = this.keyring.filter((k) => k.kind === 'passkey')
    const from = this.passwordWrap()
    const { credentialId, secret } = await signInWithPasskey(passkeys)
    const entry = passkeys.find((k) => k.credentialId === credentialId)
    if (!entry) throw new LockedError('That passkey can’t unlock notes')
    const raw = await unwrapRaw(await passkeyKek(secret, fromBase64Url(entry.salt)), entry.wrapped, 'passkey')
    // Reset meanwhile, this passkey's key may be the forgotten one.
    if (!(await this.adopt(raw, await verifierOf(raw), at, from))) throw new LockedError('That passkey can’t unlock notes')
  }

  /**
   * A new passkey that unlocks locked notes. The lock password unwraps the
   * key to wrap it again for the passkey (the session's copy can't be read
   * back out). Throws LockedError for a wrong password, PasskeyError when
   * the passkey can't be made or can't give a PRF secret.
   */
  async addPasskey(password: string, username: string): Promise<void> {
    const at = this.at()
    await this.load(true)
    const from = this.passwordWrap()
    const raw = await this.unwrapWithPassword(password)
    const salt = randomBytes(32)
    const { credentialId, secret } = await createPasskey(username, salt)
    const proof = await verifierOf(raw)
    const key = await api.put(toBase64Url(randomBytes(12)), {
      kind: 'passkey',
      credentialId,
      salt: toBase64Url(salt),
      iterations: 0,
      wrapped: await wrap(await passkeyKek(secret, salt), raw, 'passkey'),
      verifier: proof,
    })
    if (!this.changed(at, (keys) => [...keys, key], { from, ours: from })) return
    await this.adopt(raw, proof, at, from)
  }

  /** Stops a passkey unlocking notes. Needs the key in memory (its verifier). */
  async removePasskey(id: string): Promise<void> {
    if (!this.proof) throw new LockedError('Unlock locked notes first')
    const at = this.at()
    await api.remove(id, this.proof)
    // Right on any keyring, however new.
    if (this.changed(at, (keys) => keys.filter((k) => k.id !== id))) this.set(this.status)
  }

  getKeys = (): readonly LockKey[] => this.keyring

  /** Throws LockedError when the password is wrong. */
  async unlock(password: string): Promise<void> {
    const at = this.at()
    await this.load()
    const from = this.passwordWrap()
    try {
      if (await this.unlockWith(password, at)) return
    } catch (err) {
      if (!(err instanceof LockedError) || at.account !== this.account) throw err
      // The password may have changed on another device since the keyring was fetched.
      await this.load(true)
      if (this.passwordWrap() === from) throw err
    }
    // Once more, against the keyring that landed meanwhile (a new password, or a reset).
    if (!(await this.unlockWith(password, at))) throw new KeyringChangedError()
  }

  /** Unwraps the key with [password] and adopts it; false if another keyring landed meanwhile. */
  private async unlockWith(password: string, at: { epoch: number }): Promise<boolean> {
    const from = this.passwordWrap()
    const raw = await this.unwrapWithPassword(password)
    return this.adopt(raw, await verifierOf(raw), at, from)
  }

  /** Re-wraps the same key under a new password: no note changes. */
  async changePassword(current: string, next: string): Promise<void> {
    const at = this.at()
    await this.load(true)
    const from = this.passwordWrap()
    const raw = await this.unwrapWithPassword(current)
    const salt = randomBytes(16)
    const kek = await passwordKek(next, salt, kdfCost.password)
    const proof = await verifierOf(raw)
    const key = await api.put('password', {
      kind: 'password',
      salt: toBase64Url(salt),
      iterations: kdfCost.password,
      wrapped: await wrap(kek, raw, 'password'),
      verifier: proof,
    })
    if (!this.changed(at, (keys) => [key, ...keys.filter((k) => k.id !== 'password')], { from, ours: key.wrapped })) return
    if (!(await this.adopt(raw, proof, at, key.wrapped))) throw new KeyringChangedError()
  }

  /** Forgets the keyring for good, after a forgotten lock password. */
  async reset(accountPassword: string): Promise<void> {
    const at = this.at()
    await api.reset(accountPassword)
    // The server's keyring is empty now, whatever was fetched meanwhile.
    if (!this.changed(at, () => [])) return
    this.epoch++
    this.drop()
    this.set('none')
  }

  /** Saves what locked notes have pending, then drops the key. */
  async lock(): Promise<void> {
    // Before anything else: unlocks and keyring changes still running mustn't bring the key back.
    this.epoch++
    if (this.status !== 'unlocked') return
    await Promise.all([...this.beforeLock].map((fn) => fn().catch(() => undefined)))
    this.drop()
    this.set(this.keyring.some((k) => k.kind === 'password') ? 'locked' : 'none')
  }

  /** Signed out: drop everything without saving (the session is gone). */
  forget() {
    this.epoch++
    this.account++
    this.loadSeq++
    this.drop()
    this.keyring = []
    this.loading = null
    this.set('unknown')
  }

  /** The verifier, while unlocked: keyring changes need it. */
  verifier(): string | null {
    return this.proof
  }

  private async unwrapWithPassword(password: string): Promise<Uint8Array<ArrayBuffer>> {
    const entry = this.keyring.find((k) => k.kind === 'password')
    if (!entry) throw new LockedError('No lock password set')
    const kek = await passwordKek(password, fromBase64Url(entry.salt), entry.iterations)
    return unwrapRaw(kek, entry.wrapped, 'password')
  }

  private passwordWrap(): string | null {
    return this.keyring.find((k) => k.kind === 'password')?.wrapped ?? null
  }

  /** Where slow work started, to tell whether a lock, sign-out or newer keyring came since. */
  private at() {
    return { epoch: this.epoch, account: this.account }
  }

  /**
   * Installs a keyring change made here. False, quietly, if the account
   * signed out meanwhile. A change made against the password entry [from]
   * is stale if a keyring fetched since has another one (another device
   * changed or reset it), unless that's [ours], this change's own: then it's
   * dropped, the keyring fetched again, and KeyringChangedError thrown.
   */
  private changed(
    at: { account: number },
    next: (keys: LockKey[]) => LockKey[],
    basis?: { from: string | null; ours: string | null },
  ): boolean {
    if (at.account !== this.account) return false
    const now = this.passwordWrap()
    if (basis && now !== basis.from && now !== basis.ours) {
      void this.load(true).catch(() => undefined)
      throw new KeyringChangedError()
    }
    this.loadSeq++
    this.keyring = next(this.keyring)
    void notesSource()
      .keyring.changed(this.keyring)
      .catch(() => undefined)
    return true
  }

  /**
   * Takes the key into memory, unless notes were locked (or the account
   * signed out) since [at]: then it's dropped quietly. [from] is the password
   * entry the keyring had when the key was unwrapped; if another has landed
   * since (a reset, a new password), the key may be a forgotten one, so it's
   * dropped and false comes back.
   */
  private async adopt(
    raw: Uint8Array<ArrayBuffer>,
    proof: string,
    at: { epoch: number },
    from: string | null,
  ): Promise<boolean> {
    if (at.epoch !== this.epoch) {
      raw.fill(0)
      return true
    }
    const key = await importKey(raw)
    raw.fill(0)
    if (at.epoch !== this.epoch) return true
    if (this.passwordWrap() !== from) return false
    this.cryptoKey = key
    this.boundTo = this.passwordWrap()
    this.proof = proof
    this.armTimers()
    this.set('unlocked')
    return true
  }

  private drop() {
    this.cryptoKey = null
    this.proof = null
    this.boundTo = null
    this.disarmTimers()
  }

  // --- locking again by itself ------------------------------------------------

  private onActivity = () => {
    clearTimeout(this.idleTimer)
    this.idleTimer = setTimeout(() => void this.lock(), lockTiming.idleMs)
  }

  private onVisibility = () => {
    clearTimeout(this.hiddenTimer)
    if (document.visibilityState === 'hidden') this.hiddenTimer = setTimeout(() => void this.lock(), lockTiming.hiddenMs)
  }

  private armTimers() {
    this.disarmTimers()
    document.addEventListener('keydown', this.onActivity, true)
    document.addEventListener('pointerdown', this.onActivity, true)
    // Reading a long note is activity too. Scroll doesn't bubble: capture catches the pane's.
    document.addEventListener('wheel', this.onActivity, { capture: true, passive: true })
    document.addEventListener('scroll', this.onActivity, { capture: true, passive: true })
    document.addEventListener('visibilitychange', this.onVisibility)
    this.onActivity()
    this.onVisibility()
  }

  private disarmTimers() {
    clearTimeout(this.idleTimer)
    clearTimeout(this.hiddenTimer)
    document.removeEventListener('keydown', this.onActivity, true)
    document.removeEventListener('pointerdown', this.onActivity, true)
    document.removeEventListener('wheel', this.onActivity, true)
    document.removeEventListener('scroll', this.onActivity, true)
    document.removeEventListener('visibilitychange', this.onVisibility)
  }

  private set(status: LockStatus) {
    this.status = status
    for (const l of this.listeners) l()
  }
}

export const lockSession = new LockSession()

export function useLockStatus(): LockStatus {
  return useSyncExternalStore(lockSession.subscribe, lockSession.getStatus)
}

/** The keyring as last fetched or changed here. */
export function useLockKeys(): readonly LockKey[] {
  return useSyncExternalStore(lockSession.subscribe, lockSession.getKeys)
}
