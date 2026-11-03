import { useId, useState, type ClipboardEvent, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import { settings } from '../api/settings'
import { genericMessage } from '../auth/errors'
import { setEmailSettings, useEmailSettings } from '../notes/email-settings'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { Field } from '../ui/field'
import { MailIcon, SendIcon } from '../ui/icons'
import { Steps } from '../ui/steps'
import type { Integration, IntegrationStatus } from './integrations'
import styles from './settings.module.css'

const API_KEYS = 'https://resend.com/api-keys'
const DOMAINS = 'https://resend.com/domains'

function ResendMark() {
  return (
    <span className={styles.mark} aria-hidden="true">
      <MailIcon size={18} />
    </span>
  )
}

export const resend: Integration = {
  id: 'resend',
  name: 'Resend',
  pitch: 'Email notes to anyone, from an address on your own domain.',
  mark: <ResendMark />,
  Connect: ResendConnect,
  Manage: ResendManage,
}

export function useResendStatus(): IntegrationStatus {
  const saved = useEmailSettings()
  if (!saved) return { state: 'loading' }
  return saved.configured
    ? { state: 'on', editable: saved.editable, detail: `Sending as ${saved.from}` }
    : { state: 'off', editable: saved.editable }
}

/** The server's own check, made here too: the key isn't sent until the sender's chosen. */
function keyError(key: string): string | undefined {
  if (!key) return 'Paste your Resend API key.'
  if (!key.startsWith('re_') || key.length < 8 || /\s/.test(key)) return 'A Resend API key starts with re_.'
  return undefined
}

/** "Name <a@b.c>" in its parts; a bare address has no name. */
export function parseFrom(from: string): { name: string; address: string } {
  const m = /^\s*(.*?)\s*<([^<>]+)>\s*$/.exec(from)
  if (!m) return { name: '', address: from.trim() }
  let name = m[1] ?? ''
  if (name.length >= 2 && name.startsWith('"') && name.endsWith('"')) name = name.slice(1, -1).replace(/\\(.)/g, '$1')
  return { name, address: (m[2] ?? '').trim() }
}

export function formatFrom(name: string, address: string): string {
  const n = name.trim()
  const a = address.trim()
  if (!n) return a
  // RFC 5322 specials in a display name need quotes, or the server refuses the address.
  const shown = /[()<>[\]:;@\\,."]/.test(n) ? `"${n.replace(/["\\]/g, '\\$&')}"` : n
  return `${shown} <${a}>`
}

type Errors = Partial<Record<'apiKey' | 'address' | 'form', string>>

function sentence(message: string): string {
  const s = message.charAt(0).toUpperCase() + message.slice(1)
  return s.endsWith('.') ? s : `${s}.`
}

/** The server's refusal, on the field it's about. */
function saveErrors(err: unknown): Errors {
  if (err instanceof ApiError && err.status === 400 && err.field === 'apiKey') return { apiKey: sentence(err.message) }
  if (err instanceof ApiError && err.status === 400 && err.field === 'from') return { address: sentence(err.message) }
  return { form: genericMessage(err) }
}

/** Key, then sender, then done. Nothing's saved until the sender's chosen. */
function ResendConnect({ onClose }: { onClose: () => void }) {
  const titleId = useId()
  const [step, setStep] = useState<'key' | 'sender' | 'done'>('key')
  const [apiKey, setApiKey] = useState('')
  const [name, setName] = useState('')
  const [address, setAddress] = useState('')
  const [errors, setErrors] = useState<Errors>({})
  const [busy, setBusy] = useState(false)

  function next(e: FormEvent) {
    e.preventDefault()
    const error = keyError(apiKey.trim())
    setErrors({ apiKey: error })
    if (!error) setStep('sender')
  }

  // A whole key pasted in needs no Continue.
  function paste(e: ClipboardEvent<HTMLInputElement>) {
    const text = e.clipboardData.getData('text').trim()
    if (apiKey || keyError(text)) return
    e.preventDefault()
    setApiKey(text)
    setErrors({})
    setStep('sender')
  }

  async function connect(e: FormEvent) {
    e.preventDefault()
    if (!address.trim()) {
      setErrors({ address: 'Enter the address emails come from.' })
      return
    }
    setErrors({})
    setBusy(true)
    try {
      setEmailSettings(await settings.saveEmail(formatFrom(name, address), apiKey.trim()))
      setStep('done')
    } catch (err) {
      const found = saveErrors(err)
      setErrors(found)
      if (found.apiKey) setStep('key')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog labelledBy={titleId} onClose={busy ? undefined : onClose}>
      {step === 'key' && (
        <form className={styles.dialog} onSubmit={next} noValidate>
          <Steps current={0} total={2} />
          <h2 id={titleId} className={styles.dialogTitle}>
            Connect Resend
          </h2>
          <p className={styles.dialogText}>Notes are sent through your Resend account, with an API key from it.</p>
          <Field
            label="API key"
            type="password"
            mono
            autoComplete="off"
            spellCheck={false}
            placeholder="re_…"
            value={apiKey}
            onChange={(e) => {
              setApiKey(e.target.value)
              setErrors({})
            }}
            onPaste={paste}
            error={errors.apiKey}
            hint={
              <>
                <a href={API_KEYS} target="_blank" rel="noreferrer">
                  Create one in Resend ↗
                </a>{' '}
                with sending access, then paste it here.
              </>
            }
            autoFocus
          />
          <div className={styles.dialogActions}>
            <Button size="md" onClick={onClose}>
              Cancel
            </Button>
            <Button variant="solid" size="md" type="submit">
              Continue
            </Button>
          </div>
        </form>
      )}
      {step === 'sender' && (
        <form className={styles.dialog} onSubmit={(e) => void connect(e)} noValidate>
          <Steps current={1} total={2} />
          <h2 id={titleId} className={styles.dialogTitle}>
            Who it’s from
          </h2>
          <SenderFields
            name={name}
            address={address}
            onName={setName}
            onAddress={(a) => {
              setAddress(a)
              setErrors({})
            }}
            error={errors.address}
            readOnly={busy}
          />
          {errors.form && (
            <p role="alert" className={styles.error}>
              {errors.form}
            </p>
          )}
          <div className={styles.dialogActions}>
            <Button size="md" onClick={() => setStep('key')} disabled={busy}>
              Back
            </Button>
            <Button variant="solid" size="md" type="submit" busy={busy}>
              {busy ? 'Connecting…' : 'Connect'}
            </Button>
          </div>
        </form>
      )}
      {step === 'done' && (
        <div className={styles.dialog}>
          <span className={styles.sent} aria-hidden="true">
            <SendIcon size={30} />
          </span>
          <h2 id={titleId} className={[styles.dialogTitle, styles.centered].join(' ')}>
            Ready to send
          </h2>
          <p className={[styles.dialogText, styles.centered].join(' ')}>
            Anyone on this server can now email a note: choose Email… from its menu.
          </p>
          <div className={styles.dialogActions}>
            <Button variant="solid" size="md" grow onClick={onClose} autoFocus>
              Done
            </Button>
          </div>
        </div>
      )}
    </Dialog>
  )
}

/** The sender and key as saved; a new key replaces the old one, an empty field keeps it. */
function ResendManage({ onClose }: { onClose: () => void }) {
  const titleId = useId()
  const saved = useEmailSettings()
  const [initial] = useState(() => parseFrom(saved?.from ?? ''))
  const [name, setName] = useState(initial.name)
  const [address, setAddress] = useState(initial.address)
  const [apiKey, setApiKey] = useState('')
  const [errors, setErrors] = useState<Errors>({})
  const [busy, setBusy] = useState(false)
  const [disconnecting, setDisconnecting] = useState(false)

  async function save(e: FormEvent) {
    e.preventDefault()
    const found: Errors = {
      ...(apiKey.trim() && { apiKey: keyError(apiKey.trim()) }),
      ...(!address.trim() && { address: 'Enter the address emails come from.' }),
    }
    setErrors(found)
    if (found.apiKey || found.address) return
    setBusy(true)
    try {
      setEmailSettings(await settings.saveEmail(formatFrom(name, address), apiKey.trim() || undefined))
      onClose()
    } catch (err) {
      setErrors(saveErrors(err))
      setBusy(false)
    }
  }

  async function disconnect() {
    setBusy(true)
    try {
      await settings.removeEmail()
      setEmailSettings({ configured: false, from: '', keyHint: '', editable: true })
      onClose()
    } catch (err) {
      setErrors({ form: genericMessage(err) })
      setBusy(false)
    }
  }

  if (disconnecting) {
    return (
      <Dialog labelledBy={titleId} onClose={busy ? undefined : () => setDisconnecting(false)}>
        <div className={styles.dialog}>
          <h2 id={titleId} className={styles.dialogTitle}>
            Disconnect Resend?
          </h2>
          <p className={styles.dialogText}>
            Nobody on this server can email notes until it’s connected again. The API key is removed from the server.
          </p>
          {errors.form && (
            <p role="alert" className={styles.error}>
              {errors.form}
            </p>
          )}
          <div className={styles.dialogActions}>
            <Button size="md" onClick={() => setDisconnecting(false)} disabled={busy}>
              Keep it
            </Button>
            <Button variant="danger" size="md" busy={busy} onClick={() => void disconnect()}>
              Disconnect
            </Button>
          </div>
        </div>
      </Dialog>
    )
  }

  return (
    <Dialog labelledBy={titleId} onClose={busy ? undefined : onClose}>
      <form className={styles.dialog} onSubmit={(e) => void save(e)} noValidate>
        <h2 id={titleId} className={styles.dialogTitle}>
          Resend
        </h2>
        <SenderFields
          name={name}
          address={address}
          onName={setName}
          onAddress={(a) => {
            setAddress(a)
            setErrors({})
          }}
          error={errors.address}
          readOnly={busy}
        />
        <Field
          label="API key"
          type="password"
          mono
          autoComplete="off"
          spellCheck={false}
          placeholder={`Saved · …${saved?.keyHint ?? ''}`}
          value={apiKey}
          onChange={(e) => {
            setApiKey(e.target.value)
            setErrors({})
          }}
          error={errors.apiKey}
          hint="Paste a new key to replace the saved one."
          readOnly={busy}
        />
        {errors.form && (
          <p role="alert" className={styles.error}>
            {errors.form}
          </p>
        )}
        <div className={styles.dialogActions}>
          <Button variant="danger-text" size="md" className={styles.leading} onClick={() => setDisconnecting(true)} disabled={busy}>
            Disconnect…
          </Button>
          <Button size="md" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button variant="solid" size="md" type="submit" busy={busy}>
            Save
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

/** Name and address, and how they'll look in a recipient's inbox as they're typed. */
function SenderFields({
  name,
  address,
  onName,
  onAddress,
  error,
  readOnly,
}: {
  name: string
  address: string
  onName: (name: string) => void
  onAddress: (address: string) => void
  error: string | undefined
  readOnly: boolean
}) {
  const shown = name.trim() || address.trim() || 'Your name'
  return (
    <>
      <div className={styles.inbox} aria-hidden="true">
        <span className={styles.inboxAvatar}>{shown.charAt(0).toUpperCase()}</span>
        <span className={styles.inboxBody}>
          <span className={styles.inboxFrom}>
            <span className={styles.inboxName}>{shown}</span>
            {name.trim() && address.trim() && <span className={styles.inboxAddress}>{address.trim()}</span>}
            <span className={styles.inboxTime}>now</span>
          </span>
          <span className={styles.inboxSubject}>Saturday market</span>
          <span className={styles.inboxSnippet}>Sourdough, ripe tomatoes and something green. Ask about the late plums.</span>
        </span>
      </div>
      <Field
        label="Name"
        autoComplete="name"
        placeholder="Your name"
        value={name}
        onChange={(e) => onName(e.target.value)}
        hint="Optional. Shown beside the address."
        readOnly={readOnly}
        autoFocus
      />
      <Field
        label="Address"
        type="email"
        autoComplete="off"
        spellCheck={false}
        placeholder="notes@example.com"
        value={address}
        onChange={(e) => onAddress(e.target.value)}
        error={error}
        hint={
          <>
            On a domain you’ve{' '}
            <a href={DOMAINS} target="_blank" rel="noreferrer">
              verified in Resend
            </a>
            .
          </>
        }
        readOnly={readOnly}
      />
    </>
  )
}
