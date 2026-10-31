import { useEffect, useId, useState } from 'react'
import { useAuth } from '../auth/auth-context'
import { genericMessage } from '../auth/errors'
import { deviceDeclined } from '../auth/session-hint'
import { useInstallPrompt } from '../install'
import { relativeTime } from '../notes/relative-time'
import { useNotes } from '../notes/store'
import { useNotesSource, type SyncState } from '../notes/source'
import {
  deviceFailure,
  keepNotesHere,
  stopKeepingNotesHere,
  unsyncedHere,
  useDeviceSupported,
} from '../offline/device'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import { Kicker } from '../ui/kicker'
import { Surface } from '../ui/surface'
import auth from '../auth/auth.module.css'
import styles from './settings.module.css'

/** Whether this browser keeps the signed-in user's notes; follows opting in and out. */
export function useKeptHere(): boolean {
  return useNotesSource().sync !== undefined
}

/** Sync & data: keeping the notes in this browser, and how sync is doing. */
export function DeviceCard({ username }: { username: string }) {
  const kept = useKeptHere()
  const supported = useDeviceSupported()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const [unsynced, setUnsynced] = useState<number | null>(null)
  const failure = deviceFailure()
  const { flushAll } = useNotes()

  async function stop() {
    setBusy(true)
    setError(undefined)
    try {
      // The open note behind Settings may hold typing not yet saved.
      await flushAll()
      const n = await unsyncedHere()
      if (n > 0) setUnsynced(n)
      else await stopKeepingNotesHere()
    } catch (err) {
      setError(genericMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Surface as="section" className={styles.card} aria-labelledby="device-kicker">
      <Kicker id="device-kicker">This device</Kicker>
      <label className={auth.keep}>
        <input
          type="checkbox"
          checked={kept}
          disabled={busy || (!kept && !supported)}
          onChange={(e) => (e.target.checked ? keepNotesHere(username) : void stop())}
        />
        <span>
          Keep my notes on this device
          <span className={auth.keepWhy}>
            They open and save without a connection, and sync when it’s back. Anyone using this browser can read
            them, locked notes aside.
          </span>
        </span>
      </label>
      {!kept && !supported && (
        <span className={styles.muted}>
          This browser can’t keep notes: private windows, and some privacy settings, block the storage it needs.
        </span>
      )}
      {!kept && failure && (
        <p role="alert" className={styles.error}>
          The notes kept here couldn’t be opened ({failure}), so these are the server’s.
        </p>
      )}
      {kept && <SyncRow />}
      {error && (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      )}
      {unsynced !== null && (
        <ForgetDialog
          count={unsynced}
          title="Stop keeping notes here?"
          action="Remove anyway"
          onConfirm={stopKeepingNotesHere}
          onClose={() => setUnsynced(null)}
        />
      )}
    </Surface>
  )
}

/** Installing as an app, while Chrome says it can be. */
export function InstallCard() {
  const install = useInstallPrompt()
  const kept = useKeptHere()
  const supported = useDeviceSupported(install !== null && !kept)
  if (!install) return null
  return (
    <Surface as="section" className={styles.card} aria-labelledby="install-kicker">
      <Kicker id="install-kicker">Install</Kicker>
      <span className={styles.text}>Open notesd in its own window, from the dock or home screen.</span>
      {/* keepIfInstalledApp, on the app's first open. */}
      {!kept && supported && !deviceDeclined() && (
        <span className={styles.muted}>It keeps your notes on this device too, so it opens without a connection.</span>
      )}
      <div>
        <Button size="md" onClick={() => void install()}>
          Install notesd
        </Button>
      </div>
    </Surface>
  )
}

/** "Up to date", "N waiting", "Offline", as on the phones. */
export function syncSummary(state: SyncState | null): string {
  if (!state) return ''
  const { status, pending } = state
  const waiting = pending > 0 ? `${pending} waiting` : ''
  switch (status.kind) {
    case 'Synced':
      return waiting || 'Up to date'
    case 'Syncing':
      return 'Syncing…'
    case 'Offline':
      return waiting ? `Offline · ${waiting}` : 'Offline'
    case 'Failed':
      return waiting ? `Couldn’t sync · ${waiting}` : 'Couldn’t sync'
    case 'SignedOut':
      return 'Sign in again to sync'
    case 'NotConnected':
      return ''
  }
}

/** How sync is doing, kept current; null while nothing's kept here, or until known. */
export function useSyncState(): SyncState | null {
  const sync = useNotesSource().sync
  const [state, setState] = useState<SyncState | null>(null)
  useEffect(() => sync?.onState(setState), [sync])
  return sync ? state : null
}

function SyncRow() {
  const sync = useNotesSource().sync
  const state = useSyncState()
  const status = state?.status
  return (
    <div className={styles.who}>
      <span className={styles.text}>{syncSummary(state)}</span>
      {status?.kind === 'Failed' && <span className={styles.muted}>{status.message}</span>}
      {state?.lastSyncAt != null && (
        <span className={styles.muted}>Last synced {relativeTime(state.lastSyncAt).toLowerCase()}</span>
      )}
      <div>
        <Button size="md" onClick={() => sync?.now()}>
          Sync now
        </Button>
      </div>
    </div>
  )
}

/** Sign out; with notes kept here, removing them, and asking first if some haven't reached the server. */
export function SignOutCard() {
  const { signOut } = useAuth()
  const kept = useKeptHere()
  const [unsynced, setUnsynced] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const { flushAll } = useNotes()

  async function start() {
    setBusy(true)
    try {
      await flushAll().catch(() => undefined)
      const n = await unsyncedHere().catch(() => 0)
      if (n > 0) setUnsynced(n)
      else await signOut()
    } finally {
      setBusy(false)
    }
  }

  return (
    <Surface as="section" className={styles.card} aria-label="Sign out">
      <span className={styles.text}>
        {kept
          ? 'Sign out of this browser. The notes kept here are removed; they stay on the server.'
          : 'Sign out of this browser. Your notes stay on the server.'}
      </span>
      <Button variant="danger" size="md" busy={busy} onClick={() => void start()}>
        Sign out
      </Button>
      {unsynced !== null && (
        <ForgetDialog
          count={unsynced}
          title="Sign out anyway?"
          action="Sign out anyway"
          onConfirm={signOut}
          onClose={() => setUnsynced(null)}
        />
      )}
    </Surface>
  )
}

function ForgetDialog({
  count,
  title,
  action,
  onConfirm,
  onClose,
}: {
  count: number
  title: string
  action: string
  onConfirm: () => Promise<void>
  onClose: () => void
}) {
  const titleId = useId()
  const [busy, setBusy] = useState(false)
  const notes = count === 1 ? '1 note was' : `${count} notes were`
  return (
    <Dialog labelledBy={titleId} onClose={onClose}>
      <div className={styles.dialog}>
        <h2 id={titleId} className={styles.dialogTitle}>
          {title}
        </h2>
        <p className={styles.dialogText}>
          {notes} changed in this browser and haven’t reached the server yet. Removing the notes kept here loses those
          changes.
        </p>
        <div className={styles.dialogActions}>
          <Button size="md" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button
            variant="danger"
            size="md"
            busy={busy}
            onClick={() => {
              setBusy(true)
              // Best effort, as on the phones: the screens leave this device's notes either way.
              void onConfirm()
                .catch(() => undefined)
                .finally(onClose)
            }}
          >
            {action}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
