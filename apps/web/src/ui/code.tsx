import { useEffect, useRef, useState } from 'react'
import { copyText } from './copy'
import styles from './code.module.css'

/** A shell command to run on the server, with a copy button. */
export function Command({ command, tone = 'light' }: { command: string; tone?: 'light' | 'dark' }) {
  const [copied, setCopied] = useState(false)
  const text = useRef<HTMLSpanElement>(null)

  useEffect(() => {
    if (!copied) return
    const t = setTimeout(() => setCopied(false), 1600)
    return () => clearTimeout(t)
  }, [copied])

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
          void copyText(command).then((ok) => {
            setCopied(ok)
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
