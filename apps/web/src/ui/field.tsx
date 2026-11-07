import { useId, type InputHTMLAttributes, type ReactNode, type Ref } from 'react'
import styles from './field.module.css'

type Props = InputHTMLAttributes<HTMLInputElement> & {
  ref?: Ref<HTMLInputElement>
  label: string
  /** Visually hide the label (it stays for screen readers). */
  hideLabel?: boolean
  error?: string | undefined
  hint?: ReactNode
  /** Monospace input, for tokens and URLs. */
  mono?: boolean
  /** Rendered between the input and the messages, e.g. a strength meter. */
  children?: ReactNode
}

export function Field({ label, hideLabel = false, error, hint, mono = false, children, className, ...input }: Props) {
  const id = useId()
  const describedBy = [error && `${id}-error`, hint && `${id}-hint`].filter(Boolean).join(' ')
  return (
    <div className={[styles.field, className].filter(Boolean).join(' ')}>
      <label htmlFor={id} className={hideLabel ? 'visually-hidden' : styles.label}>
        {label}
      </label>
      <input
        id={id}
        className={[styles.input, mono && styles.mono].filter(Boolean).join(' ')}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy || undefined}
        {...input}
      />
      {children}
      {error && (
        <p id={`${id}-error`} className={styles.error}>
          {error}
        </p>
      )}
      {hint && (
        <p id={`${id}-hint`} className={styles.hint}>
          {hint}
        </p>
      )}
    </div>
  )
}
