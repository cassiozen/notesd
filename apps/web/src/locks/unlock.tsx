import { useId, useState, type FormEvent } from 'react'
import { useAuth } from '../auth/auth-context'
import { genericMessage } from '../auth/errors'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { Field } from '../ui/field'
import { PasswordMeter } from '../ui/password-meter'
import { useShake } from '../ui/shake'
import { LockedError } from './crypto'
import { PasskeyError, passkeysAvailable } from './passkey'
import { lockSession, useLockKeys } from './session'
import { answerTouchIdOffer, offerTouchId, unlockWithTouchId, useTouchId, useTouchIdOffer } from './touch-id'
import styles from './locks.module.css'

/** Shortest lock password accepted; it's the only thing between the server and the notes. */
export const MIN_LOCK_PASSWORD = 8

const UNLOCK: [string, string] = ['Unlock', 'Unlocking…']
const UNLOCK_PASSKEY: [string, string] = ['Unlock with passkey', 'Waiting for the passkey…']
const UNLOCK_TOUCH_ID: [string, string] = ['Unlock with Touch ID', 'Waiting for Touch ID…']

/** Touch ID when it's on here (the macOS app), otherwise a passkey when there is one: asked for before the password. */
function useQuickUnlock(): { kind: 'touch-id' | 'passkey' | null; user: string | null } {
  const { state } = useAuth()
  const user = 'username' in state ? state.username : null
  const touchId = useTouchId(user)
  const passkey = useLockKeys().some((k) => k.kind === 'passkey') && passkeysAvailable()
  return { kind: touchId.enabled ? 'touch-id' : passkey ? 'passkey' : null, user }
}

/**
 * The way into locked notes; inline in the note pane or inside
 * LockNoteDialog. With Touch ID on or a passkey set up, that's the one
 * button, and the lock password field waits behind "Use password instead".
 * [action], [passkeyAction] and [touchIdAction] name the buttons (idle, busy).
 */
export function UnlockForm({
  onUnlocked,
  onCancel,
  autoFocus = true,
  action = UNLOCK,
  passkeyAction = UNLOCK_PASSKEY,
  touchIdAction = UNLOCK_TOUCH_ID,
}: {
  onUnlocked: () => void
  onCancel?: () => void
  autoFocus?: boolean
  action?: [string, string]
  passkeyAction?: [string, string]
  touchIdAction?: [string, string]
}) {
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [ref, shake] = useShake<HTMLFormElement>()
  const [usePassword, setUsePassword] = useState(false)
  const quick = useQuickUnlock()

  async function touchId(user: string) {
    setBusy(true)
    setError(undefined)
    try {
      const outcome = await unlockWithTouchId(user)
      if (outcome === 'done') onUnlocked()
      // Turned off now: the password form takes its place.
      else if (outcome === 'stale') setError('Touch ID was turned off: the lock password or your fingerprints changed.')
      else if (outcome === 'failed') setError('Touch ID didn’t unlock notes. Use the lock password instead.')
    } catch (err) {
      setError(genericMessage(err))
    } finally {
      setBusy(false)
    }
  }

  async function passkey() {
    setBusy(true)
    setError(undefined)
    try {
      await lockSession.unlockWithPasskey()
      onUnlocked()
    } catch (err) {
      if (err instanceof PasskeyError && err.reason === 'cancelled') return
      setError(
        err instanceof PasskeyError || err instanceof LockedError
          ? 'That passkey couldn’t unlock notes. Use the lock password instead.'
          : genericMessage(err),
      )
    } finally {
      setBusy(false)
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!password) return
    setBusy(true)
    setError(undefined)
    try {
      await lockSession.unlock(password)
      onUnlocked()
      if (quick.user) void offerTouchId(quick.user, password)
    } catch (err) {
      setError(err instanceof LockedError ? 'Wrong password.' : genericMessage(err))
      shake()
    } finally {
      setBusy(false)
    }
  }

  if (quick.kind && !usePassword) {
    const [idle, waiting] = quick.kind === 'touch-id' ? touchIdAction : passkeyAction
    const user = quick.user
    return (
      <div className={onCancel ? styles.stack : styles.inline}>
        <div className={styles.actions}>
          {onCancel && (
            <Button size="md" onClick={onCancel} disabled={busy}>
              Cancel
            </Button>
          )}
          <Button
            variant="solid"
            size="md"
            onClick={() => void (quick.kind === 'touch-id' && user ? touchId(user) : passkey())}
            busy={busy}
            grow={!onCancel}
            autoFocus={autoFocus}
          >
            {busy ? waiting : idle}
          </Button>
        </div>
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
        <button
          type="button"
          className={styles.link}
          onClick={() => {
            setError(undefined)
            setUsePassword(true)
          }}
          disabled={busy}
        >
          Use password instead
        </button>
      </div>
    )
  }

  return (
    <form ref={ref} className={onCancel ? styles.stack : styles.inline} onSubmit={(e) => void submit(e)} noValidate>
      <Field
        label="Lock password"
        type="password"
        autoComplete="current-password"
        value={password}
        onChange={(e) => {
          setPassword(e.target.value)
          setError(undefined)
        }}
        error={error}
        // Read-only, not disabled, so focus stays for another try.
        readOnly={busy}
        // Chosen over the passkey: the field is what's wanted next.
        autoFocus={autoFocus || usePassword}
      />
      <div className={styles.actions}>
        {onCancel && (
          <Button size="md" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
        )}
        <Button variant="solid" size="md" type="submit" busy={busy} grow={!onCancel}>
          {busy ? action[1] : action[0]}
        </Button>
      </div>
      {quick.kind && (
        <button
          type="button"
          className={styles.link}
          onClick={() => {
            setError(undefined)
            setUsePassword(false)
          }}
          disabled={busy}
        >
          {quick.kind === 'touch-id' ? 'Use Touch ID instead' : 'Use passkey instead'}
        </button>
      )}
    </form>
  )
}

const LOCK: [string, string] = ['Lock', 'Locking…']
const LOCK_PASSKEY: [string, string] = ['Lock with passkey', 'Waiting for the passkey…']
const LOCK_TOUCH_ID: [string, string] = ['Lock with Touch ID', 'Waiting for Touch ID…']

/**
 * Lock note, when a lock password already exists (made on any device) but
 * isn't entered yet: every locked note shares it, so it's asked for, not
 * created.
 */
export function LockNoteDialog({ onUnlocked, onClose }: { onUnlocked: () => void; onClose: () => void }) {
  const titleId = useId()
  const quick = useQuickUnlock().kind
  return (
    <Dialog labelledBy={titleId} onClose={onClose}>
      <div className={styles.stack}>
        <h2 id={titleId} className={styles.title}>
          Lock this note
        </h2>
        <p className={styles.text}>
          {quick
            ? `Locked notes are opened with ${quick === 'touch-id' ? 'Touch ID' : 'your passkey'} or the lock password. Use one to lock this note.`
            : 'Locked notes share one lock password, made the first time a note was locked on any of your devices. Enter it to lock this note.'}
        </p>
        <UnlockForm
          onUnlocked={onUnlocked}
          onCancel={onClose}
          action={LOCK}
          passkeyAction={LOCK_PASSKEY}
          touchIdAction={LOCK_TOUCH_ID}
        />
      </div>
    </Dialog>
  )
}

/** First lock: choose the password every locked note will share. */
export function SetupDialog({ onDone, onClose }: { onDone: () => void; onClose: () => void }) {
  const titleId = useId()
  const { state } = useAuth()
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<{ field: 'password' | 'confirm'; message: string }>()
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (password.length < MIN_LOCK_PASSWORD) {
      setError({ field: 'password', message: `Use at least ${MIN_LOCK_PASSWORD} characters.` })
      return
    }
    if (confirm !== password) {
      setError({ field: 'confirm', message: 'The passwords don’t match.' })
      return
    }
    setBusy(true)
    try {
      await lockSession.setup(password)
      onDone()
      if ('username' in state) void offerTouchId(state.username, password)
    } catch (err) {
      setError({ field: 'password', message: genericMessage(err) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog labelledBy={titleId} onClose={onClose}>
      <form className={styles.stack} onSubmit={(e) => void submit(e)} noValidate>
        <h2 id={titleId} className={styles.title}>
          Create a lock password
        </h2>
        <p className={styles.text}>
          Locked notes are encrypted in this browser and on your devices; the server can’t read them. Every locked note
          uses this password. If you forget it, those notes can’t be recovered.
        </p>
        <Field
          label="Lock password"
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={(e) => {
            setPassword(e.target.value)
            setError(undefined)
          }}
          error={error?.field === 'password' ? error.message : undefined}
          // What's typed while the key is derived wouldn't be the password used.
          readOnly={busy}
          autoFocus
        >
          <PasswordMeter password={password} />
        </Field>
        <Field
          label="Confirm"
          type="password"
          autoComplete="new-password"
          value={confirm}
          onChange={(e) => {
            setConfirm(e.target.value)
            setError(undefined)
          }}
          error={error?.field === 'confirm' ? error.message : undefined}
          readOnly={busy}
        />
        <div className={styles.actions}>
          <Button size="md" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button variant="solid" size="md" type="submit" busy={busy}>
            {busy ? 'Creating…' : 'Create and lock'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

/**
 * Touch ID, offered once the lock password has been typed (offerTouchId). Asked here, not by the form the
 * password was typed in: that goes as soon as notes unlock.
 */
export function TouchIdOffer({ username }: { username: string }) {
  const titleId = useId()
  const [busy, setBusy] = useState(false)
  const offered = useTouchIdOffer() === username

  async function answer(yes: boolean) {
    setBusy(yes)
    await answerTouchIdOffer(yes)
    setBusy(false)
  }

  if (!offered) return null
  return (
    <Dialog labelledBy={titleId} onClose={busy ? undefined : () => void answer(false)}>
      <div className={styles.stack}>
        <h2 id={titleId} className={styles.title}>
          Use Touch ID for locked notes?
        </h2>
        <p className={styles.text}>Open locked notes with Touch ID instead of the lock password on this Mac.</p>
        <div className={styles.actions}>
          <Button size="md" onClick={() => void answer(false)} disabled={busy}>
            Not now
          </Button>
          <Button variant="solid" size="md" onClick={() => void answer(true)} busy={busy} autoFocus>
            {busy ? 'Waiting for Touch ID…' : 'Use Touch ID'}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
