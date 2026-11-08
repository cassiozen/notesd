import { useState } from 'react'
import { avatarURL } from '../api/profile'
import styles from './avatar.module.css'

/**
 * Someone's photo, or the first letter of their name without one. className sets its size and shape; the photo
 * fills it. A photo that can't load (offline, removed elsewhere) shows the letter.
 */
export function Avatar({
  userId,
  version,
  name,
  className,
  src,
}: {
  userId?: number
  /** The account's avatar field; '' or absent for none. */
  version?: string
  name: string
  className?: string
  /** A photo not yet saved, previewed instead. */
  src?: string
}) {
  const url = src ?? (userId !== undefined && version ? avatarURL(userId, version) : undefined)
  const [failed, setFailed] = useState<string>()
  return (
    <span className={[styles.avatar, className].filter(Boolean).join(' ')} aria-hidden="true">
      {url && failed !== url ? <img className={styles.photo} src={url} alt="" onError={() => setFailed(url)} /> : initial(name)}
    </span>
  )
}

export function initial(name: string): string {
  return (Array.from(name.trim())[0] ?? '?').toUpperCase()
}
