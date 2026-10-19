import { useId } from 'react'
import type { Space } from '../api/types'
import { DEFAULT_SPACE_ICON, SPACE_ICONS } from './space-icons'
import styles from './space-icon.module.css'

// Full-colour pictures (§7.7), so <img> rather than inline SVG: each keeps
// its gradient ids to itself, and the browser caches the files.
const urls = import.meta.glob<string>('./space-icons/*.svg', { eager: true, query: '?url', import: 'default' })

const fallback = urls[`./space-icons/${DEFAULT_SPACE_ICON}.svg`] ?? ''

function url(key: string): string {
  return urls[`./space-icons/${key}.svg`] ?? fallback
}

/** The space's icon, or the default for one without (or one this app doesn't have). Decorative. */
export function SpaceIcon({ space, size = 20 }: { space: Pick<Space, 'icon'>; size?: number }) {
  return <img className={styles.icon} src={url(space.icon)} width={size} height={size} alt="" draggable={false} />
}

/** Every icon to choose from: native radios, so arrow keys move between them. */
export function SpaceIconPicker({
  value,
  onChange,
  error,
}: {
  value: string | null
  onChange: (key: string) => void
  error?: string
}) {
  const name = useId()
  return (
    <fieldset className={styles.picker} aria-invalid={error ? true : undefined}>
      <legend className={styles.legend}>Icon</legend>
      <div className={styles.grid}>
        {SPACE_ICONS.map((icon) => (
          <label key={icon.key} className={styles.choice} title={icon.name}>
            <input
              type="radio"
              name={name}
              value={icon.key}
              checked={icon.key === value}
              onChange={() => onChange(icon.key)}
              aria-label={icon.name}
              className={styles.radio}
            />
            <img src={url(icon.key)} width={28} height={28} alt="" draggable={false} />
          </label>
        ))}
      </div>
      {error && (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      )}
    </fieldset>
  )
}
