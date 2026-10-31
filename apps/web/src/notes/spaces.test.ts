import { renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Space } from '../api/types'
import { onlineSource } from './online-source'
import { setNotesSource, type NotesSource } from './source'
import { forgetSpaces, onSpacesChange, reloadSpaces, useSpaces } from './spaces'

/** A source whose space lists answer when the test says. */
function fakeSource() {
  const pending: ((list: Space[]) => void)[] = []
  const source = {
    spaces: { list: () => new Promise<Space[]>((resolve) => pending.push(resolve)) },
  } as unknown as NotesSource
  return { source, answer: (list: Space[]) => pending.shift()?.(list) }
}

const space = (id: string): Space => ({ id, name: id }) as Space

afterEach(() => setNotesSource(onlineSource))

describe('spaces on a change of source', () => {
  it('fetches the new source’s list', async () => {
    const a = fakeSource()
    const b = fakeSource()
    setNotesSource(a.source)
    const { result, rerender } = renderHook(() => useSpaces())
    a.answer([space('from-a')])
    await waitFor(() => expect(result.current).toEqual([space('from-a')]))

    setNotesSource(b.source)
    rerender()
    expect(result.current).toBeNull()
    // The screens remount on a new source; a fresh useSpaces fetches.
    const { result: remounted } = renderHook(() => useSpaces())
    b.answer([space('from-b')])
    await waitFor(() => expect(remounted.current).toEqual([space('from-b')]))
  })

  it('ignores a fetch from the old source that answers after the change', async () => {
    const a = fakeSource()
    setNotesSource(a.source)
    const late = reloadSpaces()
    setNotesSource(fakeSource().source)
    const seen: Space[][] = []
    const off = onSpacesChange((list) => seen.push(list))
    a.answer([space('from-a')])
    await late
    off()
    expect(seen).toEqual([])
  })

  it('forgetting drops the list for the next account', async () => {
    const a = fakeSource()
    setNotesSource(a.source)
    const loaded = reloadSpaces()
    a.answer([space('mine')])
    await loaded
    forgetSpaces()
    const { result } = renderHook(() => useSpaces())
    expect(result.current).toBeNull()
  })
})

describe('loading', () => {
  it('source.ts loads first without tripping on spaces.ts', async () => {
    // spaces.ts subscribes to source.ts as it loads: any import from source.ts
    // back to spaces.ts would run that before source.ts is ready.
    vi.resetModules()
    await expect(import('./source')).resolves.toBeDefined()
  })
})
