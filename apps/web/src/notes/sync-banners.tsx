import { useEffect, useState, useSyncExternalStore } from 'react'
import { versionState } from '../api/version'
import { Banner } from '../ui/banner'
import { Button } from '../ui/button'
import { useNotesSource, type Unsynced } from './source'
import styles from './shell.module.css'

export const UPGRADE_REQUIRED =
  'Your notesd server needs a newer version of this app. Update notesd to keep syncing; your changes are kept here.'

/** Notes, spaces and pictures alike: the engine counts every kind of change it set aside. */
export function parkedText(n: number): string {
  return n === 1 ? '1 change couldn’t sync. It’s kept on this device.' : `${n} changes couldn’t sync. They’re kept on this device.`
}

/**
 * What sync and the server need the user to know, above every screen so a phone showing one note
 * sees it too: an app too old for the server, changes set aside or stuck here, a newer notesd to reload.
 */
export function SyncBanners() {
  const version = useSyncExternalStore(versionState.subscribe, versionState.get)
  const sync = useNotesSource().sync
  const [engineUpgrade, setEngineUpgrade] = useState(false)
  const [parked, setParked] = useState(0)
  const [unsynced, setUnsynced] = useState<Unsynced | null>(null)
  const [retrying, setRetrying] = useState(false)

  useEffect(() => sync?.onState((s) => setEngineUpgrade(s.status.kind === 'UpgradeRequired')), [sync])
  useEffect(() => sync?.onParked(setParked), [sync])
  useEffect(() => sync?.onUnsynced(setUnsynced), [sync])

  const retry = () => {
    if (!sync) return
    setRetrying(true)
    sync
      .retryParked()
      .catch(() => {})
      .finally(() => setRetrying(false))
  }

  const upgrade = version.upgrade !== null || engineUpgrade
  // One message, one Retry: Retry puts the set-aside changes back and syncs the rest with them.
  const showParked = sync && parked > 0 && !upgrade
  const showUnsynced = sync && unsynced && !showParked && !upgrade
  if (!upgrade && !showParked && !showUnsynced && !version.reload) return null

  return (
    <div className={styles.banners}>
      {upgrade && <Banner tone="error">{UPGRADE_REQUIRED}</Banner>}
      {showParked && (
        <Banner
          tone="warn"
          actions={
            <Button size="sm" onClick={retry} busy={retrying}>
              Retry
            </Button>
          }
        >
          {parkedText(parked)}
        </Banner>
      )}
      {showUnsynced && (
        <Banner
          tone="warn"
          actions={
            <Button size="sm" onClick={() => sync.now()}>
              Sync now
            </Button>
          }
        >
          {unsynced.pending === 1 ? '1 note not synced' : `${unsynced.pending} notes not synced`}
          {unsynced.error && <span className={styles.bannerWhy}> · {unsynced.error}</span>}
        </Banner>
      )}
      {version.reload && (
        <Banner
          tone="warn"
          actions={
            <Button size="sm" onClick={() => window.location.reload()}>
              Reload
            </Button>
          }
        >
          notesd was updated. Reload to get the new version.
        </Banner>
      )}
    </div>
  )
}
