import { useEffect, useState, useSyncExternalStore } from 'react'
import { versionState } from '../api/version'
import { useNotesSource } from './source'
import styles from './note-list.module.css'

export const UPGRADE_REQUIRED =
  'Your notesd server needs a newer version of this app. Update notesd to keep syncing; your changes are kept here.'

export function parkedText(n: number): string {
  return n === 1 ? '1 note couldn’t sync. It’s kept on this device.' : `${n} notes couldn’t sync. They’re kept on this device.`
}

/** What only an update or a reload fixes, and notes sync set aside; in the list, beside "not synced". */
export function SyncBanners() {
  const version = useSyncExternalStore(versionState.subscribe, versionState.get)
  const sync = useNotesSource().sync
  const [engineUpgrade, setEngineUpgrade] = useState(false)
  const [parked, setParked] = useState(0)
  const [retrying, setRetrying] = useState(false)

  useEffect(() => sync?.onState((s) => setEngineUpgrade(s.status.kind === 'UpgradeRequired')), [sync])
  useEffect(() => sync?.onParked(setParked), [sync])

  const retry = () => {
    if (!sync) return
    setRetrying(true)
    sync
      .retryParked()
      .catch(() => {})
      .finally(() => setRetrying(false))
  }

  return (
    <>
      {(version.upgrade || engineUpgrade) && (
        <div className={styles.unsynced} role="alert">
          <span>{UPGRADE_REQUIRED}</span>
        </div>
      )}
      {sync && parked > 0 && (
        <div className={styles.unsynced} role="status">
          <span>{parkedText(parked)}</span>
          <button type="button" className={styles.syncNow} onClick={retry} disabled={retrying}>
            Retry
          </button>
        </div>
      )}
      {version.reload && (
        <div className={styles.unsynced} role="status">
          <span>notesd was updated. Reload to get the new version.</span>
          <button type="button" className={styles.syncNow} onClick={() => window.location.reload()}>
            Reload
          </button>
        </div>
      )}
    </>
  )
}
