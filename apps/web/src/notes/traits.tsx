import type { ComponentType } from 'react'
import type { Space, SpaceTrait } from '../api/types'
import { PeopleSection } from '../settings/shared-space'
import { DEFAULT_SPACE } from './default-space'

// Space traits (architecture §7.8): this app's registry, the glyphs it draws and the move check.

type GlyphProps = { size?: number }

export type TraitEntry = {
  key: string
  /** Named while it's off; on, the server's label is shown. */
  label: string
  Glyph: ComponentType<GlyphProps>
  /**
   * Its part of Space info, on or off: everything it changes, in place. None: Space info shows the server's
   * label and summary, and there is nothing to change.
   */
  Section?: ComponentType<{ space: Space }>
  /** Off, whether Space info offers it. Set only with a Section: that section is what turns it on. */
  canTurnOn: (space: Space) => boolean
}

// Line glyphs as in ui/icons.tsx: a 16-unit grid in currentColor.

export function SharedGlyph({ size = 14 }: GlyphProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" aria-hidden="true">
      <circle cx="6" cy="5.4" r="2.3" />
      <path d="M1.8 13.4c0-2.4 1.9-4.1 4.2-4.1s4.2 1.7 4.2 4.1" />
      <circle cx="11.4" cy="6" r="1.8" />
      <path d="M11.2 9.5c1.8.2 3 1.7 3 3.7" />
    </svg>
  )
}

export function PublishedGlyph({ size = 14 }: GlyphProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" aria-hidden="true">
      <circle cx="8" cy="8" r="5.8" />
      <path d="M2.2 8h11.6M8 2.2c1.5 1.6 2.3 3.6 2.3 5.8S9.5 12.2 8 13.8C6.5 12.2 5.7 10.2 5.7 8S6.5 3.8 8 2.2z" />
    </svg>
  )
}

export function AgentGlyph({ size = 14 }: GlyphProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" aria-hidden="true">
      <path d="M7 2.3l1.3 3.6 3.6 1.3-3.6 1.3L7 12.1 5.7 8.5 2.1 7.2l3.6-1.3z" />
      <path d="M12.4 10.2l.5 1.4 1.4.5-1.4.5-.5 1.4-.5-1.4-1.4-.5 1.4-.5z" strokeWidth="1.1" />
    </svg>
  )
}

/** A trait this app doesn't know: a newer server's. */
export function UnknownGlyph({ size = 14 }: GlyphProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" aria-hidden="true">
      <path d="M3.2 5h2.4a1.6 1.6 0 1 1 3.2 0h2.4v2.4a1.6 1.6 0 1 1 0 3.2V13H3.2z" />
    </svg>
  )
}

const off = () => false

/** Never the default space (everyone keeps a private one), and only by its owner once it's shared (§7.9). */
const canShare = (space: Space) => space.id !== DEFAULT_SPACE && (!space.shared || space.shared.role === 'owner')

/** In the server's order (spaces.TraitOrder); a key keeps its place before its feature ships. */
export const TRAITS: readonly TraitEntry[] = [
  { key: 'shared', label: 'Shared', Glyph: SharedGlyph, Section: PeopleSection, canTurnOn: canShare },
  { key: 'published', label: 'Published', Glyph: PublishedGlyph, canTurnOn: off },
  { key: 'agent', label: 'Agent', Glyph: AgentGlyph, canTurnOn: off },
]

export function traitEntry(key: string): TraitEntry | undefined {
  return TRAITS.find((t) => t.key === key)
}

export function traitGlyph(key: string): ComponentType<GlyphProps> {
  return traitEntry(key)?.Glyph ?? UnknownGlyph
}

/** The glyphs' accessible name: "Shared, Published", with any problem said after its trait. */
export function traitsLabel(traits: readonly SpaceTrait[]): string {
  return traits.map((t) => (t.problem ? `${t.label} (${t.problem})` : t.label)).join(', ')
}

/**
 * Moving a note from one space to another: the destination's traits that
 * reach someone the current space doesn't. Empty: nothing to ask.
 */
export function newExposure(from: Pick<Space, 'traits'> | undefined, to: Pick<Space, 'traits'>): SpaceTrait[] {
  const reached = new Set(from?.traits.flatMap((t) => t.audience ?? []))
  // A trait without an audience (one this app predates) can only be compared by its words.
  const said = new Set(from?.traits.map((t) => t.exposure).filter(Boolean))
  // Two traits can expose to the same people; the dialog says so once.
  const seen = new Set<string>()
  return to.traits.filter((t) => {
    if (!t.exposure || seen.has(t.exposure)) return false
    const fresh = t.audience?.length ? t.audience.some((a) => !reached.has(a)) : !said.has(t.exposure)
    if (!fresh) return false
    seen.add(t.exposure)
    return true
  })
}

/** The first trait that lets others read the space's notes: a locked note, only yours to read, can't be there (§7.8). */
export function exposingTrait(space: Pick<Space, 'traits'> | undefined): SpaceTrait | undefined {
  return space?.traits.find((t) => t.exposure)
}

/** "Locked notes can’t be in a shared space." */
export function lockedRefusal(trait: SpaceTrait): string {
  return `Locked notes can’t be in a ${trait.label.toLowerCase()} space.`
}

export type TraitRow =
  | { kind: 'on'; entry: TraitEntry; trait: SpaceTrait }
  | { kind: 'off'; entry: TraitEntry }
  | { kind: 'unknown'; trait: SpaceTrait }

/**
 * Space info's rows: each trait this app knows, in order, when on or when it
 * could be turned on; then any on that it doesn't know, as the server sent them.
 */
export function traitRows(space: Space): TraitRow[] {
  const known: TraitRow[] = TRAITS.flatMap((entry): TraitRow[] => {
    const trait = space.traits.find((t) => t.key === entry.key)
    if (trait) return [{ kind: 'on', entry, trait }]
    return entry.canTurnOn(space) ? [{ kind: 'off', entry }] : []
  })
  const unknown = space.traits.filter((t) => !traitEntry(t.key)).map((trait): TraitRow => ({ kind: 'unknown', trait }))
  return [...known, ...unknown]
}
