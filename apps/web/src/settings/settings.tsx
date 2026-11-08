import { useEffect, useId, useRef, useState, type FormEvent, type MouseEvent, type ReactNode } from 'react'
import { auth } from '../api/auth'
import { settings } from '../api/settings'
import { ApiError } from '../api/client'
import type { Session, UpdateStatus } from '../api/types'
import { platformBadge, sessionName } from '../api/user-agent'
import { useAccount } from '../auth/auth-context'
import { genericMessage, newPasswordErrors, sentence } from '../auth/errors'
import { applyFont, readFont, type FontChoice } from '../font'
import { useLockStatus } from '../locks/session'
import { relativeTime } from '../notes/relative-time'
import { useSpaces } from '../notes/spaces'
import { navigate } from '../router'
import { applyTheme, readTheme, type ThemeChoice } from '../theme'
import { Avatar } from '../ui/avatar'
import { Button } from '../ui/button'
import { Command } from '../ui/code'
import { Dialog } from '../ui/dialog'
import { Field } from '../ui/field'
import { AppearanceIcon, ChevronIcon, CloudIcon, LockIcon, MailIcon, SpacesIcon } from '../ui/icons'
import { Kicker } from '../ui/kicker'
import { PHONE, useMediaQuery } from '../ui/media'
import { Sheet } from '../ui/sheet'
import { Surface } from '../ui/surface'
import { DeviceCard, InstallCard, SignOutItem, syncSummary, useKeptHere, useSyncState } from './device'
import { IntegrationsPage, integrationsValue, useIntegrationStatuses } from './integrations'
import { LockedNotesCard } from './locked-notes'
import { PeopleCard } from './people'
import { ProfileItem } from './profile'
import { SECTIONS, type SettingsSection } from './sections'
import { SpacesCard } from './spaces'
import { TimeZoneCard } from './time-zone'
import styles from './settings.module.css'

/** On a phone. Opened from the list, a page's entry is popped, so browser Back won't reopen the page. */
function backToList() {
  if ((window.history.state as { fromList?: boolean } | null)?.fromList) window.history.back()
  else navigate('/settings', { replace: true })
}

export function SettingsSheet({
  username,
  section,
  onClose,
  onSpaceCreated,
}: {
  username: string
  /** None: the list of categories on a phone, Server on a wider screen. */
  section?: SettingsSection
  onClose: () => void
  /** A new space: the app switches to it, and Settings closes. */
  onSpaceCreated: (id: string) => void
}) {
  const phone = useMediaQuery(PHONE)
  const [theme, setTheme] = useState<ThemeChoice>(readTheme)
  const values = useValues(theme)
  const shown = section ?? (phone ? null : 'server')
  const title = SECTIONS.find((s) => s.key === shown)?.title
  const heading = useRef<HTMLHeadingElement>(null)
  const layout = useRef<HTMLDivElement>(null)
  const was = useRef(shown)

  // On a phone, the list and a page replace each other, taking focus with them: a page starts at its
  // title, and the list returns to the category that was open.
  useEffect(() => {
    const before = was.current
    was.current = shown
    if (!phone || before === shown) return
    if (shown) heading.current?.focus()
    else if (before) layout.current?.querySelector<HTMLElement>(`a[href="/settings/${before}"]`)?.focus()
  }, [phone, shown])

  return (
    <Sheet labelledBy="settings-title" onClose={onClose}>
      <div className={styles.top}>
        {phone && title ? (
          <div className={styles.back}>
            <Button size="md" onClick={backToList} aria-label="Back to Settings">
              ‹ Settings
            </Button>
            <h2 id="settings-title" ref={heading} tabIndex={-1}>
              {title}
            </h2>
          </div>
        ) : (
          <h2 id="settings-title">Settings</h2>
        )}
        <Button size="md" onClick={onClose}>
          Done
        </Button>
      </div>

      <div className={styles.layout} ref={layout}>
        {!(phone && shown) && (
          <SettingsNav shown={shown} values={values} replace={!phone} />
        )}
        {shown && (
          <div className={styles.page}>
            {!phone && <h3 className={styles.pageTitle}>{title}</h3>}
            <SettingsPage
              section={shown}
              username={username}
              theme={theme}
              onTheme={setTheme}
              onSpaceCreated={onSpaceCreated}
            />
          </div>
        )}
      </div>
    </Sheet>
  )
}

const GROUPS = [
  { group: 'account', label: 'Synced to your account' },
  { group: 'device', label: 'This browser only' },
] as const

const ICONS: Partial<Record<SettingsSection, ReactNode>> = {
  security: <LockIcon size={15} />,
  spaces: <SpacesIcon size={15} />,
  integrations: <MailIcon size={15} />,
  appearance: <AppearanceIcon size={15} />,
  device: <CloudIcon size={15} />,
}

/**
 * Like the system's settings: who you're signed in as at the top, then each category with its state on the
 * right, under a header that says where its settings are kept.
 */
function SettingsNav({
  shown,
  values,
  replace,
}: {
  shown: SettingsSection | null
  values: Record<SettingsSection, string>
  /** Beside the page, moving between categories needn't stack up in the history. */
  replace: boolean
}) {
  const { name, profile } = useAccount()
  const link = (key: SettingsSection) => ({
    href: `/settings/${key}`,
    'aria-current': shown === key ? ('page' as const) : undefined,
    onClick: (e: MouseEvent) => {
      if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
      e.preventDefault()
      navigate(`/settings/${key}`, { replace, state: replace ? null : { fromList: true } })
    },
  })

  return (
    <nav className={styles.nav} aria-label="Settings categories">
      <a
        {...link('server')}
        className={[styles.navItem, styles.account].join(' ')}
        aria-label={`Server: ${name} on ${window.location.host}`}
      >
        <Avatar userId={profile?.userId} version={profile?.avatar} name={name} className={styles.avatar} />
        <span className={styles.accountText}>
          <span className={styles.navTitle}>{name}</span>
          <span className={styles.navValue}>{window.location.host}</span>
        </span>
        <span className={styles.chevron}>
          <ChevronIcon />
        </span>
      </a>
      {GROUPS.map((g) => (
        <div key={g.group} className={styles.navGroup}>
          <span id={`settings-${g.group}`} className={styles.navLabel}>
            {g.label}
          </span>
          <ul className={styles.navList} aria-labelledby={`settings-${g.group}`}>
            {SECTIONS.filter((s) => s.group === g.group && s.key !== 'server').map((s) => (
              <li key={s.key}>
                <a {...link(s.key)} className={styles.navItem}>
                  <span className={styles.navIcon}>{ICONS[s.key]}</span>
                  <span className={styles.navTitle}>{s.title}</span>
                  <span className={styles.navValue}>{values[s.key]}</span>
                  <span className={styles.chevron}>
                    <ChevronIcon />
                  </span>
                </a>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </nav>
  )
}

/** Each category's state in a word, as the list shows it (docs/contributing/settings-guidelines.md); empty while it loads. */
function useValues(theme: ThemeChoice): Record<SettingsSection, string> {
  const locks = useLockStatus()
  const spaces = useSpaces()
  const integrations = useIntegrationStatuses()
  const kept = useKeptHere()
  const sync = useSyncState()
  return {
    server: window.location.host,
    security: { unknown: '', none: 'Off', locked: 'Locked', unlocked: 'Unlocked' }[locks],
    spaces: spaces ? String(spaces.length) : '',
    integrations: integrationsValue(integrations),
    appearance: themes.find((t) => t.value === theme)?.label ?? '',
    device: kept ? syncSummary(sync) : 'Off',
  }
}

const RELEASES = 'https://github.com/cassiozen/notesd/releases'
const UPGRADING = 'https://notesd.app/docs/guides/self-hosting/upgrading.html'

/** Which notesd the server runs, beside where to find newer ones (docs/guides/self-hosting/upgrading.md). */
function ServerVersion() {
  const [version, setVersion] = useState<string>()
  useEffect(() => {
    let cancelled = false
    auth.session().then(
      (info) => !cancelled && setVersion(info.serverVersion),
      () => {},
    )
    return () => {
      cancelled = true
    }
  }, [])
  if (!version) return null
  return (
    <span className={styles.muted}>
      notesd {version} ·{' '}
      <a href={RELEASES} target="_blank" rel="noreferrer">
        Releases
      </a>
    </span>
  )
}

/** How long an update usually takes, restart included, before the card says to check the server. */
const UPDATE_SLOW = 3 * 60_000

/**
 * A newer release, on a server set up to update itself (docs/guides/self-hosting/upgrading.md, Updating from Settings).
 * Once asked, the server goes away while notesd-update swaps the binary; the page reloads when it's back
 * with a new version, so the web app matches the server.
 */
function UpdateCard() {
  const [status, setStatus] = useState<UpdateStatus>()
  const [phase, setPhase] = useState<'idle' | 'updating'>('idle')
  const [slow, setSlow] = useState(false)
  const [error, setError] = useState<string>()

  useEffect(() => {
    let cancelled = false
    settings.update().then(
      (s) => {
        if (cancelled) return
        setStatus(s)
        if (s.requested) setPhase('updating')
      },
      () => {},
    )
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    if (phase !== 'updating' || !status) return
    // A big data directory can take a while to back up: past UPDATE_SLOW the card only warns, and a
    // late restart still reloads the page.
    const warn = setTimeout(() => setSlow(true), UPDATE_SLOW)
    const timer = setInterval(() => {
      // Fails while the server restarts; the next tick asks again.
      settings.update().then(
        (s) => s.current !== status.current && window.location.reload(),
        () => {},
      )
    }, 3000)
    return () => {
      clearTimeout(warn)
      clearInterval(timer)
    }
  }, [phase, status])

  const selfUpdate = status?.enabled && (status.newer || phase !== 'idle')
  if (!status || (!selfUpdate && !status.majorAvailable)) return null

  async function update() {
    setError(undefined)
    try {
      await settings.requestUpdate()
      setPhase('updating')
    } catch (err) {
      setError(genericMessage(err))
    }
  }

  return (
    <Surface as="section" className={styles.card} aria-labelledby="update-kicker">
      <Kicker id="update-kicker">Update</Kicker>
      {selfUpdate && phase === 'idle' && (
        <>
          <span className={styles.text}>
            notesd {status.latest} is available. This server runs {status.current}.{' '}
            <a href={`${RELEASES}/tag/${status.latest}`} target="_blank" rel="noreferrer">
              What’s new
            </a>
          </span>
          {status.editable ? (
            <Button size="md" onClick={() => void update()}>
              Update to {status.latest}
            </Button>
          ) : (
            <span className={styles.muted}>An admin can update the server.</span>
          )}
        </>
      )}
      {status.majorAvailable && phase === 'idle' && (
        // Never offered as a button: a new major version can need steps notesd-update won't take.
        <span className={styles.text}>
          notesd {status.majorAvailable} is available. Major versions are installed by hand: see{' '}
          <a href={UPGRADING} target="_blank" rel="noreferrer">
            Upgrading
          </a>
          .
        </span>
      )}
      {phase === 'updating' && (
        <p role="status" className={styles.text}>
          Updating to {status.latest}. Notes can’t be saved for a moment; this page reloads when the server is back.
        </p>
      )}
      {slow && (
        <p role="alert" className={styles.error}>
          This is taking longer than usual. To see how it’s going, run{' '}
          <code>journalctl -u notesd-update</code> on the server.
        </p>
      )}
      {error && (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      )}
    </Surface>
  )
}

function SettingsPage({
  section,
  username,
  theme,
  onTheme,
  onSpaceCreated,
}: {
  section: SettingsSection
  username: string
  theme: ThemeChoice
  onTheme: (theme: ThemeChoice) => void
  onSpaceCreated: (id: string) => void
}) {
  switch (section) {
    case 'server':
      return (
        <>
          <Surface as="section" className={styles.card} aria-label="Server">
            <div className={styles.who}>
              <span className={styles.host}>{window.location.host}</span>
              <span className={styles.muted}>Signed in as {username}</span>
              <ServerVersion />
            </div>
            <ul className={styles.items}>
              <ProfileItem />
              <PasswordItem />
              <SignOutItem />
            </ul>
          </Surface>
          <PeopleCard username={username} />
          <UpdateCard />
          <TimeZoneCard />
        </>
      )
    case 'security':
      return (
        <>
          <LockedNotesCard username={username} />
          <Devices />
        </>
      )
    case 'spaces':
      return <SpacesCard onCreated={onSpaceCreated} />
    case 'integrations':
      return <IntegrationsPage />
    case 'appearance':
      return (
        <>
          <Appearance theme={theme} onChange={onTheme} />
          <FontCard />
        </>
      )
    case 'device':
      return (
        <>
          <DeviceCard username={username} />
          <InstallCard />
        </>
      )
  }
}

const themes: { value: ThemeChoice; label: string }[] = [
  { value: 'system', label: 'System' },
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
]

function Appearance({ theme, onChange }: { theme: ThemeChoice; onChange: (theme: ThemeChoice) => void }) {
  return (
    <Surface as="section" className={styles.card} aria-labelledby="theme-kicker">
      <Kicker id="theme-kicker">Theme</Kicker>
      <div className={styles.segmented} role="radiogroup" aria-labelledby="theme-kicker">
        {themes.map((t) => (
          <button
            key={t.value}
            type="button"
            role="radio"
            aria-checked={theme === t.value}
            className={styles.segment}
            onClick={() => {
              onChange(t.value)
              applyTheme(t.value)
            }}
          >
            {t.label}
          </button>
        ))}
      </div>
      <span className={styles.muted}>Only in this browser. Your other devices keep their own theme.</span>
    </Surface>
  )
}

const fonts: { value: FontChoice; label: string; face: string }[] = [
  { value: 'system', label: 'System', face: 'This device’s font' },
  { value: 'sans', label: 'Sans', face: 'Instrument Sans' },
  { value: 'serif', label: 'Serif', face: 'Instrument Serif' },
  { value: 'book', label: 'Book', face: 'Literata' },
  { value: 'legible', label: 'Legible', face: 'Atkinson Hyperlegible Next' },
  { value: 'mono', label: 'Mono', face: 'JetBrains Mono' },
]

/** A short note in the picked font, then each choice set in its own face (tokens.css reads data-font). */
function FontCard() {
  const [font, setFont] = useState<FontChoice>(readFont)
  return (
    <Surface as="section" className={styles.card} aria-labelledby="font-kicker">
      <Kicker id="font-kicker">Font</Kicker>
      <div className={styles.fontSample} data-font={font} aria-hidden="true">
        <span className={styles.fontSampleTitle}>Saturday market</span>
        <span className={styles.fontSampleText}>
          Sourdough, ripe tomatoes and <em>something green</em>. Ask about the late plums.
        </span>
        <span className={styles.fontSampleFace}>{fonts.find((f) => f.value === font)?.face}</span>
      </div>
      <div className={styles.fontOptions} role="radiogroup" aria-labelledby="font-kicker">
        {fonts.map((f) => (
          <label key={f.value} className={styles.fontOption} data-font={f.value}>
            <input
              type="radio"
              name="note-font"
              className="visually-hidden"
              checked={font === f.value}
              onChange={() => {
                setFont(f.value)
                applyFont(f.value)
              }}
            />
            <span className={styles.fontGlyph} aria-hidden="true">
              Aa
            </span>
            <span className={styles.fontName}>{f.label}</span>
          </label>
        ))}
      </div>
      <span className={styles.muted}>For the title and text of notes. Only in this browser.</span>
    </Surface>
  )
}

/** The account password: a line saying what changing it does; the form only once asked for. */
function PasswordItem() {
  const [open, setOpen] = useState(false)
  const [done, setDone] = useState(false)
  return (
    <li className={styles.item}>
      <span className={styles.itemText}>
        <span className={styles.itemTitle}>Password</span>
        {done ? (
          <span role="status" className={styles.good}>
            Password changed. Every other browser and device was signed out and needs the new password.
          </span>
        ) : (
          <span className={styles.muted}>Changing it signs out every other browser and device.</span>
        )}
      </span>
      <Button
        size="sm"
        onClick={() => {
          setDone(false)
          setOpen(true)
        }}
        aria-label="Change password"
      >
        Change…
      </Button>
      {open && (
        <PasswordDialog
          onClose={() => setOpen(false)}
          onChanged={() => {
            setOpen(false)
            setDone(true)
          }}
        />
      )}
    </li>
  )
}

type PasswordErrors = Partial<Record<'current' | 'next' | 'confirm', string>>

function PasswordDialog({ onClose, onChanged }: { onClose: () => void; onChanged: () => void }) {
  const titleId = useId()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<PasswordErrors>({})
  const [formError, setFormError] = useState<string>()
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const fresh = newPasswordErrors(next, confirm)
    const found: PasswordErrors = {
      ...(!current && { current: 'Enter your current password.' }),
      ...(fresh.password && { next: fresh.password }),
      ...(fresh.confirm && { confirm: fresh.confirm }),
    }
    setErrors(found)
    setFormError(undefined)
    if (Object.keys(found).length > 0) return

    setBusy(true)
    try {
      await auth.changePassword(current, next)
      onChanged()
    } catch (err) {
      if (err instanceof ApiError && err.code === 'invalid_credentials') {
        setErrors({ current: 'That isn’t your current password.' })
      } else if (err instanceof ApiError && err.status === 400 && err.field === 'password') {
        setErrors({ next: sentence(err.message) })
      } else {
        setFormError(genericMessage(err))
      }
      setBusy(false)
    }
  }

  return (
    <Dialog labelledBy={titleId} onClose={busy ? undefined : onClose}>
      <form className={styles.dialog} onSubmit={(e) => void submit(e)} noValidate>
        <h2 id={titleId} className={styles.dialogTitle}>
          Change password
        </h2>
        <Field
          label="Current password"
          type="password"
          autoComplete="current-password"
          value={current}
          onChange={(e) => setCurrent(e.target.value)}
          error={errors.current}
          readOnly={busy}
          autoFocus
        />
        <Field
          label="New password"
          type="password"
          autoComplete="new-password"
          placeholder="At least 8 characters"
          value={next}
          onChange={(e) => setNext(e.target.value)}
          error={errors.next}
          readOnly={busy}
        />
        <Field
          label="Confirm new password"
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
        <div className={styles.forgot}>
          <span className={styles.muted}>Forgot it? An admin can send you a reset link from Settings, or run on the server:</span>
          <Command command="notesd reset-password" tone="dark" />
        </div>
        <div className={styles.dialogActions}>
          <Button size="md" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button variant="solid" size="md" type="submit" busy={busy}>
            {busy ? 'Changing…' : 'Change password'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

type DevicesState = { status: 'loading' | 'ready' | 'error'; sessions: Session[]; currentId: string }

function Devices() {
  const [state, setState] = useState<DevicesState>({ status: 'loading', sessions: [], currentId: '' })
  const [revoking, setRevoking] = useState<string | null>(null)
  const [error, setError] = useState<string>()

  useEffect(() => {
    let cancelled = false
    auth.sessions().then(
      ({ sessions, currentId }) => {
        if (!cancelled) setState({ status: 'ready', sessions: sortSessions(sessions, currentId), currentId })
      },
      () => {
        if (!cancelled) setState((s) => ({ ...s, status: 'error' }))
      },
    )
    return () => {
      cancelled = true
    }
  }, [])

  async function revoke(id: string) {
    setRevoking(id)
    setError(undefined)
    try {
      await auth.revokeSession(id)
      setState((s) => ({ ...s, sessions: s.sessions.filter((x) => x.id !== id) }))
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        setState((s) => ({ ...s, sessions: s.sessions.filter((x) => x.id !== id) }))
      } else setError(genericMessage(err))
    } finally {
      setRevoking(null)
    }
  }

  const n = state.sessions.length
  return (
    <Surface as="section" className={styles.devices} aria-labelledby="devices-kicker">
      <div className={styles.devicesHead}>
        <Kicker id="devices-kicker">Signed-in devices</Kicker>
        {state.status === 'ready' && (
          <span className={styles.muted}>
            {n} {n === 1 ? 'device' : 'devices'}
          </span>
        )}
      </div>
      {state.status === 'loading' && <p className={styles.deviceNote}>Loading…</p>}
      {state.status === 'error' && <p className={styles.deviceNote}>Couldn’t load the list of devices.</p>}
      {error && (
        <p role="alert" className={[styles.deviceNote, styles.error].join(' ')}>
          {error}
        </p>
      )}
      <ul className={styles.deviceList}>
        {state.sessions.map((s) => {
          const current = s.id === state.currentId
          const name = sessionName(s.name, s.kind)
          return (
            <li key={s.id} className={styles.device}>
              <span className={styles.badge} aria-hidden="true">
                {platformBadge(name, s.kind)}
              </span>
              <span className={styles.deviceText}>
                <span className={styles.deviceName}>{name}</span>
                <span className={styles.muted}>
                  {current ? 'Active now' : `Last used ${relativeTime(s.lastUsedAt).toLowerCase()}`}
                </span>
              </span>
              {current ? (
                <span className={styles.here}>This browser</span>
              ) : (
                <Button
                  size="sm"
                  busy={revoking === s.id}
                  onClick={() => void revoke(s.id)}
                  aria-label={`Sign out ${name}`}
                >
                  Sign out
                </Button>
              )}
            </li>
          )
        })}
      </ul>
    </Surface>
  )
}

function sortSessions(sessions: Session[], currentId: string): Session[] {
  return sessions.toSorted((a, b) =>
    a.id === currentId ? -1 : b.id === currentId ? 1 : b.lastUsedAt - a.lastUsedAt,
  )
}
