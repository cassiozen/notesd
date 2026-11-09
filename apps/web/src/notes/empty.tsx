import { useAccount, useAuth } from '../auth/auth-context'
import { Button } from '../ui/button'
import { Kicker } from '../ui/kicker'
import styles from './empty.module.css'

/** The editor pane with no note open. */
export function EmptyPane({ hasNotes, onNew }: { hasNotes: boolean; onNew: () => void }) {
  const { state } = useAuth()
  const firstRun = state.status === 'signed-in' && state.firstRun === true
  const { name } = useAccount()
  const kicker = hasNotes ? 'Nothing selected' : firstRun ? `You’re all set, ${name}` : 'No notes yet'
  return (
    <section className={styles.pane} aria-labelledby="empty-title">
      <div className={styles.text}>
        <h2 id="empty-title" className={styles.kicker}>
          <Kicker>{kicker}</Kicker>
        </h2>
        {hasNotes && <p className={styles.title}>Pick a note, or start one.</p>}
        <Button variant="solid" size="md" className={styles.write} onClick={onNew}>
          Write a note
        </Button>
      </div>
    </section>
  )
}
