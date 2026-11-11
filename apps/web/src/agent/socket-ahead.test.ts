import { afterEach, describe, expect, it, vi } from 'vitest'
import { aheadTiming, agentSocket, socketFactory } from './socket'

/** Stands in for the server's end of the socket. */
class FakeSocket extends EventTarget {
  readyState: number = WebSocket.OPEN
  sent: Record<string, unknown>[] = []
  send(data: string) {
    this.sent.push(JSON.parse(data) as Record<string, unknown>)
  }
  close() {
    this.readyState = WebSocket.CLOSED
  }
  hear(m: object) {
    this.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(m) }))
  }
  prepares() {
    return this.sent.filter((m) => m.type === 'prepare').length
  }
}

function connect(): FakeSocket {
  const ws = new FakeSocket()
  vi.spyOn(socketFactory, 'make').mockReturnValue(ws as unknown as WebSocket)
  agentSocket.wantAhead('work')
  agentSocket.start()
  ws.hear({ type: 'hello', connectionId: 'c1', protocol: 1 })
  return ws
}

const prepared = (id: string, expiresAt: number) => ({
  type: 'prepared',
  id,
  spaceId: 'work',
  title: 'Monday',
  content: '',
  overlays: [],
  expiresAt,
})

afterEach(() => {
  agentSocket.reset()
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('a result done ahead near its end', () => {
  it('is let go, and the next asked for once the server has let it go too, not over and over', () => {
    vi.useFakeTimers()
    const ws = connect()
    expect(ws.prepares()).toBe(1)
    const expiresAt = Date.now() + 60_000
    ws.hear(prepared('run_1', expiresAt))

    vi.advanceTimersByTime(60_000 - aheadTiming.marginMs)
    expect(agentSocket.takePrepared('work')).toBeNull()
    expect(ws.prepares()).toBe(1)
    // The server still holds it, and hands it back: kept out, and nothing asked meanwhile.
    ws.hear(prepared('run_1', expiresAt))
    expect(agentSocket.takePrepared('work')).toBeNull()
    vi.advanceTimersByTime(aheadTiming.marginMs + aheadTiming.againMs - 1)
    expect(ws.prepares()).toBe(1)
    vi.advanceTimersByTime(1)
    expect(ws.prepares()).toBe(2)

    ws.hear(prepared('run_2', Date.now() + 60_000))
    expect(agentSocket.takePrepared('work')?.id).toBe('run_2')
  })

  it('is never kept when it comes already too near its end', () => {
    vi.useFakeTimers()
    const ws = connect()
    ws.hear(prepared('run_1', Date.now() + aheadTiming.marginMs - 1))
    expect(agentSocket.takePrepared('work')).toBeNull()
    expect(ws.prepares()).toBe(1)
    vi.advanceTimersByTime(aheadTiming.marginMs + aheadTiming.againMs)
    expect(ws.prepares()).toBe(2)
  })
})

describe('an ask the server never answers', () => {
  it('is made again, waiting longer each time, until a result comes', () => {
    vi.useFakeTimers()
    const ws = connect()
    expect(ws.prepares()).toBe(1)
    vi.advanceTimersByTime(aheadTiming.unansweredMs - 1)
    expect(ws.prepares()).toBe(1)
    vi.advanceTimersByTime(1)
    expect(ws.prepares()).toBe(2)
    vi.advanceTimersByTime(aheadTiming.unansweredMs * 2 - 1)
    expect(ws.prepares()).toBe(2)
    vi.advanceTimersByTime(1)
    expect(ws.prepares()).toBe(3)

    ws.hear(prepared('run_1', Date.now() + 30 * 60_000))
    vi.advanceTimersByTime(10 * 60_000)
    expect(ws.prepares()).toBe(3)
  })
})
