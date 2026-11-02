import { useId, type ReactNode, type TextareaHTMLAttributes } from 'react'
import styles from './field.module.css'

// The label and messages are tied to the generated id; a caller's would cut them off.
type Props = Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'id' | 'aria-describedby' | 'aria-invalid'> & {
  label: string
  error?: string | undefined
  hint?: ReactNode
}

/** Field's multi-line sibling, for prompts and other prose. */
export function TextArea({ label, error, hint, className, rows = 3, ...input }: Props) {
  const id = useId()
  const describedBy = [error && `${id}-error`, hint && `${id}-hint`].filter(Boolean).join(' ')
  return (
    <div className={[styles.field, className].filter(Boolean).join(' ')}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      <textarea
        id={id}
        rows={rows}
        className={[styles.input, styles.area].join(' ')}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy || undefined}
        {...input}
      />
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
