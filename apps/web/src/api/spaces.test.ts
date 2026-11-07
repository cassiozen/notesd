import { afterEach, describe, expect, it, vi } from 'vitest'
import { readSpace, spaces } from './spaces'

afterEach(() => vi.unstubAllGlobals())

const base = { id: 'work', name: 'Work', icon: '', createdAt: 1, noteCount: 0 }
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })

describe('spaces from a server older than traits', () => {
  it('reads a missing traits as none', () => {
    expect(readSpace(base).traits).toEqual([])
    expect(readSpace({ ...base, traits: null }).traits).toEqual([])
  })

  it('keeps the traits sent', () => {
    const traits = [{ key: 'shared', label: 'Shared', summary: 'With Ana' }]
    expect(readSpace({ ...base, traits }).traits).toEqual(traits)
  })

  it('normalizes both the list and a put’s answer', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ spaces: [base] })).mockResolvedValueOnce(json(base)))
    expect((await spaces.list()).spaces).toEqual([{ ...base, traits: [] }])
    expect(await spaces.put('work', 'Work')).toEqual({ ...base, traits: [] })
  })
})
