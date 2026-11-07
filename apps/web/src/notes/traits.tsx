import type { ComponentType } from 'react'
import type { Space, SpaceTrait } from '../api/types'

// Space traits (architecture §7.8): this app's registry, the glyphs it draws and the move check.

type GlyphProps = { size?: number }

/** What a trait's settings or its Turn on flow get: the space, and a way back to Space info. */
export type TraitFlowProps = { space: Space; onClose: () => void }

export type TraitEntry = {
  key: string
  /** Named while it's off; on, the server's label is shown. */
  label: string
  Glyph: ComponentType<GlyphProps>
  /** Space info's Manage, while it's on. None: Space info has nothing to change. */
  Settings?: ComponentType<TraitFlowProps>
  /** Off, whether Space info offers Turn on, which shows turnOn. */
  canTurnOn: (space: Space) => boolean
  turnOn?: ComponentType<TraitFlowProps>
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

/** In the server's order (spaces.TraitOrder); a key keeps its place before its feature ships. */
export const TRAITS: readonly TraitEntry[] = [
  { key: 'shared', label: 'Shared', Glyph: SharedGlyph, canTurnOn: off },
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
 * Moving a note from one space to another: the destination's traits whose
 * exposure the current space doesn't already have. Empty: nothing to ask.
 */
export function newExposure(from: Pick<Space, 'traits'> | undefined, to: Pick<Space, 'traits'>): SpaceTrait[] {
  const had = new Set(from?.traits.map((t) => t.exposure).filter(Boolean))
  return to.traits.filter((t) => t.exposure && !had.has(t.exposure))
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
