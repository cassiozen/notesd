import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent as ReactKeyboardEvent,
} from 'react'
import { ApiError } from '../api/client'
import { nameOf } from '../api/profile'
import type { Person, SetupLink } from '../api/types'
import { users } from '../api/users'
import { useAccount, useAuth } from '../auth/auth-context'
import { genericMessage } from '../auth/errors'
import { Avatar } from '../ui/avatar'
import { Banner } from '../ui/banner'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { Field } from '../ui/field'
import { MoreIcon } from '../ui/icons'
import { Kicker } from '../ui/kicker'
import { useCopy } from '../ui/copy'
import { Surface } from '../ui/surface'
import { isLoopback, linkExpiry, setupLinkURL } from './people-link'
import menu from '../notes/share.module.css'
import styles from './settings.module.css'

/** hidden: not an admin, as the server says (another admin may have changed it since sign-in). */
type Load = { status: 'loading' } | { status: 'error' } | { status: 'hidden' } | { status: 'ready'; people: Person[] }

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
  const me = useAccount().profile
  const { profileChanged } = useAuth()
  const [load, setLoad] = useState<Load>({ status: 'loading' })
  const [open, setOpen] = useState<Open | null>(null)
  const [busy, setBusy] = useState<number | null>(null)
  const [error, setError] = useState<string>()

  // The list is fresher than the session: your name or photo may have changed on another device since.
  const loaded = useCallback(
    (next: Load) => {
      const self = next.status === 'ready' ? next.people.find((p) => p.username === username) : undefined
      if (self) profileChanged(self)
      setLoad(next)
    },
    [profileChanged, username],
  )

  useEffect(() => {
    let cancelled = false
    void fetchPeople().then((next) => {
      if (!cancelled) loaded(next)
    })
    return () => {
      cancelled = true
    }
  }, [loaded])

  function retry() {
    setLoad({ status: 'loading' })
    void fetchPeople().then(loaded)
  }

  const updatePeople = (change: (people: Person[]) => Person[]) =>
    setLoad((l) => (l.status === 'ready' ? { ...l, people: change(l.people) } : l))
  const replace = (p: Person) => updatePeople((ps) => ps.map((x) => (x.id === p.id ? p : x)))
  const drop = (id: number) => updatePeople((ps) => ps.filter((x) => x.id !== id))
  const hide = () => setLoad({ status: 'hidden' })

  async function act(p: Person, run: () => Promise<void>) {
    setBusy(p.id)
    setError(undefined)
    try {
      await run()
    } catch (err) {
      if (isAdminOnly(err)) hide()
      else if (err instanceof ApiError && err.status === 404) drop(p.id)
      else setError(actionMessage(err))
    } finally {
      setBusy(null)
    }
  }

  // The account comes back as it is now: this list may be from before they chose a password.
  const newLink = (p: Person) =>
    act(p, async () => {
      const { user, link } = await users.newLink(p.id)
      replace(user)
      setOpen({ kind: 'link', person: user, link })
    })

  // Fresh first: a stale "invited" would describe removing someone who has since joined as cancelling an invitation.
  const confirmRemove = (p: Person) =>
    act(p, async () => {
      const { users: latest } = await users.list()
      loaded({ status: 'ready', people: latest })
      const fresh = latest.find((x) => x.id === p.id)
      if (fresh) setOpen({ kind: 'remove', person: fresh })
    })

  const remove = (p: Person) =>
    act(p, async () => {
      await users.remove(p.id)
      drop(p.id)
    })

  const setAdmin = (p: Person, isAdmin: boolean) => act(p, async () => replace(await users.setAdmin(p.id, isAdmin)))

  if (load.status === 'hidden') return null
  const people = load.status === 'ready' ? sortPeople(load.people, username) : []
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
        <Banner
          tone="error"
          actions={
            <Button size="sm" onClick={retry}>
              Try again
            </Button>
          }
        >
          Couldn’t load who uses this server.
        </Banner>
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
          {people.map((listed) => {
            const you = listed.username === username
            // Yours as just edited in this page's Name and photo, not as the list was loaded.
            const p = you && me ? { ...listed, displayName: me.displayName, avatar: me.avatar } : listed
            return (
              <li key={p.id} className={styles.device} aria-busy={busy === p.id || undefined}>
                <Avatar userId={p.id} version={p.avatar} name={nameOf(p)} className={[styles.badge, styles.initial].join(' ')} />
                <span className={styles.deviceText}>
                  <span className={styles.deviceName}>{nameOf(p)}</span>
                  <span className={styles.muted}>{p.displayName ? `${p.username} · ${describe(p)}` : describe(p)}</span>
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
            updatePeople((ps) => [...ps, person])
            setOpen({ kind: 'link', person, link })
          }}
          onForbidden={hide}
        />
      )}
      {open?.kind === 'link' && <LinkDialog person={open.person} link={open.link} onClose={() => setOpen(null)} />}
      {open?.kind === 'reset' && (
        <Confirm
          title={`Reset ${nameOf(open.person)}’s password?`}
          text="You’ll get a link to send them, where they choose a new one. Nothing changes until they use it."
          action="Get link"
          onCancel={() => setOpen(null)}
          onConfirm={() => {
            setOpen(null)
            void newLink(open.person)
          }}
        />
      )}
      {open?.kind === 'remove' && (
        <Confirm
          {...(open.person.pending
            ? {
                title: `Cancel ${nameOf(open.person)}’s invitation?`,
                text: 'Their link stops working, and the username is free again.',
                action: 'Cancel invitation',
                cancel: 'Keep',
              }
            : {
                title: `Remove ${nameOf(open.person)}?`,
                text: 'They’re signed out on every device and can’t sign in again. Their notes stay on the server’s disk, but no app can open them.',
                action: 'Remove',
              })}
          danger
          onCancel={() => setOpen(null)}
          onConfirm={() => {
            setOpen(null)
            void remove(open.person)
          }}
        />
      )}
    </Surface>
  )
}

async function fetchPeople(): Promise<Load> {
  try {
    const { users: people } = await users.list()
    return { status: 'ready', people }
  } catch (err) {
    return { status: isAdminOnly(err) ? 'hidden' : 'error' }
  }
}

/** You first, then everyone in the order they were added. */
function sortPeople(people: Person[], you: string): Person[] {
  return people.toSorted((a, b) => (a.username === you ? -1 : b.username === you ? 1 : a.id - b.id))
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

  const label = `Actions for ${nameOf(person)}`
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
  const [username, setUsername] = useState('')
  const [name, setName] = useState('')
  const [error, setError] = useState<string>()
  const [nameError, setNameError] = useState<string>()
  const [formError, setFormError] = useState<string>()
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setFormError(undefined)
    setNameError(undefined)
    if (!username.trim()) {
      setError('Enter the username they’ll sign in with.')
      return
    }
    setError(undefined)
    setBusy(true)
    try {
      const { user, link } = await users.invite(username.trim(), name.trim())
      onInvited(user, link)
    } catch (err) {
      setBusy(false)
      if (isAdminOnly(err)) onForbidden()
      else if (err instanceof ApiError && err.code === 'username_taken') setError('Someone already has that username.')
      else if (err instanceof ApiError && err.field === 'username') setError('Use 1 to 64 characters, on one line.')
      else if (err instanceof ApiError && err.field === 'displayName') setNameError('Use at most 64 characters, on one line.')
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
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          error={error}
          hint="What they’ll sign in with."
          readOnly={busy}
          autoFocus
        />
        <Field
          label="Name (optional)"
          autoComplete="off"
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={nameError}
          hint="Shown instead of their username. They can change it when they open the link."
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
  const [copied, copy] = useCopy()
  const field = useRef<HTMLInputElement>(null)

  return (
    <Dialog labelledBy={titleId} onClose={onClose}>
      <div className={styles.dialog}>
        <h2 id={titleId} className={styles.dialogTitle}>
          Send {nameOf(person)} this link
        </h2>
        <p className={styles.dialogText}>
          {person.pending
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
              void copy(url).then((ok) => {
                // Selected, for ⌘C, if the browser refused.
                if (!ok) field.current?.select()
              })
            }}
          >
            {copied ? 'Copied' : 'Copy'}
          </Button>
        </div>
        {isLoopback() && (
          <Banner tone="warn">
            This link uses {window.location.host}, so it only works on this computer. Open notesd at the address others
            reach it by, then make a new link.
          </Banner>
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

function Confirm({
  title,
  text,
  action,
  cancel = 'Cancel',
  danger = false,
  onCancel,
  onConfirm,
}: {
  title: string
  text: string
  action: string
  cancel?: string
  danger?: boolean
  onCancel: () => void
  onConfirm: () => void
}) {
  const titleId = useId()
  return (
    <Dialog labelledBy={titleId} onClose={onCancel}>
      <div className={styles.dialog}>
        <h2 id={titleId} className={styles.dialogTitle}>
          {title}
        </h2>
        <p className={styles.dialogText}>{text}</p>
        <div className={styles.dialogActions}>
          <Button size="md" onClick={onCancel}>
            {cancel}
          </Button>
          <Button variant={danger ? 'danger' : 'solid'} size="md" onClick={onConfirm} autoFocus={!danger}>
            {action}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
