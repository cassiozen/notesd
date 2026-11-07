import { useEffect, useState } from 'react'
import { copyText } from './copy'
import styles from './code.module.css'

/** A shell command to run on the server, with a copy button. */
export function Command({ command, tone = 'light' }: { command: string; tone?: 'light' | 'dark' }) {
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    if (!copied) return
    const t = setTimeout(() => setCopied(false), 1600)
    return () => clearTimeout(t)
  }, [copied])

  return (
    <div className={[styles.command, styles[tone]].join(' ')}>
      <code>
        {tone === 'dark' && <span className={styles.dim}>$ </span>}
        {command}
      </code>
      <button
        type="button"
        className={styles.copy}
        onClick={() => {
          void copyText(command).then(setCopied)
        }}
      >
        {copied ? 'Copied' : 'Copy'}
        <span className="visually-hidden"> command</span>
      </button>
    </div>
  )
}
