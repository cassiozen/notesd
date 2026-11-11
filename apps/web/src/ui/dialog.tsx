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
  /**
   * Focus the dialog itself on open, not its first control: for a page of settings, where no one control is what
   * it opened for, and the first shouldn't be drawn ringed before anything's been pressed.
   */
  focusSelf?: boolean
  children: ReactNode
}

/** Modal built on <dialog>: the browser traps focus and restores it on close. */
export function Dialog({ labelledBy, onClose, space, wide = false, focusSelf = false, children }: Props) {
  const ref = useRef<HTMLDialogElement>(null)

  // Layout effect: open before children's effects run, or showModal()'s
  // initial focus would override focus they set (e.g. a new note's title).
  useLayoutEffect(() => {
    const dialog = ref.current
    dialog?.showModal()
    if (focusSelf) dialog?.focus()
    return () => dialog?.close()
  }, [focusSelf])

  return (
    <dialog
      ref={ref}
      className={wide ? `${styles.dialog} ${styles.wide}` : styles.dialog}
      aria-labelledby={labelledBy}
      data-space={space}
      tabIndex={focusSelf ? -1 : undefined}
      onCancel={(e) => {
        e.preventDefault()
        onClose?.()
      }}
    >
      {children}
    </dialog>
  )
}
