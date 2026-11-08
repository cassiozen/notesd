import type { ButtonHTMLAttributes, Ref } from 'react'
import styles from './button.module.css'

type Props = ButtonHTMLAttributes<HTMLButtonElement> & {
  /**
   * solid: the main action. accent: the one action that creates something.
   * chip: secondary. ghost: toolbar/back. danger: destructive, soft fill.
   * danger-text: destructive, no fill.
   */
  variant?: 'solid' | 'accent' | 'chip' | 'ghost' | 'danger' | 'danger-text'
  size?: 'lg' | 'md' | 'sm'
  /** Stretch to fill the row (flex: 1). */
  grow?: boolean
  busy?: boolean
  ref?: Ref<HTMLButtonElement>
}

export function Button({
  variant = 'chip',
  size = 'lg',
  grow = false,
  busy = false,
  className,
  disabled,
  type = 'button',
  ...rest
}: Props) {
  return (
    <button
      type={type}
      className={[styles.button, styles[variant], styles[size], grow && styles.grow, className]
        .filter(Boolean)
        .join(' ')}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      {...rest}
    />
  )
}
