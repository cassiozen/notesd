import { useEffect, useId, useState } from 'react'
import { sharing } from '../api/spaces'
import type { Account, Space } from '../api/types'
import { useAuth } from '../auth/auth-context'
import { notesSource } from '../notes/source'
import { cancelInvite, invite, makeOwner, names, removeMember, sharingError, stopSharing } from '../notes/sharing'
import { useNotes } from '../notes/store'
import { Button } from '../ui/button'
import { InlineConfirm } from '../ui/inline-confirm'
import info from './space-info.module.css'
import styles from './shared-space.module.css'

// Sharing a space (architecture §7.9), as Space info's People section: who's in it, who's invited, and everyone
// else on the server to invite with one press, all changed in place.

function useSelf(): number | undefined {
  const { state } = useAuth()
  return 'userId' in state ? state.userId : undefined
}

/** The accounts on the server, for the owner to invite; null until they're read. Read once per page. */
function usePeople(wanted: boolean, onError: (message: string) => void): Account[] | null {
  const [people, setPeople] = useState<Account[] | null>(null)
  useEffect(() => {
    if (!wanted) return
    let live = true
    sharing.people().then(
      (all) => live && setPeople(all),
      (err: unknown) => {
        if (!live) return
        setPeople([])
        onError(sharingError(err))
      },
    )
    return () => {
      live = false
    }
  }, [wanted, onError])
  return people
}

type Confirm = { kind: 'remove'; who: Account } | { kind: 'owner'; who: Account } | { kind: 'stop' } | { kind: 'leave'; unsynced: number }

/**
 * Who's in a space. Its owner invites by pressing a name (the first invitation shares it), cancels invitations,
 * removes people, hands it on and stops sharing; a member leaves. Each confirmation opens under what asked for it.
 */
export function PeopleSection({ space }: { space: Space }) {
  const headingId = useId()
  const self = useSelf()
  const { refresh, unsavedIn, flushAll } = useNotes()
  const [confirm, setConfirm] = useState<Confirm | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string>()
  // Just invited: their row arrives with a highlight, so it's seen where it landed.
  const [fresh, setFresh] = useState<number | null>(null)
  const owner = space.shared?.role !== 'member'
  const members = space.shared?.members ?? []
  const invites = space.shared?.invites ?? []
  const others = members.filter((m) => m.id !== self)
  const shared = others.length > 0 || invites.length > 0
  const people = usePeople(owner, setError)
  const taken = new Set([...members.map((m) => m.id), ...invites.map((i) => i.userId)])
  const candidates = people?.filter((p) => p.id !== self && !taken.has(p.id)) ?? []

  async function run(key: string, work: () => Promise<unknown>): Promise<boolean> {
    setBusy(key)
    setError(undefined)
    try {
      await work()
      refresh()
      return true
    } catch (err) {
      setError(sharingError(err))
      return false
    } finally {
      setBusy(null)
    }
  }

  async function pick(person: Account) {
    // Shared just now, the space's notes moved to its own file: refresh() has the list read them from there.
    if (await run(`invite:${person.id}`, () => invite(space.id, person.id))) setFresh(person.id)
  }

  async function askToLeave() {
    setBusy('leave')
    // Saved here but not yet on the server: leaving keeps these as copies in the account's own space.
    const kept = await notesSource()
      .sharing.unsynced(space.id)
      .catch(() => [])
    const unsynced = new Set([...kept, ...unsavedIn(space.id)]).size
    setBusy(null)
    setConfirm({ kind: 'leave', unsynced })
  }

  function confirmed(c: Confirm) {
    void run('confirm', async () => {
      switch (c.kind) {
        case 'remove':
          return removeMember(space.id, c.who.id)
        case 'owner':
          return makeOwner(space.id, c.who.id)
        case 'stop':
          return self === undefined ? undefined : stopSharing(space, self)
        case 'leave':
          // What can still reach the server goes first; the rest the sync engine keeps as copies.
          await flushAll()
          await notesSource()
            .sharing.settle()
            .catch(() => {})
          if (self !== undefined) await removeMember(space.id, self)
      }
    }).then(() => setConfirm(null))
  }

  const strip = (c: Confirm) => {
    const words = confirmText(c, space, others.map((m) => m.username))
    return (
      <InlineConfirm title={words.title} action={words.action} busy={busy === 'confirm'} onConfirm={() => confirmed(c)} onCancel={() => setConfirm(null)}>
        {words.text}
      </InlineConfirm>
    )
  }
  const asking = (kind: Confirm['kind'], who?: Account) =>
    confirm?.kind === kind && (who === undefined || ('who' in confirm && confirm.who.id === who.id))

  return (
    <section className={info.section} aria-labelledby={headingId}>
      <h3 id={headingId} className={info.caption}>
        People
      </h3>
      <div className={info.group}>
        {shared ? (
          <ul className={styles.people} aria-label="People in this space">
            {members.map((m, i) => (
              <li key={m.id} className={styles.person}>
                <div className={styles.line}>
                  <Who name={m.username} role={[i === 0 && 'Owner', m.id === self && 'You'].filter(Boolean).join(' · ')} />
                  {owner && m.id !== self && (
                    <span className={styles.actions}>
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy !== null}
                        aria-expanded={asking('owner', m)}
                        aria-label={`Make owner: ${m.username}`}
                        onClick={() => setConfirm({ kind: 'owner', who: m })}
                      >
                        Make owner
                      </Button>
                      <Button
                        size="sm"
                        variant="danger-text"
                        disabled={busy !== null}
                        aria-expanded={asking('remove', m)}
                        aria-label={`Remove ${m.username}`}
                        onClick={() => setConfirm({ kind: 'remove', who: m })}
                      >
                        Remove
                      </Button>
                    </span>
                  )}
                </div>
                {confirm && 'who' in confirm && confirm.who.id === m.id && strip(confirm)}
              </li>
            ))}
            {invites.map((inv) => (
              <li key={inv.id} className={styles.person} data-fresh={inv.userId === fresh ? '' : undefined}>
                <div className={styles.line}>
                  <Who name={inv.username} role="Invited" pending />
                  {owner && (
                    <span className={styles.actions}>
                      <Button
                        size="sm"
                        variant="ghost"
                        busy={busy === `cancel:${inv.id}`}
                        disabled={busy !== null}
                        aria-label={`Cancel ${inv.username}’s invitation`}
                        onClick={() => void run(`cancel:${inv.id}`, () => cancelInvite(space.id, inv.id))}
                      >
                        Cancel
                      </Button>
                    </span>
                  )}
                </div>
              </li>
            ))}
          </ul>
        ) : (
          <p className={styles.lead}>Invite people to read and edit every note in {space.name}, and add their own.</p>
        )}
        {owner && people !== null && (candidates.length > 0 || !shared) && (
          <div className={styles.invite}>
            {candidates.length > 0 ? (
              <ul className={styles.chips} aria-label="People to invite">
                {/* With people listed above, what these do needs saying; alone, the sentence above says it. */}
                {shared && (
                  <li className={styles.inviteLabel} aria-hidden="true">
                    Invite
                  </li>
                )}
                {candidates.map((p) => (
                  <li key={p.id}>
                    <button
                      type="button"
                      className={styles.chip}
                      disabled={busy !== null}
                      aria-busy={busy === `invite:${p.id}` || undefined}
                      aria-label={`Invite ${p.username}`}
                      onClick={() => void pick(p)}
                    >
                      <Avatar name={p.username} small />
                      {p.username}
                      <span className={styles.plus} aria-hidden="true">
                        +
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            ) : (
              !error && <p className={styles.none}>There’s no one else on this server yet. An admin adds people in Settings › Server › People.</p>
            )}
          </div>
        )}
      </div>
      {error && (
        <p role="alert" className={info.error}>
          {error}
        </p>
      )}
      {(owner ? shared : true) && (
        <div className={info.footer}>
          {owner ? (
            <Button size="sm" variant="danger-text" disabled={busy !== null} aria-expanded={asking('stop')} onClick={() => setConfirm({ kind: 'stop' })}>
              Stop sharing…
            </Button>
          ) : (
            <Button
              size="sm"
              variant="danger-text"
              disabled={busy !== null}
              busy={busy === 'leave'}
              aria-expanded={asking('leave')}
              onClick={() => void askToLeave()}
            >
              Leave space…
            </Button>
          )}
          {confirm && (confirm.kind === 'stop' || confirm.kind === 'leave') && strip(confirm)}
        </div>
      )}
    </section>
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
            ? ` ${c.unsynced === 1 ? '1 note has changes' : `${c.unsynced} notes have changes`} not yet synced: they’re sent first, or kept as copies in your own notes if they can’t be.`
            : ''),
        action: 'Leave',
      }
  }
}

function Who({ name, role, pending = false }: { name: string; role: string; pending?: boolean }) {
  return (
    <span className={styles.who}>
      <Avatar name={name} pending={pending} />
      <span className={styles.name}>
        {name}
        <span className={styles.role}>{role}</span>
      </span>
    </span>
  )
}

function Avatar({ name, pending = false, small = false }: { name: string; pending?: boolean; small?: boolean }) {
  return (
    <span className={styles.avatar} data-pending={pending ? '' : undefined} data-small={small ? '' : undefined} aria-hidden="true">
      {[...name][0]?.toUpperCase()}
    </span>
  )
}
