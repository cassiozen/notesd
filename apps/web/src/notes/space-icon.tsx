import { useId } from 'react'
import type { Space } from '../api/types'
import mark from '../ui/mark.png'
import { SPACE_ICONS } from './space-icons'
import styles from './space-icon.module.css'

// Full-colour pictures (§7.7), so <img> rather than inline SVG: each keeps
// its gradient ids to itself, and the browser caches the files.
const urls = import.meta.glob<string>('./space-icons/*.svg', { eager: true, query: '?url', import: 'default' })

function url(key: string): string | undefined {
  return urls[`./space-icons/${key}.svg`]
}

/**
 * An icon's picture: the app's own for '' (the default space's to begin with)
 * and any key this app doesn't have, inset as the others leave a margin.
 */
function Picture({ icon, size, className }: { icon: string; size: number; className?: string }) {
  const src = url(icon)
  return (
    <img
      className={className}
      style={src ? undefined : { padding: size * 0.08 }}
      src={src ?? mark}
      width={size}
      height={size}
      alt=""
      draggable={false}
    />
  )
}

/** The space's icon. Decorative. */
export function SpaceIcon({ space, size = 20 }: { space: Pick<Space, 'icon'>; size?: number }) {
  return <Picture icon={space.icon} size={size} className={styles.icon} />
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
        {[{ key: '', name: 'App icon' }, ...SPACE_ICONS].map((icon) => (
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
            <Picture icon={icon.key} size={36} />
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
