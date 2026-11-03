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
      expect(runs[0]?.edits[0]?.blocked).toBe(undefined)
    })

    // One step of its own: the user's typing stays.
    await user.keyboard('{Control>}z{/Control}')
    expect(bodyText()).toBe('Items and more')
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

    first.dispatchEvent(new MessageEvent('message', { data: JSON.stringify({ type: 'read', id: 's1', noteId: 'n1' }) }))
    await waitFor(() => expect(first.sent.at(-1)).toEqual({ type: 'result', id: 's1', error: 'not_open' }))

    first.drop()
    await waitFor(() => expect(FakeSocket.all).toHaveLength(2))
    const second = FakeSocket.all[1]
    second?.hello()
    expect(second?.sent).toEqual([{ type: 'editor.opened', noteId: 'n1' }])
  })
})
