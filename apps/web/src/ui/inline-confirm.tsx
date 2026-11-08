import { useEffect, useRef, type ReactNode } from 'react'
import { flushSync } from 'react-dom'
import { Button } from './button'
import styles from './inline-confirm.module.css'

/**
 * A destructive action's confirmation, opened under the button that asked for it instead of in a dialog over the
 * page: what it will do stays beside where it was asked. Focus goes to Cancel, and back to that button on Cancel or
 * Escape.
 */
export function InlineConfirm({
  title,
  children,
  action,
  busy = false,
  onConfirm,
  onCancel,
}: {
  title: string
  children?: ReactNode
  action: string
  busy?: boolean
  onConfirm: () => void
  onCancel: () => void
}) {
  const cancelRef = useRef<HTMLButtonElement>(null)
  const from = useRef<HTMLElement | null>(null)

  useEffect(() => {
    // Kept from the first run: StrictMode's second would find Cancel focused.
    from.current ??= document.activeElement instanceof HTMLElement ? document.activeElement : null
    cancelRef.current?.focus()
  }, [])

  const cancel = () => {
    if (busy) return
    const back = from.current
    // Closed first: the button that asked may be disabled while this is open, and can't take focus until then.
    flushSync(onCancel)
    if (back?.isConnected) back.focus()
  }

  return (
    <div
      role="group"
      aria-label={title}
      className={styles.confirm}
      onKeyDown={(e) => {
        if (e.key !== 'Escape') return
        // Escape closes this, not the dialog it's in.
        e.preventDefault()
        e.stopPropagation()
        cancel()
      }}
    >
      <p className={styles.text}>
        <span className={styles.title}>{title}</span> {children}
      </p>
      <div className={styles.actions}>
        <Button ref={cancelRef} size="sm" onClick={cancel} disabled={busy}>
          Cancel
        </Button>
        <Button size="sm" variant="danger" busy={busy} onClick={onConfirm}>
          {action}
        </Button>
      </div>
    </div>
  )
}
