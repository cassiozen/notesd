import { useEffect, useState, type FormEvent } from 'react'
import { auth } from '../api/auth'
import { ApiError } from '../api/client'
import { setupLinks } from '../api/users'
import { keepIfInstalledApp } from '../offline/device'
import { navigate } from '../router'
import { Button } from '../ui/button'
import { Field } from '../ui/field'
import { Kicker } from '../ui/kicker'
import { PasswordMeter } from '../ui/password-meter'
import { useShake } from '../ui/shake'
import { useAuth } from './auth-context'
import { AuthLayout } from './auth-layout'
import { genericMessage } from './errors'
import styles from './auth.module.css'

export const SETUP_PATH = '/setup'

type Link =
  | { status: 'checking' }
  | { status: 'invalid' }
  | { status: 'error'; message: string }
  | { status: 'ready'; username: string; pending: boolean }

type FieldErrors = Partial<Record<'password' | 'confirm', string>>

/**
 * The page a setup link opens (settings/people.tsx makes them): an invited person chooses their password, or
 * someone who forgot theirs chooses a new one, then they're signed in.
 */
export function SetupLinkPage() {
  const { state, signedIn, signOut } = useAuth()
  const [token] = useState(readToken)
  const [link, setLink] = useState<Link>(() => (token ? { status: 'checking' } : { status: 'invalid' }))

  // Out of the address bar and history, but kept in this entry's state so a reload still works.
  useEffect(() => {
    if (token) window.history.replaceState({ setupToken: token }, '', SETUP_PATH)
  }, [token])

  useEffect(() => {
    if (!token) return
    let cancelled = false
    void checkLink(token).then((next) => {
      if (!cancelled) setLink(next)
    })
    return () => {
      cancelled = true
    }
  }, [token])

  if (link.status === 'checking' || state.status === 'loading') return null

  if (link.status === 'invalid') {
    return (
      <AuthLayout>
        <div className={styles.stack}>
          <Kicker>Link</Kicker>
          <h1>This link doesn’t work anymore</h1>
          <p className={styles.lede}>
            It was already used, a newer one replaced it, or it’s more than 7 days old. Ask whoever sent it for a new
            one.
          </p>
          <Button variant="solid" onClick={() => navigate('/', { replace: true })}>
            Go to sign in
          </Button>
        </div>
      </AuthLayout>
    )
  }

  if (link.status === 'error') {
    return (
      <AuthLayout>
        <div className={styles.stack} role="alert">
          <Kicker>Link</Kicker>
          <h1>Couldn’t check this link</h1>
          <p className={styles.lede}>{link.message}</p>
          <Button
            variant="solid"
            onClick={() => {
              setLink({ status: 'checking' })
              void checkLink(token).then(setLink)
            }}
          >
            Try again
          </Button>
        </div>
      </AuthLayout>
    )
  }

  const current = 'username' in state ? state.username : null
  // Finishing here would sign this browser in as someone else; their own link, it simply signs in again.
  if (current !== null && current.toLowerCase() !== link.username.toLowerCase()) {
    return (
      <AuthLayout>
        <div className={styles.stack}>
          <Kicker>{link.pending ? 'Invitation' : 'Password reset'}</Kicker>
          <h1>You’re signed in as {current}</h1>
          <p className={styles.lede}>
            This link is for {link.username}. To use it in this browser, sign out first. Or send it on, to open on their
            own device.
          </p>
          <div className={styles.actions}>
            <Button onClick={() => navigate('/', { replace: true })}>Back to my notes</Button>
            <Button variant="solid" grow onClick={() => void signOut()}>
              Sign out
            </Button>
          </div>
        </div>
      </AuthLayout>
    )
  }

  return (
    <PasswordForm
      token={token}
      username={link.username}
      pending={link.pending}
      onInvalid={() => setLink({ status: 'invalid' })}
      onDone={(info) => {
        navigate('/', { replace: true })
        signedIn(info, { firstRun: link.pending })
      }}
    />
  )
}

async function checkLink(token: string): Promise<Link> {
  try {
    const { username, pending } = await setupLinks.peek(token)
    return { status: 'ready', username, pending }
  } catch (err) {
    return err instanceof ApiError && err.code === 'link_invalid' ? { status: 'invalid' } : { status: 'error', message: genericMessage(err) }
  }
}

function readToken(): string {
  const fromHash = window.location.hash.slice(1)
  if (fromHash) return fromHash
  const kept: unknown = window.history.state
  return kept && typeof kept === 'object' && 'setupToken' in kept && typeof kept.setupToken === 'string' ? kept.setupToken : ''
}

function PasswordForm({
  token,
  username,
  pending,
  onInvalid,
  onDone,
}: {
  token: string
  username: string
  pending: boolean
  onInvalid: () => void
  onDone: (info: Awaited<ReturnType<typeof auth.login>>) => void
}) {
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [fieldsRef, shake] = useShake<HTMLDivElement>()

  async function submit(e: FormEvent) {
    e.preventDefault()
    const found: FieldErrors = {}
    if (password.length < 8) found.password = 'Use at least 8 characters.'
    else if (confirm !== password) found.confirm = "The passwords don't match."
    setErrors(found)
    setFormError(undefined)
    if (Object.keys(found).length > 0) {
      shake()
      return
    }

    setBusy(true)
    try {
      await setupLinks.use(token, password)
    } catch (err) {
      setBusy(false)
      if (err instanceof ApiError && err.code === 'link_invalid') onInvalid()
      else if (err instanceof ApiError && err.field === 'password') {
        setErrors({ password: 'Use at least 8 characters, and at most 1024 bytes.' })
        shake()
      } else {
        setFormError(genericMessage(err))
        shake()
      }
      return
    }

    try {
      const info = await auth.login(username, password)
      await keepIfInstalledApp(info.username)
      onDone(info)
    } catch {
      // The password is set; the regular sign-in screen takes it from here.
      navigate('/', { replace: true })
    }
  }

  return (
    <AuthLayout>
      <form className={styles.stack} onSubmit={(e) => void submit(e)} noValidate>
        <Kicker>{pending ? 'Invitation' : 'Password reset'}</Kicker>
        <h1>{pending ? `Welcome, ${username}.` : `A new password for ${username}`}</h1>
        <p className={styles.lede}>
          {pending
            ? `You’ve been invited to notesd at ${window.location.host}. Choose a password, and you’re in.`
            : 'Choosing one signs you out on every other browser and device.'}
        </p>
        <div ref={fieldsRef} className={styles.fields}>
          <Field label="Username" name="username" autoComplete="username" value={username} readOnly />
          <Field
            label={pending ? 'Password' : 'New password'}
            name="password"
            type="password"
            placeholder="At least 8 characters"
            autoComplete="new-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            error={errors.password}
            readOnly={busy}
            autoFocus
          >
            <PasswordMeter password={password} />
          </Field>
          <Field
            label="Confirm password"
            name="confirm"
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            error={errors.confirm}
            readOnly={busy}
          />
          {formError && (
            <p role="alert" className={styles.formError}>
              {formError}
            </p>
          )}
        </div>
        <Button variant="accent" type="submit" busy={busy}>
          {busy ? 'Signing in…' : pending ? 'Get started' : 'Set password'}
        </Button>
      </form>
    </AuthLayout>
  )
}
