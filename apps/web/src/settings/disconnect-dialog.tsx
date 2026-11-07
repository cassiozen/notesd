import { useId, type ReactNode } from 'react'
import { Button } from '../ui/button'
import { Dialog } from '../ui/dialog'
import styles from './settings.module.css'

/** "Disconnect X?", for an integration's Manage dialog. */
export function DisconnectDialog({
  title,
  children,
  error,
  busy,
  onBack,
  onDisconnect,
}: {
  title: string
  children: ReactNode
  error?: string
  busy: boolean
  onBack: () => void
  onDisconnect: () => void
}) {
  const titleId = useId()
  return (
    <Dialog labelledBy={titleId} onClose={busy ? undefined : onBack}>
      <div className={styles.dialog}>
        <h2 id={titleId} className={styles.dialogTitle}>
          {title}
        </h2>
        <p className={styles.dialogText}>{children}</p>
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
        <div className={styles.dialogActions}>
          <Button size="md" onClick={onBack} disabled={busy}>
            Keep it
          </Button>
          <Button variant="danger" size="md" busy={busy} onClick={onDisconnect}>
            Disconnect
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
