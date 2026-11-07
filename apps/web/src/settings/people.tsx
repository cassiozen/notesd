import {
  useEffect,
  useId,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent as ReactKeyboardEvent,
} from 'react'
import { ApiError } from '../api/client'
import type { Person, SetupLink } from '../api/types'
import { users } from '../api/users'
import { useAuth } from '../auth/auth-context'
import { genericMessage } from '../auth/errors'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { Field } from '../ui/field'
import { MoreIcon } from '../ui/icons'
import { Kicker } from '../ui/kicker'
import { copyText } from '../ui/copy'
import { Surface } from '../ui/surface'
import { isLoopback, linkExpiry, setupLinkURL } from './people-link'
import menu from '../notes/share.module.css'
import styles from './settings.module.css'

type Load = { status: 'loading' } | { status: 'error' } | { status: 'ready'; people: Person[] }

/** Who else uses this server: shown to admins only, everyone else never sees it. */
export function PeopleCard({ username }: { username: string }) {
  const { state } = useAuth()
  const admin = state.status === 'signed-in' && state.isAdmin === true && !state.offline
  return admin ? <People username={username} /> : null
}

type Open =
  | { kind: 'invite' }
  | { kind: 'link'; person: Person; link: SetupLink }
  | { kind: 'reset'; person: Person }
  | { kind: 'remove'; person: Person }

function People({ username }: { username: string }) {
  const [load, setLoad] = useState<Load>({ status: 'loading' })
  const [open, setOpen] = useState<Open | null>(null)
  const [busy, setBusy] = useState<number | null>(null)
  const [error, setError] = useState<string>()
  // Another admin took this one's away since sign-in: the server says so, and the card goes.
  const [gone, setGone] = useState(false)

  useEffect(() => {
    let cancelled = false
    void fetchPeople().then((next) => {
      if (cancelled) return
      if (next) setLoad(next)
      else setGone(true)
    })
    return () => {
      cancelled = true
    }
  }, [])

  function retry() {
    setLoad({ status: 'loading' })
    void fetchPeople().then((next) => {
      if (next) setLoad(next)
      else setGone(true)
    })
  }

  const people = load.status === 'ready' ? sortPeople(load.people, username) : []
  const replace = (p: Person) =>
    setLoad((l) => (l.status === 'ready' ? { ...l, people: l.people.map((x) => (x.id === p.id ? p : x)) } : l))
  const drop = (id: number) =>
    setLoad((l) => (l.status === 'ready' ? { ...l, people: l.people.filter((x) => x.id !== id) } : l))

  async function act(p: Person, run: () => Promise<void>) {
    setBusy(p.id)
    setError(undefined)
    try {
      await run()
    } catch (err) {
      if (isAdminOnly(err)) setGone(true)
      else if (err instanceof ApiError && err.status === 404) drop(p.id)
      else setError(actionMessage(err))
    } finally {
      setBusy(null)
    }
  }

  const newLink = (p: Person) =>
    act(p, async () => {
      const link = await users.newLink(p.id)
      // The server's word on pending: this list may be from before they chose a password.
      const fresh = { ...p, pending: link.pending, linkExpiresAt: link.expiresAt }
      replace(fresh)
      setOpen({ kind: 'link', person: fresh, link })
    })

  // Fresh first: a stale "invited" would describe removing someone who has since joined as cancelling an invitation.
  const confirmRemove = (p: Person) =>
    act(p, async () => {
      const { users: latest } = await users.list()
      setLoad({ status: 'ready', people: latest })
      const fresh = latest.find((x) => x.id === p.id)
      if (fresh) setOpen({ kind: 'remove', person: fresh })
    })

  const setAdmin = (p: Person, isAdmin: boolean) => act(p, async () => replace(await users.setAdmin(p.id, isAdmin)))

  if (gone) return null
  const n = people.length
  return (
    <Surface as="section" className={styles.devices} aria-labelledby="people-kicker">
      <div className={styles.devicesHead}>
        <Kicker id="people-kicker">People</Kicker>
        <Button size="sm" onClick={() => setOpen({ kind: 'invite' })} disabled={load.status !== 'ready'}>
          Invite someone…
        </Button>
      </div>
      {load.status === 'loading' && <p className={styles.deviceNote}>Loading…</p>}
      {load.status === 'error' && (
        <div className={[styles.deviceNote, styles.retryNote].join(' ')}>
          <span role="alert">Couldn’t load who uses this server.</span>
          <Button size="sm" onClick={retry}>
            Try again
          </Button>
        </div>
      )}
      {load.status === 'ready' && n <= 1 && (
        <p className={styles.deviceNote}>
          Only you use this server. Invite family or friends: each gets their own notes, which no one else can see.
        </p>
      )}
      {error && (
        <p role="alert" className={[styles.deviceNote, styles.error].join(' ')}>
          {error}
        </p>
      )}
      {n > 1 && (
        <ul className={styles.deviceList} aria-label="People">
          {people.map((p) => {
            const you = p.username === username
            return (
              <li key={p.id} className={styles.device} aria-busy={busy === p.id || undefined}>
                <span className={[styles.badge, styles.initial].join(' ')} aria-hidden="true">
                  {initial(p.username)}
                </span>
                <span className={styles.deviceText}>
                  <span className={styles.deviceName}>{p.username}</span>
                  <span className={styles.muted}>{describe(p)}</span>
                </span>
                {you ? (
                  <span className={styles.here}>You</span>
                ) : (
                  <PersonMenu
                    person={p}
                    disabled={busy !== null}
                    onNewLink={() => void newLink(p)}
                    onReset={() => setOpen({ kind: 'reset', person: p })}
                    onAdmin={(isAdmin) => void setAdmin(p, isAdmin)}
                    onRemove={() => void confirmRemove(p)}
                  />
                )}
              </li>
            )
          })}
        </ul>
      )}
      {open?.kind === 'invite' && (
        <InviteDialog
          onClose={() => setOpen(null)}
          onInvited={(person, link) => {
            setLoad((l) => (l.status === 'ready' ? { ...l, people: [...l.people, person] } : l))
            setOpen({ kind: 'link', person, link })
          }}
          onForbidden={() => setGone(true)}
        />
      )}
      {open?.kind === 'link' && <LinkDialog person={open.person} link={open.link} onClose={() => setOpen(null)} />}
      {open?.kind === 'reset' && (
        <ResetDialog
          person={open.person}
          onClose={() => setOpen(null)}
          onConfirm={() => {
            const p = open.person
            setOpen(null)
            void newLink(p)
          }}
        />
      )}
      {open?.kind === 'remove' && (
        <RemoveDialog
          person={open.person}
          onClose={() => setOpen(null)}
          onRemoved={() => {
            drop(open.person.id)
            setOpen(null)
          }}
          onForbidden={() => setGone(true)}
        />
      )}
    </Surface>
  )
}

/** null: not an admin, as the server says. */
async function fetchPeople(): Promise<Load | null> {
  try {
    const { users: people } = await users.list()
    return { status: 'ready', people }
  } catch (err) {
    return isAdminOnly(err) ? null : { status: 'error' }
  }
}

/** You first, then everyone in the order they were added. */
function sortPeople(people: Person[], you: string): Person[] {
  return people.toSorted((a, b) => (a.username === you ? -1 : b.username === you ? 1 : a.id - b.id))
}

function initial(name: string): string {
  return (Array.from(name.trim())[0] ?? '?').toUpperCase()
}

function describe(p: Person): string {
  if (p.pending) return `Invited · ${linkExpiry(p.linkExpiresAt)}`
  const joined = `Joined ${new Date(p.createdAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })}`
  return p.isAdmin ? `Admin · ${joined}` : joined
}

function isAdminOnly(err: unknown): boolean {
  return err instanceof ApiError && err.code === 'admin_only'
}

function actionMessage(err: unknown): string {
  if (err instanceof ApiError && err.code === 'last_admin') {
    return 'The server needs an admin who can sign in. Make someone else an admin first.'
  }
  return genericMessage(err)
}

/** A person's actions, behind a "…" button at the end of their row. */
function PersonMenu({
  person,
  disabled,
  onNewLink,
  onReset,
  onAdmin,
  onRemove,
}: {
  person: Person
  disabled: boolean
  onNewLink: () => void
  onReset: () => void
  onAdmin: (isAdmin: boolean) => void
  onRemove: () => void
}) {
  const [open, setOpen] = useState(false)
  const menuId = useId()
  const anchor = useRef<HTMLSpanElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const items = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    items.current?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus()
    const outside = (e: Event) => {
      if (!anchor.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', outside)
    return () => document.removeEventListener('mousedown', outside)
  }, [open])

  /** Focus back on the trigger, so a dialog an item opens returns it there when it closes. */
  const choose = (fn: () => void) => () => {
    setOpen(false)
    trigger.current?.focus()
    fn()
  }

  const onKey = (e: ReactKeyboardEvent) => {
    const list = Array.from(items.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]') ?? [])
    const at = list.indexOf(document.activeElement as HTMLButtonElement)
    const go = (i: number) => {
      e.preventDefault()
      list[(i + list.length) % list.length]?.focus()
    }
    if (e.key === 'ArrowDown') go(at + 1)
    else if (e.key === 'ArrowUp') go(at - 1)
    else if (e.key === 'Home') go(0)
    else if (e.key === 'End') go(list.length - 1)
    else if (e.key === 'Escape' || e.key === 'Tab') {
      if (e.key === 'Escape') e.preventDefault()
      setOpen(false)
      trigger.current?.focus()
    }
  }

  const label = `Actions for ${person.username}`
  return (
    <span ref={anchor} className={menu.anchor}>
      <button
        ref={trigger}
        type="button"
        className={menu.button}
        aria-label={label}
        title="More"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        disabled={disabled}
        onClick={() => setOpen((v) => !v)}
      >
        <MoreIcon />
      </button>
      {open && (
        <div ref={items} id={menuId} role="menu" aria-label={label} className={menu.menu} onKeyDown={onKey}>
          {person.pending ? (
            <button type="button" role="menuitem" className={menu.item} onClick={choose(onNewLink)}>
              New invite link
            </button>
          ) : (
            <>
              <button type="button" role="menuitem" className={menu.item} onClick={choose(onReset)}>
                Reset password…
              </button>
              <button type="button" role="menuitem" className={menu.item} onClick={choose(() => onAdmin(!person.isAdmin))}>
                {person.isAdmin ? 'Remove admin' : 'Make admin'}
              </button>
            </>
          )}
          <div role="separator" className={menu.separator} />
          <button
            type="button"
            role="menuitem"
            className={[menu.item, menu.danger].join(' ')}
            onClick={choose(onRemove)}
          >
            {person.pending ? 'Cancel invitation…' : 'Remove from server…'}
          </button>
        </div>
      )}
    </span>
  )
}

function InviteDialog({
  onClose,
  onInvited,
  onForbidden,
}: {
  onClose: () => void
  onInvited: (person: Person, link: SetupLink) => void
  onForbidden: () => void
}) {
  const titleId = useId()
  const [name, setName] = useState('')
  const [error, setError] = useState<string>()
  const [formError, setFormError] = useState<string>()
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setFormError(undefined)
    if (!name.trim()) {
      setError('Enter the username they’ll sign in with.')
      return
    }
    setError(undefined)
    setBusy(true)
    try {
      const { user, link } = await users.invite(name.trim())
      onInvited(user, link)
    } catch (err) {
      setBusy(false)
      if (isAdminOnly(err)) onForbidden()
      else if (err instanceof ApiError && err.code === 'username_taken') setError('Someone already has that username.')
      else if (err instanceof ApiError && err.field === 'username') setError('Use 1 to 64 characters, on one line.')
      else setFormError(genericMessage(err))
    }
  }

  return (
    <Dialog labelledBy={titleId} onClose={busy ? undefined : onClose}>
      <form className={styles.dialog} onSubmit={(e) => void submit(e)} noValidate>
        <h2 id={titleId} className={styles.dialogTitle}>
          Invite someone
        </h2>
        <p className={styles.dialogText}>
          They get their own notes, which no one else can see. Next, you’ll get a link to send them, where they choose
          their password.
        </p>
        <Field
          label="Username"
          autoComplete="off"
          autoCapitalize="none"
          spellCheck={false}
          maxLength={64}
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={error}
          hint="What they’ll sign in with."
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
          <Button variant="solid" size="md" type="submit" busy={busy}>
            Invite
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

/** The link to send, once: only its hash is kept, so it can't be shown again later. */
function LinkDialog({ person, link, onClose }: { person: Person; link: SetupLink; onClose: () => void }) {
  const titleId = useId()
  const url = setupLinkURL(link.token)
  const [copied, setCopied] = useState(false)
  const field = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (!copied) return
    const t = setTimeout(() => setCopied(false), 1600)
    return () => clearTimeout(t)
  }, [copied])

  return (
    <Dialog labelledBy={titleId} onClose={onClose}>
      <div className={styles.dialog}>
        <h2 id={titleId} className={styles.dialogTitle}>
          Send {person.username} this link
        </h2>
        <p className={styles.dialogText}>
          {link.pending
            ? 'They open it to choose a password, and they’re signed in.'
            : 'They open it to choose a new password, which signs them out everywhere. Until then, their current password still works.'}{' '}
          It works once, for 7 days. Send it privately: whoever has it can set the password.
        </p>
        <div className={styles.linkRow}>
          <Field
            ref={field}
            label="Link"
            hideLabel
            mono
            readOnly
            value={url}
            onFocus={(e) => e.currentTarget.select()}
            autoFocus
          />
          <Button
            size="md"
            onClick={() => {
              void copyText(url).then((ok) => {
                setCopied(ok)
                // Selected, for ⌘C, if the browser refused.
                if (!ok) field.current?.select()
              })
            }}
          >
            {copied ? 'Copied' : 'Copy'}
          </Button>
        </div>
        {isLoopback() && (
          <p className={styles.warn}>
            This link uses {window.location.host}, so it only works on this computer. Open notesd at the address others
            reach it by, then make a new link.
          </p>
        )}
        <div className={styles.dialogActions}>
          <Button variant="solid" size="md" onClick={onClose}>
            Done
          </Button>
        </div>
      </div>
    </Dialog>
  )
}

function ResetDialog({ person, onClose, onConfirm }: { person: Person; onClose: () => void; onConfirm: () => void }) {
  const titleId = useId()
  return (
    <Dialog labelledBy={titleId} onClose={onClose}>
      <div className={styles.dialog}>
        <h2 id={titleId} className={styles.dialogTitle}>
          Reset {person.username}’s password?
        </h2>
        <p className={styles.dialogText}>
          You’ll get a link to send them, where they choose a new one. Nothing changes until they use it.
        </p>
        <div className={styles.dialogActions}>
          <Button size="md" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="solid" size="md" onClick={onConfirm} autoFocus>
            Get link
          </Button>
        </div>
      </div>
    </Dialog>
  )
}

function RemoveDialog({
  person,
  onClose,
  onRemoved,
  onForbidden,
}: {
  person: Person
  onClose: () => void
  onRemoved: () => void
  onForbidden: () => void
}) {
  const titleId = useId()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()

  async function remove() {
    setBusy(true)
    setError(undefined)
    try {
      await users.remove(person.id)
      onRemoved()
    } catch (err) {
      setBusy(false)
      if (isAdminOnly(err)) onForbidden()
      else if (err instanceof ApiError && err.status === 404) onRemoved()
      else setError(actionMessage(err))
    }
  }

  return (
    <Dialog labelledBy={titleId} onClose={busy ? undefined : onClose}>
      <div className={styles.dialog}>
        <h2 id={titleId} className={styles.dialogTitle}>
          {person.pending ? `Cancel ${person.username}’s invitation?` : `Remove ${person.username}?`}
        </h2>
        <p className={styles.dialogText}>
          {person.pending
            ? 'Their link stops working, and the username is free again.'
            : 'They’re signed out on every device and can’t sign in again. Their notes stay on the server’s disk, but no app can open them.'}
        </p>
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
        <div className={styles.dialogActions}>
          <Button size="md" onClick={onClose} disabled={busy}>
            {person.pending ? 'Keep' : 'Cancel'}
          </Button>
          <Button variant="danger" size="md" busy={busy} onClick={() => void remove()}>
            {person.pending ? 'Cancel invitation' : 'Remove'}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
