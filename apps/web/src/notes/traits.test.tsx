import { describe, expect, it } from 'vitest'
import type { Space, SpaceTrait } from '../api/types'
import { newExposure, TRAITS, traitEntry, traitGlyph, traitRows, traitsLabel, UnknownGlyph } from './traits'

const shared: SpaceTrait = { key: 'shared', label: 'Shared', summary: 'With Ana and Ben', exposure: 'Visible to Ana and Ben' }
const published: SpaceTrait = { key: 'published', label: 'Published', summary: 'At notes.example', exposure: 'Public at notes.example' }
const agent: SpaceTrait = { key: 'agent', label: 'Agent', summary: 'A local model' }
const calendar: SpaceTrait = { key: 'calendar', label: 'Calendar', summary: 'Synced', exposure: 'Sent to Apple' }

const space = (...traits: SpaceTrait[]): Space => ({ id: 's', name: 'S', icon: '', createdAt: 0, noteCount: 0, traits })

describe('the registry', () => {
  it('keeps the server’s order', () => {
    expect(TRAITS.map((t) => t.key)).toEqual(['shared', 'published', 'agent'])
  })

  it('draws a key it doesn’t know with the generic glyph', () => {
    expect(traitEntry('calendar')).toBeUndefined()
    expect(traitGlyph('calendar')).toBe(UnknownGlyph)
    expect(traitGlyph('shared')).not.toBe(UnknownGlyph)
  })

  it('offers nothing to turn on yet', () => {
    expect(TRAITS.filter((t) => t.canTurnOn(space()))).toEqual([])
  })
})

describe('traitsLabel', () => {
  it('joins the labels, a problem after its trait', () => {
    expect(traitsLabel([shared, published])).toBe('Shared, Published')
    expect(traitsLabel([shared, { ...published, problem: 'Publishing failed' }])).toBe('Shared, Published (Publishing failed)')
  })
})

describe('traitRows', () => {
  it('lists known traits in order, then unknown ones as sent', () => {
    const rows = traitRows(space(calendar, agent, shared))
    expect(rows.map((r) => [r.kind, r.kind === 'off' ? r.entry.key : r.trait.key])).toEqual([
      ['on', 'shared'],
      ['on', 'agent'],
      ['unknown', 'calendar'],
    ])
  })

  it('is empty for a space with none', () => {
    expect(traitRows(space())).toEqual([])
  })
})

describe('newExposure', () => {
  it('is the destination’s exposure the current space lacks', () => {
    expect(newExposure(space(), space(shared, published))).toEqual([shared, published])
    expect(newExposure(space(shared), space(shared, published))).toEqual([published])
  })

  it('asks nothing between spaces that expose the same, or into one that exposes less', () => {
    expect(newExposure(space(shared, published), space(shared, published))).toEqual([])
    expect(newExposure(space(shared, published), space(shared))).toEqual([])
    expect(newExposure(space(shared), space())).toEqual([])
  })

  it('names an audience once when two traits expose to it', () => {
    const family: SpaceTrait = { key: 'calendar', label: 'Calendar', summary: 'Synced', exposure: 'Visible to Ana and Ben' }
    expect(newExposure(space(), space(shared, family))).toEqual([shared])
  })

  it('ignores traits that keep the notes to the account', () => {
    expect(newExposure(space(), space(agent))).toEqual([])
  })

  it('compares the sentences, not the keys', () => {
    const more = { ...shared, exposure: 'Visible to Ana, Ben and Cy' }
    expect(newExposure(space(shared), space(more))).toEqual([more])
  })

  it('counts unknown traits by their exposure too', () => {
    expect(newExposure(space(), space(calendar))).toEqual([calendar])
  })

  it('treats a current space not loaded yet as exposing nothing', () => {
    expect(newExposure(undefined, space(shared))).toEqual([shared])
  })
})
