import { useState, type FormEvent } from 'react'
import { auth } from '../api/auth'
import { ApiError } from '../api/client'
import { Button } from '../ui/button'
import { Command } from '../ui/code'
import { Field } from '../ui/field'
import { useShake } from '../ui/shake'
import { useAuth } from './auth-context'
import { AuthLayout } from './auth-layout'
import { askToPersist, useDeviceSupported } from '../offline/device'
import { genericMessage } from './errors'
import { deviceUser, keepOnDevice } from './session-hint'
import styles from './auth.module.css'

type Props = {
  heading?: string
  /** Shown under the heading, e.g. why the user has to sign in again. */
  notice?: string
  /** Prefilled and read-only when re-authenticating an expired session. */
  username?: string
  headingId?: string
  /** Offer to keep the notes in this browser (a fresh sign-in, not signing in again). */
  offerDevice?: boolean
}

export function LoginForm({ heading = 'Welcome back.', notice, username: fixedUsername, headingId, offerDevice = false }: Props) {
  const { signedIn } = useAuth()
  const [username, setUsername] = useState(fixedUsername ?? '')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [fieldsRef, shake] = useShake<HTMLDivElement>()
  // Kept already: opting out is Settings', which warns of changes not yet on the server.
  const [keptHere] = useState(() => deviceUser() !== null)
  const canKeep = useDeviceSupported(offerDevice && !keptHere)
  const [keep, setKeep] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!username.trim() || !password) {
      setError('Enter your username and password.')
      shake()
      return
    }
    setBusy(true)
    setError(undefined)
    const wantsDevice = canKeep && keep
    // Now, while the click still counts.
    if (wantsDevice || keptHere) askToPersist()
    try {
      const info = await auth.login(username.trim(), password)
      if (wantsDevice) keepOnDevice(info.username)
      signedIn(info)
    } catch (err) {
      setBusy(false)
      setError(
        err instanceof ApiError && err.code === 'invalid_credentials'
          ? 'Wrong username or password.'
          : genericMessage(err),
      )
      shake()
    }
  }

  return (
    <form className={styles.stack} onSubmit={(e) => void submit(e)} noValidate>
      <h1 id={headingId}>{heading}</h1>
      {notice && <p className={styles.lede}>{notice}</p>}
      <div ref={fieldsRef} className={styles.fields}>
        <Field
          label="Username"
          name="username"
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
          value={username}
          readOnly={fixedUsername !== undefined}
          onChange={(e) => setUsername(e.target.value)}
          autoFocus={fixedUsername === undefined}
        />
        <Field
          label="Password"
          name="password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => {
            setPassword(e.target.value)
            setError(undefined)
          }}
          autoFocus={fixedUsername !== undefined}
          aria-invalid={error ? true : undefined}
        />
        {error && (
          <p role="alert" className={styles.formError}>
            {error}
          </p>
        )}
      </div>
      {canKeep && (
        <label className={styles.keep}>
          <input type="checkbox" checked={keep} onChange={(e) => setKeep(e.target.checked)} />
          <span>
            Keep my notes on this device
            <span className={styles.keepWhy}>
              They open and save without a connection. Only on a device you trust: anyone using this browser can read them, locked notes aside.
            </span>
          </span>
        </label>
      )}
      <Button variant="solid" type="submit" busy={busy}>
        {busy ? 'Signing in…' : 'Sign in'}
      </Button>
      <div className={styles.help}>
        <span>Forgot your password? Reset it on the server:</span>
        <Command command="notesd reset-password" />
      </div>
    </form>
  )
}

export function Login() {
  return (
    <AuthLayout footer={<span className={styles.host}>{window.location.host}</span>}>
      <LoginForm offerDevice />
    </AuthLayout>
  )
}
