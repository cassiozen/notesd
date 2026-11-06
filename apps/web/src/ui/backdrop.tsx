import styles from './backdrop.module.css'

/** Behind the window: the theme's style decides what's there (a glow, washes, a pattern). Decorative. */
export function Backdrop() {
  return (
    <div className={styles.backdrop} aria-hidden="true">
      <div className={styles.wash} />
    </div>
  )
}
