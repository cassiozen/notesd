import { useRef } from 'react'
import { useCopy } from './copy'
import styles from './code.module.css'

/** A shell command to run on the server, with a copy button. */
export function Command({ command, tone = 'light' }: { command: string; tone?: 'light' | 'dark' }) {
  const [copied, copy] = useCopy()
  const text = useRef<HTMLSpanElement>(null)

  return (
    <div className={[styles.command, styles[tone]].join(' ')}>
      <code>
        {tone === 'dark' && <span className={styles.dim}>$ </span>}
        <span ref={text}>{command}</span>
      </code>
      <button
        type="button"
        className={styles.copy}
        onClick={() => {
          void copy(command).then((ok) => {
            // Selected, for ⌘C, if the browser refused.
            if (!ok && text.current) window.getSelection()?.selectAllChildren(text.current)
          })
        }}
      >
        {copied ? 'Copied' : 'Copy'}
        <span className="visually-hidden"> command</span>
      </button>
    </div>
  )
}
