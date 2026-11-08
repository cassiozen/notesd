import { useEffect, useId, useState } from 'react'
import { sharing } from '../api/spaces'
import type { Account, Space } from '../api/types'
import { useAuth } from '../auth/auth-context'
import { notesSource } from '../notes/source'
import { themeKey } from '../notes/space-theme'
import { cancelInvite, invite, makeOwner, names, removeMember, sharingError, stopSharing } from '../notes/sharing'
import { useNotes } from '../notes/store'
import type { TraitFlowProps } from '../notes/traits'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { ChevronIcon } from '../ui/icons'
import settings from './settings.module.css'
import info from './space-info.module.css'
import styles from './shared-space.module.css'

// Sharing a space (architecture §7.9): Space info's Shared row turns it on by
// inviting someone, and its Manage page lists who's in it.

function useSelf(): number | undefined {
  const { state } = useAuth()
  return 'userId' in state ? state.userId : undefined
}

/** Picks an account on the server to invite; the first invitation shares the space. */
export function InvitePicker({ space, onClose }: TraitFlowProps) {
  const titleId = useId()
  const self = useSelf()
  const { refresh } = useNotes()
  const [people, setPeople] = useState<Account[] | null>(null)
  const [busy, setBusy] = useState<number | null>(null)
  const [error, setError] = useState<string>()
  const shared = space.shared?.members.some((m) => m.id !== self) || (space.shared?.invites?.length ?? 0) > 0

  useEffect(() => {
    let live = true
    sharing.people().then(
      (all) => live && setPeople(all),
      (err: unknown) => {
        if (!live) return
        setPeople([])
        setError(sharingError(err))
      },
    )
    return () => {
      live = false
    }
  }, [])

  const taken = new Set([...(space.shared?.members ?? []).map((m) => m.id), ...(space.shared?.invites ?? []).map((i) => i.userId)])
  const candidates = people?.filter((p) => p.id !== self && !taken.has(p.id)) ?? []

  async function pick(person: Account) {
    setBusy(person.id)
    setError(undefined)
    try {
      await invite(space.id, person.id)
      // Shared just now, the space's notes moved to its own file: the list reads them from there.
      refresh()
      onClose()
    } catch (err) {
      setError(sharingError(err))
      setBusy(null)
    }
  }

  return (
    <Dialog labelledBy={titleId} onClose={onClose} space={themeKey(space.icon)}>
      <div className={settings.dialog}>
        <h2 id={titleId} className={settings.dialogTitle}>
          {shared ? 'Invite someone' : `Share ${space.name}`}
        </h2>
        <p className={settings.dialogText}>
          {shared
            ? `They can read and edit every note in ${space.name} once they join.`
            : `Pick someone to share ${space.name} with. Once they join, they can read and edit every note in it, and add their own.`}
        </p>
        {people === null ? (
          <p className={settings.muted}>Loading…</p>
        ) : candidates.length > 0 ? (
          <ul className={info.group} aria-label="People">
            {candidates.map((p) => (
              <li key={p.id} className={styles.pickRow}>
                <button
                  type="button"
                  className={info.action}
                  disabled={busy !== null}
                  aria-busy={busy === p.id || undefined}
                  onClick={() => void pick(p)}
                >
                  <span className={styles.who}>
                    <Avatar name={p.username} />
                    {p.username}
                  </span>
                  <span className={styles.pickAction}>{busy === p.id ? 'Inviting…' : 'Invite'}</span>
                </button>
              </li>
            ))}
          </ul>
        ) : (
          !error && <p className={settings.muted}>There’s no one else to invite. An admin adds people in Settings › People.</p>
        )}
        {error && (
          <p role="alert" className={settings.error}>
            {error}
          </p>
        )}
        <div className={settings.dialogActions}>
          <Button size="md" onClick={onClose}>
            Cancel
          </Button>
        </div>
      </div>
    </Dialog>
  )
}

type Confirm =
  | { kind: 'remove'; who: Account }
  | { kind: 'owner'; who: Account }
  | { kind: 'stop' }
  | { kind: 'leave'; unsynced: number }

/** Who's in a shared space; its owner invites, removes and hands it on, a member leaves. */
export function SharedSettings({ space, onClose }: TraitFlowProps) {
  const titleId = useId()
  const self = useSelf()
  const { refresh, unsavedIn } = useNotes()
  const [picking, setPicking] = useState(false)
  const [confirm, setConfirm] = useState<Confirm | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string>()
  const members = space.shared?.members ?? []
  const invites = space.shared?.invites ?? []
  const owner = space.shared?.role === 'owner'
  const others = members.filter((m) => m.id !== self)

  async function run(key: string, work: () => Promise<void>) {
    setBusy(key)
    setError(undefined)
    try {
      await work()
      refresh()
    } catch (err) {
      setError(sharingError(err))
    } finally {
      setBusy(null)
    }
  }

  async function askToLeave() {
    setBusy('leave')
    // Saved here but not yet on the server: leaving takes the notes off this device, and these with them.
    const kept = await notesSource()
      .sharing.unsynced(space.id)
      .catch(() => [])
    const unsynced = new Set([...kept, ...unsavedIn(space.id)]).size
    setBusy(null)
    setConfirm({ kind: 'leave', unsynced })
  }

  return (
    <>
      <Dialog labelledBy={titleId} onClose={onClose} space={themeKey(space.icon)}>
        <div className={info.page}>
          <div className={styles.head}>
            <h2 id={titleId} className={settings.dialogTitle}>
              Shared
            </h2>
            <span className={settings.muted}>{space.name}</span>
          </div>
          <ul className={info.group} aria-label="People in this space">
            {members.map((m, i) => (
              <li key={m.id} className={styles.person}>
                <span className={styles.who}>
                  <Avatar name={m.username} />
                  <span className={styles.name}>
                    {m.username}
                    <span className={styles.role}>{[i === 0 && 'Owner', m.id === self && 'You'].filter(Boolean).join(' · ')}</span>
                  </span>
                </span>
                {owner && m.id !== self && (
                  <span className={styles.actions}>
                    <Button size="sm" onClick={() => setConfirm({ kind: 'owner', who: m })} disabled={busy !== null} aria-label={`Make owner: ${m.username}`}>
                      Make owner
                    </Button>
                    <Button size="sm" variant="danger-text" onClick={() => setConfirm({ kind: 'remove', who: m })} disabled={busy !== null} aria-label={`Remove ${m.username}`}>
                      Remove
                    </Button>
                  </span>
                )}
              </li>
            ))}
            {invites.map((inv) => (
              <li key={inv.id} className={styles.person}>
                <span className={styles.who}>
                  <Avatar name={inv.username} pending />
                  <span className={styles.name}>
                    {inv.username}
                    <span className={styles.role}>Invited</span>
                  </span>
                </span>
                {owner && (
                  <span className={styles.actions}>
                    <Button
                      size="sm"
                      busy={busy === `cancel:${inv.id}`}
                      disabled={busy !== null}
                      aria-label={`Cancel ${inv.username}’s invitation`}
                      onClick={() => void run(`cancel:${inv.id}`, () => cancelInvite(space.id, inv.id))}
                    >
                      Cancel
                    </Button>
                  </span>
                )}
              </li>
            ))}
          </ul>
          <div className={info.group}>
            {owner ? (
              <>
                <button type="button" className={info.action} onClick={() => setPicking(true)} disabled={busy !== null}>
                  Invite someone…
                  <span className={info.chevron} aria-hidden="true">
                    <ChevronIcon />
                  </span>
                </button>
                {(others.length > 0 || invites.length > 0) && (
                  <button type="button" className={info.action} data-danger="" onClick={() => setConfirm({ kind: 'stop' })} disabled={busy !== null}>
                    Stop sharing…
                  </button>
                )}
              </>
            ) : (
              <button type="button" className={info.action} data-danger="" onClick={() => void askToLeave()} disabled={busy !== null} aria-busy={busy === 'leave' || undefined}>
                Leave space…
              </button>
            )}
          </div>
          {error && (
            <p role="alert" className={settings.error}>
              {error}
            </p>
          )}
          <div className={settings.dialogActions}>
            <Button size="md" onClick={onClose}>
              Done
            </Button>
          </div>
        </div>
      </Dialog>
      {/* Beside the page, not inside it: a nested dialog's Escape would close both. */}
      {picking && <InvitePicker space={space} onClose={() => setPicking(false)} />}
      {confirm && (
        <ConfirmDialog
          {...confirmText(confirm, space, others.map((m) => m.username))}
          onClose={() => setConfirm(null)}
          onConfirm={async () => {
            switch (confirm.kind) {
              case 'remove':
                return removeMember(space.id, confirm.who.id)
              case 'owner':
                return makeOwner(space.id, confirm.who.id)
              case 'stop':
                return self === undefined ? undefined : stopSharing(space, self)
              case 'leave':
                if (self !== undefined) await removeMember(space.id, self)
            }
          }}
          onDone={(err) => {
            setConfirm(null)
            if (err) setError(sharingError(err))
            else refresh()
          }}
        />
      )}
    </>
  )
}

function confirmText(c: Confirm, space: Space, others: string[]): { title: string; text: string; action: string } {
  switch (c.kind) {
    case 'remove':
      return {
        title: `Remove ${c.who.username}?`,
        text: `${c.who.username} will no longer see the notes in ${space.name}. What they changed stays as it is.`,
        action: 'Remove',
      }
    case 'owner':
      return {
        title: `Make ${c.who.username} the owner?`,
        text: `Only the owner can invite and remove people, rename ${space.name} or delete it. You’ll stay in it, to read and edit its notes.`,
        action: 'Make owner',
      }
    case 'stop':
      return {
        title: `Stop sharing ${space.name}?`,
        text:
          others.length > 0
            ? `${names(others)} will no longer see its notes, and any invitations are cancelled. The space and its notes stay with you.`
            : 'Its invitations are cancelled. The space and its notes stay with you.',
        action: 'Stop sharing',
      }
    case 'leave':
      return {
        title: `Leave ${space.name}?`,
        text:
          `Its notes will be removed from this device. You can join again only if you’re invited again.` +
          (c.unsynced > 0
            ? ` ${c.unsynced === 1 ? '1 note has changes' : `${c.unsynced} notes have changes`} not yet synced, which will be lost.`
            : ''),
        action: 'Leave',
      }
  }
}

function ConfirmDialog({
  title,
  text,
  action,
  onConfirm,
  onClose,
  onDone,
}: {
  title: string
  text: string
  action: string
  onConfirm: () => Promise<void>
  onClose: () => void
  onDone: (err: unknown) => void
}) {
  const titleId = useId()
  const [busy, setBusy] = useState(false)
  return (
    <Dialog labelledBy={titleId} onClose={busy ? undefined : onClose}>
      <div className={settings.dialog}>
        <h2 id={titleId} className={settings.dialogTitle}>
          {title}
        </h2>
        <p className={settings.dialogText}>{text}</p>
        <div className={settings.dialogActions}>
          <Button size="md" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button
            variant="danger"
            size="md"
            busy={busy}
            onClick={() => {
              setBusy(true)
              onConfirm().then(
                () => onDone(null),
                (err: unknown) => onDone(err),
              )
            }}
          >
            {action}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}

function Avatar({ name, pending = false }: { name: string; pending?: boolean }) {
  return (
    <span className={styles.avatar} data-pending={pending ? '' : undefined} aria-hidden="true">
      {[...name][0]?.toUpperCase()}
    </span>
  )
}
