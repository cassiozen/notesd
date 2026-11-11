// Apart from traits.tsx, so a trait's section can draw its glyph without importing the registry that imports it.

export type GlyphProps = { size?: number }

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
