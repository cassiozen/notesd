import { describe, expect, it } from 'vitest'
import type { Space, SpaceTrait } from '../api/types'
import { newExposure, TRAITS, traitEntry, traitGlyph, traitRows, traitsLabel, UnknownGlyph } from './traits'

const shared: SpaceTrait = { key: 'shared', label: 'Shared', summary: 'With Ana and Ben', exposure: 'Visible to Ana and Ben' }
const published: SpaceTrait = { key: 'published', label: 'Published', summary: 'At notes.example', exposure: 'Public at notes.example' }
const agent: SpaceTrait = { key: 'agent', label: 'Agent', summary: 'A local model' }
const calendar: SpaceTrait = { key: 'calendar', label: 'Calendar', summary: 'Synced', exposure: 'Sent to Apple' }

const space = (...traits: SpaceTrait[]): Space => ({ id: 's', name: 'S', icon: '', createdAt: 0, noteCount: 0, traits })
const canTurnOn = (s: Space) => TRAITS.filter((t) => t.canTurnOn(s)).map((t) => t.key)

describe('the registry', () => {
  it('keeps the server’s order', () => {
    expect(TRAITS.map((t) => t.key)).toEqual(['shared', 'published', 'agent'])
  })

  it('draws a key it doesn’t know with the generic glyph', () => {
    expect(traitEntry('calendar')).toBeUndefined()
    expect(traitGlyph('calendar')).toBe(UnknownGlyph)
    expect(traitGlyph('shared')).not.toBe(UnknownGlyph)
  })

  it('offers sharing for a space of the account’s own, never the default, nor to a member', () => {
    expect(canTurnOn(space())).toEqual(['shared'])
    expect(canTurnOn({ ...space(), id: 'default' })).toEqual([])
    // Shared once, no one left in it: its owner can share it again.
    expect(canTurnOn({ ...space(), shared: { role: 'owner', members: [{ id: 1, username: 'me' }] } })).toEqual(['shared'])
    expect(canTurnOn({ ...space(), shared: { role: 'member', members: [] } })).toEqual([])
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

  it('lists only what can be turned on for a space with none', () => {
    expect(traitRows(space()).map((r) => [r.kind, r.kind === 'off' ? r.entry.key : r.trait.key])).toEqual([['off', 'shared']])
    expect(traitRows({ ...space(), id: 'default' })).toEqual([])
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

  it('compares who a trait reaches when the server says, not how it words it', () => {
    const anaBen: SpaceTrait = { ...shared, exposure: 'Visible to Ana and Ben', audience: ['user:2', 'user:3'] }
    const benAna: SpaceTrait = { ...shared, exposure: 'Visible to Ben and Ana', audience: ['user:3', 'user:2'] }
    const ana: SpaceTrait = { ...shared, exposure: 'Visible to Ana', audience: ['user:2'] }
    const anaCy: SpaceTrait = { ...shared, exposure: 'Visible to Ana and Cy', audience: ['user:2', 'user:4'] }
    expect(newExposure(space(anaBen), space(benAna))).toEqual([])
    expect(newExposure(space(anaBen), space(ana))).toEqual([])
    expect(newExposure(space(anaBen), space(anaCy))).toEqual([anaCy])
    expect(newExposure(space(anaBen), space({ ...published, audience: ['public'] }))).toHaveLength(1)
  })

  it('treats a current space not loaded yet as exposing nothing', () => {
    expect(newExposure(undefined, space(shared))).toEqual([shared])
  })
})
