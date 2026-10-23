import { useCallback, useRef, useState, type FormEvent } from 'react'
import { auth } from '../api/auth'
import { ApiError } from '../api/client'
import { Button } from '../ui/button'
import { Field } from '../ui/field'
import { Kicker } from '../ui/kicker'
import { PasswordMeter } from '../ui/password-meter'
import { useShake } from '../ui/shake'
import { Steps } from '../ui/steps'
import { useAuth } from './auth-context'
import { AuthLayout } from './auth-layout'
import { genericMessage } from './errors'
import styles from './auth.module.css'

type Step = 'welcome' | 'account'
const order: Step[] = ['welcome', 'account']
const stepName: Record<Step, string> = { welcome: 'Welcome', account: 'Account' }

type FieldErrors = Partial<Record<'username' | 'password' | 'confirm', string>>

/** First run: the server has no account yet, and whoever gets here first creates it (§10). */
export function Onboarding() {
  const { signedIn, retry } = useAuth()
  const [step, setStep] = useState<Step>('welcome')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [accountRef, shakeAccount] = useShake<HTMLDivElement>()
  const navigated = useRef(false)

  function go(next: Step) {
    navigated.current = true
    setStep(next)
  }

  // Stable ref callback: each step renders its own <h1>, so this runs once
  // per step. Focus makes screen readers announce it; skipped on first load.
  const heading = useCallback((el: HTMLHeadingElement | null) => {
    if (el && navigated.current) el.focus()
  }, [])

  async function submitAccount(e: FormEvent) {
    e.preventDefault()
    const next: FieldErrors = {}
    if (!username.trim()) next.username = 'Choose a username.'
    if (password.length < 8) next.password = 'Use at least 8 characters.'
    else if (confirm !== password) next.confirm = "The passwords don't match."
    setErrors(next)
    setFormError(undefined)
    if (Object.keys(next).length > 0) {
      shakeAccount()
      return
    }

    setBusy(true)
    try {
      await auth.setup(username.trim(), password)
    } catch (err) {
      setBusy(false)
      if (err instanceof ApiError) {
        if (err.code === 'already_set_up') {
          retry()
          return
        }
        if (err.status === 400 && (err.field === 'username' || err.field === 'password')) {
          setErrors({ [err.field]: sentence(err.message) })
          shakeAccount()
          return
        }
      }
      setFormError(genericMessage(err))
      shakeAccount()
      return
    }

    try {
      signedIn(await auth.login(username.trim(), password), { firstRun: true })
    } catch {
      // The account exists now; the regular sign-in screen takes it from here.
      retry()
    }
  }

  const index = order.indexOf(step)
  const kicker = (s: Step) => `Step ${order.indexOf(s) + 1} of ${order.length} · ${stepName[s]}`

  return (
    <AuthLayout aside={<Steps current={index} total={order.length} />}>
      {step === 'welcome' && (
        <div className={styles.stack}>
          <Kicker>{kicker('welcome')}</Kicker>
          <h1 ref={heading} tabIndex={-1} className={styles.hero}>
            A quiet place for everything you write.
          </h1>
          <p className={styles.lede}>
            notesd runs on your own server. What you write stays there, and every device you sign in on stays in
            sync. This server has no account yet, so let’s create yours.
          </p>
          <Button variant="solid" onClick={() => go('account')}>
            Get started
          </Button>
        </div>
      )}

      {step === 'account' && (
        <form className={styles.stack} onSubmit={(e) => void submitAccount(e)} noValidate>
          <Kicker>{kicker('account')}</Kicker>
          <h1 ref={heading} tabIndex={-1}>
            Choose how you’ll sign in
          </h1>
          <p className={styles.lede}>This is the first account on this server.</p>
          <div ref={accountRef} className={styles.fields}>
            <Field
              label="Username"
              name="username"
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              error={errors.username}
            />
            <Field
              label="Password"
              name="password"
              type="password"
              placeholder="At least 8 characters"
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              error={errors.password}
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
            />
            {formError && (
              <p role="alert" className={styles.formError}>
                {formError}
              </p>
            )}
          </div>
          <div className={styles.actions}>
            <Button onClick={() => go('welcome')} disabled={busy}>
              Back
            </Button>
            <Button variant="accent" type="submit" grow busy={busy}>
              {busy ? 'Creating account…' : 'Create account'}
            </Button>
          </div>
        </form>
      )}
    </AuthLayout>
  )
}

function sentence(message: string): string {
  const s = message.charAt(0).toUpperCase() + message.slice(1)
  return s.endsWith('.') ? s : `${s}.`
}
