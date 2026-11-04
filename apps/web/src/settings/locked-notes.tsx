import { useCallback, useEffect, useId, useState, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import { genericMessage } from '../auth/errors'
import { LockedError } from '../locks/crypto'
import { PasskeyError, passkeysAvailable } from '../locks/passkey'
import { lockSession, useLockKeys, useLockStatus } from '../locks/session'
import { forgetTouchId, touchIdPasswordChanged, turnOffTouchId, turnOnTouchId, useTouchId } from '../locks/touch-id'
import { MIN_LOCK_PASSWORD } from '../locks/unlock'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { Field } from '../ui/field'
import { Kicker } from '../ui/kicker'
import { Surface } from '../ui/surface'
import styles from './settings.module.css'

type Errors = Partial<Record<'current' | 'next' | 'confirm' | 'account', string>>

/** Locked notes (§10.1): change the lock password, lock now, Touch ID, passkeys, or reset after forgetting it. Each form opens in a dialog. */
export function LockedNotesCard({ username }: { username: string }) {
  const status = useLockStatus()
  const [loadError, setLoadError] = useState<string>()
  // Fresh each time: another device may have changed the password, reset, or added a passkey.
  const fetchKeyring = useCallback(() => {
    lockSession.load(true).catch((err: unknown) => setLoadError(genericMessage(err)))
  }, [])
  useEffect(fetchKeyring, [fetchKeyring])

  return (
    <Surface as="section" className={styles.card} aria-labelledby="locks-kicker">
      <Kicker id="locks-kicker">Locked notes</Kicker>
      {status === 'unknown' &&
        (loadError ? (
          <>
            <p role="alert" className={styles.error}>
              {loadError}
            </p>
            <div>
              <Button
                size="sm"
                onClick={() => {
                  setLoadError(undefined)
                  fetchKeyring()
                }}
              >
                Try again
              </Button>
            </div>
          </>
        ) : (
          <span className={styles.muted}>Loading…</span>
        ))}
      {status === 'none' && (
        <span className={styles.text}>
          No lock password yet. Choose Lock note from a note’s menu to create one; the note is then encrypted so the
          server can’t read it.
        </span>
      )}
      {(status === 'locked' || status === 'unlocked') && (
        <LockItems username={username} unlocked={status === 'unlocked'} />
      )}
    </Surface>
  )
}

function LockItems({ username, unlocked }: { username: string; unlocked: boolean }) {
  const passkeys = useLockKeys().filter((k) => k.kind === 'passkey')
  const available = passkeysAvailable()
  const touchId = useTouchId(username)
  const [open, setOpen] = useState<'change' | 'touch-id' | 'passkey' | 'reset' | null>(null)
  const [message, setMessage] = useState<string>()
  const [error, setError] = useState<string>()
  const [removing, setRemoving] = useState<string | null>(null)
  const close = () => setOpen(null)
  const show = (dialog: typeof open) => {
    setMessage(undefined)
    setError(undefined)
    setOpen(dialog)
  }

  async function remove(id: string) {
    setRemoving(id)
    setMessage(undefined)
    setError(undefined)
    try {
      await lockSession.removePasskey(id)
    } catch (err) {
      setError(genericMessage(err))
    } finally {
      setRemoving(null)
    }
  }

  return (
    <>
      <ul className={styles.items}>
        <li className={styles.item}>
          <span className={styles.itemText}>
            <span className={styles.itemTitle}>Lock password</span>
            <span className={styles.muted}>{unlocked ? 'Unlocked in this browser' : 'Locked'}</span>
          </span>
          <span className={styles.itemActions}>
            {unlocked && (
              <Button size="sm" onClick={() => void lockSession.lock()}>
                Lock now
              </Button>
            )}
            <Button size="sm" onClick={() => show('change')} aria-label="Change lock password">
              Change…
            </Button>
          </span>
        </li>
        {touchId.available && (
          <li className={styles.item}>
            <span className={styles.itemText}>
              <span className={styles.itemTitle}>Touch ID</span>
              <span className={styles.muted}>
                {touchId.enabled
                  ? 'Touch ID unlocks locked notes on this Mac.'
                  : 'Unlock with Touch ID instead of typing the lock password, on this Mac.'}
              </span>
            </span>
            {touchId.enabled ? (
              <Button
                size="sm"
                onClick={() => {
                  setMessage(undefined)
                  turnOffTouchId(username)
                }}
                aria-label="Turn off Touch ID"
              >
                Turn off
              </Button>
            ) : (
              <Button size="sm" onClick={() => show('touch-id')} aria-label="Turn on Touch ID">
                Turn on…
              </Button>
            )}
          </li>
        )}
        {(available || passkeys.length > 0) && (
          <li className={styles.item}>
            <span className={styles.itemText}>
              <span className={styles.itemTitle}>Passkeys</span>
              <span className={styles.muted}>
                {passkeys.length === 0
                  ? 'Unlock with a fingerprint, face or device PIN instead of typing the lock password.'
                  : `${passkeys.length} ${passkeys.length === 1 ? 'passkey unlocks' : 'passkeys unlock'} locked notes.`}
              </span>
            </span>
            {available && (
              <Button size="sm" onClick={() => show('passkey')} aria-label="Add a passkey">
                Add…
              </Button>
            )}
          </li>
        )}
        {passkeys.map((k) => {
          const added = new Date(k.createdAt).toLocaleDateString()
          return (
            <li key={k.id} className={[styles.item, styles.subItem].join(' ')}>
              <span className={styles.itemText}>
                <span className={styles.text}>Passkey added {added}</span>
              </span>
              <Button
                variant="danger-text"
                size="sm"
                onClick={() => void remove(k.id)}
                busy={removing === k.id}
                disabled={removing !== null || !unlocked}
                title={unlocked ? undefined : 'Unlock locked notes to remove a passkey'}
                aria-label={`Remove passkey added ${added}`}
              >
                Remove
              </Button>
            </li>
          )
        })}
        <li className={styles.item}>
          <span className={styles.itemText}>
            <span className={styles.itemTitle}>Forgot the lock password?</span>
            <span className={styles.muted}>Locked notes can’t be recovered without it.</span>
          </span>
          <Button variant="danger-text" size="sm" onClick={() => show('reset')} aria-label="Reset locked notes">
            Reset…
          </Button>
        </li>
      </ul>
      {message && (
        <p role="status" className={styles.success}>
          {message}
        </p>
      )}
      {error && (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      )}
      {open === 'change' && (
        <ChangeDialog
          username={username}
          onClose={close}
          onChanged={() => {
            close()
            setMessage('Lock password changed. Other devices use the new one once they sync.')
          }}
        />
      )}
      {open === 'touch-id' && (
        <TouchIdDialog
          username={username}
          onClose={close}
          onTurnedOn={() => {
            close()
            setMessage('Touch ID is on. Use it to unlock locked notes on this Mac.')
          }}
        />
      )}
      {open === 'passkey' && (
        <PasskeyDialog
          username={username}
          onClose={close}
          onAdded={() => {
            close()
            setMessage('Passkey added. Use it to unlock locked notes wherever it’s available.')
          }}
        />
      )}
      {open === 'reset' && <ResetDialog username={username} onClose={close} />}
    </>
  )
}

function ChangeDialog({
  username,
  onClose,
  onChanged,
}: {
  username: string
  onClose: () => void
  onChanged: () => void
}) {
  const titleId = useId()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<Errors>({})
  const [formError, setFormError] = useState<string>()
  const [busy, setBusy] = useState(false)

  async function change(e: FormEvent) {
    e.preventDefault()
    const found: Errors = {}
    if (!current) found.current = 'Enter your current lock password.'
    if (next.length < MIN_LOCK_PASSWORD) found.next = `Use at least ${MIN_LOCK_PASSWORD} characters.`
    else if (confirm !== next) found.confirm = 'The passwords don’t match.'
    setErrors(found)
    setFormError(undefined)
    if (Object.keys(found).length > 0) return
    setBusy(true)
    try {
      touchIdPasswordChanged(username, await lockSession.changePassword(current, next))
      onChanged()
    } catch (err) {
      if (err instanceof LockedError) setErrors({ current: 'That isn’t your lock password.' })
      else setFormError(genericMessage(err))
      setBusy(false)
    }
  }

  return (
    <Dialog labelledBy={titleId} onClose={busy ? undefined : onClose}>
      <form className={styles.dialog} onSubmit={(e) => void change(e)} noValidate>
        <h2 id={titleId} className={styles.dialogTitle}>
          Change lock password
        </h2>
        <Field
          label="Current lock password"
          type="password"
          autoComplete="current-password"
          value={current}
          onChange={(e) => setCurrent(e.target.value)}
          error={errors.current}
          readOnly={busy}
          autoFocus
        />
        <Field
          label="New lock password"
          type="password"
          autoComplete="new-password"
          placeholder={`At least ${MIN_LOCK_PASSWORD} characters`}
          value={next}
          onChange={(e) => setNext(e.target.value)}
          error={errors.next}
          readOnly={busy}
        />
        <Field
          label="Confirm new lock password"
          type="password"
          autoComplete="new-password"
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
          error={errors.confirm}
          readOnly={busy}
        />
        {formError && (
          <p role="alert" className={styles.error}>
            {formError}
          </p>
        )}
        <div className={styles.dialogActions}>
          <Button size="md" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button variant="solid" size="md" type="submit" busy={busy}>
            {busy ? 'Changing…' : 'Change lock password'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

/** Touch ID instead of the lock password, on this Mac (the macOS app). */
function TouchIdDialog({
  username,
  onClose,
  onTurnedOn,
}: {
  username: string
  onClose: () => void
  onTurnedOn: () => void
}) {
  const titleId = useId()
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  async function turnOn(e: FormEvent) {
    e.preventDefault()
    if (!password) return
    setBusy(true)
    setError(undefined)
    try {
      const outcome = await turnOnTouchId(username, password)
      if (outcome === 'done') {
        onTurnedOn()
        return
      }
      // Cancelled: asked again on Continue.
      if (outcome === 'failed') setError('Touch ID couldn’t be turned on. Try again.')
    } catch (err) {
      setError(err instanceof LockedError ? 'That isn’t your lock password.' : genericMessage(err))
    }
    setBusy(false)
  }

  return (
    <Dialog labelledBy={titleId} onClose={busy ? undefined : onClose}>
      <form className={styles.dialog} onSubmit={(e) => void turnOn(e)} noValidate>
        <h2 id={titleId} className={styles.dialogTitle}>
          Turn on Touch ID
        </h2>
        <p className={styles.dialogText}>
          Unlock locked notes with Touch ID instead of typing the lock password, on this Mac.
        </p>
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
          hint="Needed once, to keep the key on this Mac behind Touch ID."
          readOnly={busy}
          autoFocus
        />
        <div className={styles.dialogActions}>
          <Button size="md" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button variant="solid" size="md" type="submit" busy={busy}>
            {busy ? 'Waiting for Touch ID…' : 'Continue'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

/** A passkey that unlocks locked notes instead of the lock password (WebAuthn PRF). */
function PasskeyDialog({ username, onClose, onAdded }: { username: string; onClose: () => void; onAdded: () => void }) {
  const titleId = useId()
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  async function add(e: FormEvent) {
    e.preventDefault()
    if (!password) return
    setBusy(true)
    setError(undefined)
    try {
      await lockSession.addPasskey(password, username)
      onAdded()
      return
    } catch (err) {
      if (err instanceof PasskeyError && err.reason === 'cancelled') {
        // Asked again on Continue.
      } else if (err instanceof LockedError) setError('That isn’t your lock password.')
      else if (err instanceof PasskeyError) setError(err.message)
      else setError(genericMessage(err))
    }
    setBusy(false)
  }

  return (
    <Dialog labelledBy={titleId} onClose={busy ? undefined : onClose}>
      <form className={styles.dialog} onSubmit={(e) => void add(e)} noValidate>
        <h2 id={titleId} className={styles.dialogTitle}>
          Add a passkey
        </h2>
        <p className={styles.dialogText}>
          Unlock locked notes with a fingerprint, face or device PIN, wherever the passkey is available.
        </p>
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
          hint="Needed once, to give the passkey a copy of the key."
          readOnly={busy}
          autoFocus
        />
        <div className={styles.dialogActions}>
          <Button size="md" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button variant="solid" size="md" type="submit" busy={busy}>
            {busy ? 'Waiting for the passkey…' : 'Continue'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

function ResetDialog({ username, onClose }: { username: string; onClose: () => void }) {
  const titleId = useId()
  const [account, setAccount] = useState('')
  const [errors, setErrors] = useState<Errors>({})
  const [formError, setFormError] = useState<string>()
  const [busy, setBusy] = useState(false)

  async function reset(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setFormError(undefined)
    try {
      // Status goes to none, and this dialog with it.
      await lockSession.reset(account)
      forgetTouchId(username)
    } catch (err) {
      if (err instanceof ApiError && err.code === 'invalid_credentials') setErrors({ account: 'That isn’t your account password.' })
      else setFormError(genericMessage(err))
      setBusy(false)
    }
  }

  return (
    <Dialog labelledBy={titleId} onClose={busy ? undefined : onClose}>
      <form className={styles.dialog} onSubmit={(e) => void reset(e)} noValidate>
        <h2 id={titleId} className={styles.dialogTitle}>
          Reset locked notes?
        </h2>
        <p className={styles.dialogText}>
          Resetting forgets the lock password. Notes locked with it stay encrypted and can never be opened again, on any
          device. You can then create a new lock password.
        </p>
        <Field
          label="Account password"
          type="password"
          autoComplete="current-password"
          value={account}
          onChange={(e) => {
            setAccount(e.target.value)
            setErrors({})
          }}
          error={errors.account}
          readOnly={busy}
          autoFocus
        />
        {formError && (
          <p role="alert" className={styles.error}>
            {formError}
          </p>
        )}
        <div className={styles.dialogActions}>
          <Button size="md" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button variant="danger" size="md" type="submit" busy={busy}>
            Reset locked notes
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
