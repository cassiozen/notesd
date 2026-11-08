import { waitFor } from '@testing-library/react'
import { describe, expect, it, onTestFinished } from 'vitest'
import { agentSocket, socketFactory, socketTiming } from '../src/agent/socket'
import type { AgentRun } from '../src/api/agent'
import { bodyText, createNote, getNote, openNote, signedIn, typeAtEnd } from './helpers'
import { startFakeLLM } from './server'

describe('the agent socket', () => {
  it('ends a writing session as soon as the note is closed', async () => {
    const llm = await startFakeLLM()
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        await s.api('PUT', '/settings/ai', { baseUrl: llm.url, apiKey: 'sk-test-1234', model: 'm' })
        await s.api('PUT', '/agent/spaces/default', { enabled: true, prompt: 'p', triggers: { editDone: 'Tidy up.' } })
        id = await createNote(s, 'Plan', 'Step one')
        await createNote(s, 'Other', 'x')
      },
    })
    llm.script.push({ text: 'No change.' })
    await openNote(user, 'Plan')
    await typeAtEnd(user, ', step two')
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('Step one, step two\n'))

    // Long before the two idle minutes that end it otherwise.
    await openNote(user, 'Other')
    await waitFor(
      async () => {
        const { runs } = (await server.api<{ runs: AgentRun[] }>('GET', '/agent/runs')).body
        expect(runs.map((r) => [r.trigger, r.noteId])).toEqual([['editDone', id]])
      },
      { timeout: 5000 },
    )
  })
})

describe('a run on an open note', () => {
  it('changes it in the editor, where Undo takes it back', async () => {
    const llm = await startFakeLLM()
    let digest = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        await s.api('PUT', '/settings/ai', { baseUrl: llm.url, apiKey: 'sk-test-1234', model: 'm' })
        await s.api('PUT', '/agent/spaces/default', { enabled: true, prompt: 'p', triggers: { noteDeleted: 'Add it to Digest.' } })
        digest = await createNote(s, 'Digest', 'Items')
        await s.api('POST', '/notes', { operationId: 'op-milk', id: 'milk', title: 'Milk', content: 'Milk' })
      },
    })
    await openNote(user, 'Digest')
    // Typed, not saved yet: the run sees it.
    await typeAtEnd(user, ' and more')
    llm.script.push({ tool: 'edit_note', args: { id: digest, find: 'more', replace: 'more\n\n- Milk' } }, { text: 'Added Milk.' })
    await server.api('DELETE', '/notes/milk', { operationId: 'op-del', baseRevision: 1 })

    await waitFor(() => expect(bodyText()).toBe('Items and moreMilk'), { timeout: 5000 })
    await waitFor(async () => expect((await getNote(server, digest)).content).toBe('Items and more\n\n- Milk\n'))
    await waitFor(async () => {
      const { runs } = (await server.api<{ runs: AgentRun[] }>('GET', '/agent/runs')).body
      expect(runs[0]?.edits).toHaveLength(1)
      expect(runs[0]?.edits[0]?.blocked).toBe(undefined)
    })

    // One step of its own: the user's typing stays.
    await user.keyboard('{Control>}z{/Control}')
    expect(bodyText()).toBe('Items and more')
  })

  it('makes its change to what the user typed while it worked, without starting over', async () => {
    const llm = await startFakeLLM()
    let digest = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        await s.api('PUT', '/settings/ai', { baseUrl: llm.url, apiKey: 'sk-test-1234', model: 'm' })
        await s.api('PUT', '/agent/spaces/default', { enabled: true, prompt: 'p', triggers: { noteDeleted: 'Add it to Digest.' } })
        digest = await createNote(s, 'Digest', 'Items')
        await s.api('POST', '/notes', { operationId: 'op-milk', id: 'milk', title: 'Milk', content: 'Milk' })
      },
    })
    await openNote(user, 'Digest')
    let answer: (() => void) | undefined
    const thinking = new Promise<void>((r) => (answer = r))
    llm.script.push({ tool: 'insert', args: { id: digest, at: 'end', text: '- Milk', done: 'Added Milk.' }, hold: thinking })
    await server.api('DELETE', '/notes/milk', { operationId: 'op-del', baseRevision: 1 })

    // The run read "Items"; the user types on while the model thinks.
    await waitFor(() => expect(llm.requests.length).toBe(1), { timeout: 5000 })
    await typeAtEnd(user, ' and more')
    answer?.()

    await waitFor(() => expect(bodyText()).toBe('Items and moreMilk'), { timeout: 5000 })
    await waitFor(async () => {
      const { runs } = (await server.api<{ runs: AgentRun[] }>('GET', '/agent/runs')).body
      expect(runs[0]?.status).toBe('done')
    })
    expect(llm.requests.length).toBe(1)
  })
})

class FakeSocket extends EventTarget {
  static all: FakeSocket[] = []
  readyState: number = WebSocket.CONNECTING
  sent: unknown[] = []
  constructor() {
    super()
    FakeSocket.all.push(this)
  }
  send(data: string) {
    this.sent.push(JSON.parse(data))
  }
  close() {
    this.drop()
  }
  hello() {
    this.readyState = WebSocket.OPEN
    this.dispatchEvent(new MessageEvent('message', { data: JSON.stringify({ type: 'hello', connectionId: 'c1', protocol: 1 }) }))
  }
  drop() {
    this.readyState = WebSocket.CLOSED
    this.dispatchEvent(new Event('close'))
  }
}

describe('the agent socket, connecting', () => {
  it('reconnects after a drop and says again which notes are open', async () => {
    const make = socketFactory.make
    const base = socketTiming.retryBaseMs
    socketFactory.make = () => new FakeSocket() as unknown as WebSocket
    socketTiming.retryBaseMs = 1
    onTestFinished(() => {
      agentSocket.noteClosed('n1', null)
      agentSocket.stop()
      socketFactory.make = make
      socketTiming.retryBaseMs = base
      FakeSocket.all = []
    })
    agentSocket.start()
    agentSocket.noteOpened('n1')
    const [first] = FakeSocket.all
    if (!first) throw new Error('no socket')
    first.hello()
    expect(first.sent).toEqual([{ type: 'editor.opened', noteId: 'n1' }])
    agentSocket.noteOpened('n2')
    agentSocket.noteClosed('n2', 3)
    expect(first.sent.slice(1)).toEqual([
      { type: 'editor.opened', noteId: 'n2' },
      { type: 'editor.closed', noteId: 'n2', revision: 3 },
    ])
    // Reopened before the close (it waits on the save) lands: still open.
    agentSocket.noteOpened('n2')
    agentSocket.noteOpened('n2')
    agentSocket.noteClosed('n2', 3)
    expect(first.sent.slice(3).map((m) => (m as { type: string }).type)).toEqual(['editor.opened', 'editor.opened'])
    agentSocket.noteClosed('n2', 4)
    expect(first.sent.at(-1)).toEqual({ type: 'editor.closed', noteId: 'n2', revision: 4 })

    first.dispatchEvent(new MessageEvent('message', { data: JSON.stringify({ type: 'read', id: 's1', noteId: 'n1' }) }))
    await waitFor(() => expect(first.sent.at(-1)).toEqual({ type: 'result', id: 's1', error: 'not_open' }))

    const run = (status: string) =>
      first.dispatchEvent(new MessageEvent('message', { data: JSON.stringify({ type: 'run', runId: 'r1', noteId: 'n1', trigger: 'menu', status }) }))
    run('running')
    expect(agentSocket.isWriting('n1')).toBe(true)
    run('done')
    expect(agentSocket.isWriting('n1')).toBe(false)
    run('running')

    // Asked just before the drop: its answer must not reach the next connection.
    let answer: ((v: object) => void) | undefined
    const stop = agentSocket.serve('n1', { read: () => ({}), apply: () => new Promise((r) => (answer = r)) })
    onTestFinished(stop)
    const fields = { title: 'T', content: 'x' }
    first.dispatchEvent(
      new MessageEvent('message', { data: JSON.stringify({ type: 'apply', id: 's2', noteId: 'n1', before: fields, after: fields }) }),
    )
    first.drop()
    await waitFor(() => expect(FakeSocket.all).toHaveLength(2))
    const second = FakeSocket.all[1]
    // What was going is unknown once the connection drops.
    expect(agentSocket.isWriting('n1')).toBe(false)
    second?.hello()
    answer?.({})
    await new Promise((r) => setTimeout(r, 0))
    expect(second?.sent).toEqual([{ type: 'editor.opened', noteId: 'n1' }])
  })

  it('closes once the notes open here have said they closed', async () => {
    const make = socketFactory.make
    const wait = socketTiming.closeWaitMs
    socketFactory.make = () => new FakeSocket() as unknown as WebSocket
    onTestFinished(() => {
      agentSocket.stop()
      socketFactory.make = make
      socketTiming.closeWaitMs = wait
      FakeSocket.all = []
    })
    agentSocket.start()
    agentSocket.noteOpened('n1')
    const [socket] = FakeSocket.all
    if (!socket) throw new Error('no socket')
    socket.hello()

    // Left for a space without an agent: n1's close, sent after its last save, still gets there.
    agentSocket.stop()
    expect(socket.readyState).toBe(WebSocket.OPEN)
    agentSocket.noteClosed('n1', 4)
    expect(socket.sent.at(-1)).toEqual({ type: 'editor.closed', noteId: 'n1', revision: 4 })
    expect(socket.readyState).toBe(WebSocket.CLOSED)

    // Back before it closed: the same socket carries on.
    agentSocket.start()
    agentSocket.noteOpened('n2')
    const [, again] = FakeSocket.all
    again?.hello()
    agentSocket.stop()
    agentSocket.start()
    expect(FakeSocket.all).toHaveLength(2)
    expect(again?.readyState).toBe(WebSocket.OPEN)

    // A close that never comes: it closes anyway.
    socketTiming.closeWaitMs = 1
    agentSocket.stop()
    await waitFor(() => expect(again?.readyState).toBe(WebSocket.CLOSED))
    agentSocket.noteClosed('n2', null)

    // Stopped twice while waiting, then started again: the first wait doesn't close the next socket.
    socketTiming.closeWaitMs = 20
    agentSocket.start()
    agentSocket.noteOpened('n3')
    const third = FakeSocket.all.at(-1)
    third?.hello()
    agentSocket.stop()
    agentSocket.stop()
    agentSocket.start()
    await new Promise((r) => setTimeout(r, 60))
    expect(third?.readyState).toBe(WebSocket.OPEN)
    agentSocket.noteClosed('n3', null)
  })

  it('sends nothing for a note closed after a reset', () => {
    const make = socketFactory.make
    socketFactory.make = () => new FakeSocket() as unknown as WebSocket
    onTestFinished(() => {
      agentSocket.stop()
      socketFactory.make = make
      FakeSocket.all = []
    })
    agentSocket.start()
    agentSocket.noteOpened('n1')
    agentSocket.reset()
    agentSocket.start()
    const socket = FakeSocket.all.at(-1)
    if (!socket) throw new Error('no socket')
    socket.hello()
    // The editor's close waits on its save, so it can land once the next account's socket is up.
    agentSocket.noteClosed('n1', 2)
    expect(socket.sent).toEqual([])
  })

  it('forgets what was running when stopped', () => {
    const make = socketFactory.make
    socketFactory.make = () => new FakeSocket() as unknown as WebSocket
    onTestFinished(() => {
      agentSocket.stop()
      socketFactory.make = make
      FakeSocket.all = []
    })
    agentSocket.start()
    const socket = FakeSocket.all.at(-1)
    if (!socket) throw new Error('no socket')
    socket.hello()
    const running = { type: 'run', runId: 'r1', noteId: 'n1', trigger: 'menu', status: 'running' }
    socket.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(running) }))
    expect(agentSocket.isWriting('n1')).toBe(true)
    // The agent turned off and on again: the run's end went to a socket no longer listened to.
    agentSocket.stop()
    agentSocket.start()
    expect(agentSocket.isWriting('n1')).toBe(false)
  })

  it('holds a run asked for while connecting until the server says hello', async () => {
    const make = socketFactory.make
    const { heldMs, retryBaseMs } = socketTiming
    socketFactory.make = () => new FakeSocket() as unknown as WebSocket
    socketTiming.retryBaseMs = 1
    onTestFinished(() => {
      agentSocket.noteClosed('n1', null)
      agentSocket.stop()
      socketFactory.make = make
      Object.assign(socketTiming, { heldMs, retryBaseMs })
      FakeSocket.all = []
    })
    expect(agentSocket.requestRun('menu', 'n1', 's1')).toBe(false) // no socket wanted

    agentSocket.start()
    agentSocket.noteOpened('n1')
    const shown = { spaceId: 's1', title: 'T', content: 'Typed', cursor: 5, revision: null }
    const stop = agentSocket.serve('n1', { read: () => shown, apply: () => Promise.resolve({}) })
    onTestFinished(stop)
    expect(agentSocket.requestRun('noteCreated', 'n1', 's1')).toBe(true)
    expect(agentSocket.requestRun('noteCreated', 'n2', 's1')).toBe(true) // closed before hello
    const [first] = FakeSocket.all
    if (!first) throw new Error('no socket')
    expect(first.sent).toEqual([])
    first.hello()
    expect(first.sent).toEqual([
      { type: 'editor.opened', noteId: 'n1' },
      { type: 'run.request', trigger: 'noteCreated', noteId: 'n1', spaceId: 's1', note: shown },
    ])

    // Asked while reconnecting, but too long ago by the time it's back.
    socketTiming.heldMs = -1
    first.drop()
    expect(agentSocket.requestRun('editStarted', 'n1', 's1')).toBe(true)
    await waitFor(() => expect(FakeSocket.all).toHaveLength(2))
    const again = FakeSocket.all[1]
    again?.hello()
    expect(again?.sent).toEqual([{ type: 'editor.opened', noteId: 'n1' }])
  })
})

describe('the agent socket, stopped while connecting', () => {
  it('closes without an uncaught error', async () => {
    // Signed in: the factory makes Node's WebSocket, which throws where a browser only logs.
    await signedIn()
    const uncaught: unknown[] = []
    const onUncaught = (err: unknown) => uncaught.push(err)
    process.on('uncaughtException', onUncaught)
    onTestFinished(() => {
      process.off('uncaughtException', onUncaught)
      agentSocket.stop()
    })
    agentSocket.start()
    agentSocket.stop()
    await new Promise((r) => setTimeout(r, 100))
    expect(uncaught).toEqual([])
  })
})
