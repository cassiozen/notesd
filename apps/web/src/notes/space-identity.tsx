import type { Space, SpaceTrait } from '../api/types'
import { SpaceIcon } from './space-icon'
import { traitGlyph, traitsLabel } from './traits'
import styles from './space-identity.module.css'

/**
 * A space's traits as glyphs, packed in the server's order (§7.8); one with a
 * problem in the danger colour. Nothing for a space without traits.
 */
export function TraitGlyphs({ traits, size = 13, className }: { traits: readonly SpaceTrait[]; size?: number; className?: string }) {
  if (traits.length === 0) return null
  return (
    <span role="img" aria-label={traitsLabel(traits)} className={[styles.glyphs, className].filter(Boolean).join(' ')}>
      {traits.map((t) => {
        const Glyph = traitGlyph(t.key)
        return (
          <span key={t.key} className={t.problem ? styles.problem : undefined} data-trait={t.key}>
            <Glyph size={size} />
          </span>
        )
      })}
    </span>
  )
}

/**
 * A space wherever it's listed: icon, name, then its trait glyphs. A fragment, so
 * it lays out in the caller's row, which looks as before traits when there are none.
 */
export function SpaceIdentity({
  space,
  iconSize = 20,
  glyphSize,
  icon = true,
  nameClassName,
}: {
  space: Pick<Space, 'icon' | 'name' | 'traits'>
  iconSize?: number
  glyphSize?: number
  /** Off where the caller places the icon itself. */
  icon?: boolean
  nameClassName?: string
}) {
  return (
    <>
      {icon && <SpaceIcon space={space} size={iconSize} />}
      <span className={nameClassName} title={space.name}>
        {space.name}
      </span>
      {/* Collapsed in a flex row; keeps the name and the glyphs' label apart in an accessible name. */}
      {space.traits.length > 0 && ' '}
      <TraitGlyphs traits={space.traits} size={glyphSize} />
    </>
  )
}

/**
 * Beside a space's title (the switcher, the list's): its traits grouped in one
 * button that opens Space info (§7.8). Nothing for a space without traits.
 */
export function TraitGroup({ traits, onOpen }: { traits: readonly SpaceTrait[]; onOpen: () => void }) {
  if (traits.length === 0) return null
  const label = traitsLabel(traits)
  return (
    <button type="button" className={styles.group} aria-label={`Space info: ${label}`} title={label} onClick={onOpen}>
      <TraitGlyphs traits={traits} size={16} />
    </button>
  )
}
