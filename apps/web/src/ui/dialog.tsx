import { useLayoutEffect, useRef, type ReactNode } from 'react'
import styles from './dialog.module.css'

type Props = {
  labelledBy: string
  /** Omit to make the dialog impossible to dismiss with Escape. */
  onClose?: () => void
  /** A space theme's key (styles/space-themes.css), to show the dialog in it rather than the app's. */
  space?: string
  /** Room for a page of settings rather than a question. */
  wide?: boolean
  children: ReactNode
}

/** Modal built on <dialog>: the browser traps focus and restores it on close. */
export function Dialog({ labelledBy, onClose, space, wide = false, children }: Props) {
  const ref = useRef<HTMLDialogElement>(null)

  // Layout effect: open before children's effects run, or showModal()'s
  // initial focus would override focus they set (e.g. a new note's title).
  useLayoutEffect(() => {
    const dialog = ref.current
    dialog?.showModal()
    return () => dialog?.close()
  }, [])

  return (
    <dialog
      ref={ref}
      className={wide ? `${styles.dialog} ${styles.wide}` : styles.dialog}
      aria-labelledby={labelledBy}
      data-space={space}
      onCancel={(e) => {
        e.preventDefault()
        onClose?.()
      }}
    >
      {children}
    </dialog>
  )
}
