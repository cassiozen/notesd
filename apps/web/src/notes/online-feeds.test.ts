import { afterEach, describe, expect, it, vi } from 'vitest'
import { notes } from '../api/notes'
import type { ListItem, Space } from '../api/types'
import { feedNews } from './online-feeds'

const ME = 1
const ANA = 2
const work = { id: 'work', shared: { role: 'owner', members: [{ id: ME, username: 'me' }, { id: ANA, username: 'ana' }] } } as unknown as Space

/** Pages of [size] notes Ana changed, newest first; the last has no more after it. */
function pages(count: number, size = 50) {
  let n = 0
  return vi.spyOn(notes, 'list').mockImplementation(async () => {
    const page = Array.from({ length: size }, () => ({ id: String(n), updatedAt: Date.now() - n++, updatedBy: ANA }) as unknown as ListItem)
    return { notes: page, nextCursor: String(n), hasMore: n < count * size, total: count * size }
  })
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('feedNews', () => {
  it('counts every change in a few pages', async () => {
    pages(2)
    expect(await feedNews([work], ME)).toEqual([{ spaceId: 'work', count: 100, usernames: ['ana'], latestAt: expect.any(Number) }])
  })

  it('stops after a few pages, and says the count is a floor', async () => {
    const list = pages(10)
    const [news] = await feedNews([work], ME)
    expect(list).toHaveBeenCalledTimes(4)
    expect(news).toMatchObject({ count: 200, atLeast: true })
  })
})
