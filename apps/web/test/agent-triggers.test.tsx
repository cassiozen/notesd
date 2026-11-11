import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, onTestFinished, vi } from 'vitest'
import type { AgentConfigInput, AgentRun } from '../src/api/agent'
import { bodyText, createNote, newNoteButton, openNote, signedIn, typeAtEnd } from './helpers'
import { startFakeLLM, type FakeLLM, type TestServer } from './server'

async function withAgent(s: TestServer, llm: FakeLLM, config: Partial<AgentConfigInput>) {
  await s.api('PUT', '/settings/ai', { baseUrl: llm.url, apiKey: 'sk-test-1234', model: 'm' })
  await s.api('PUT', '/agent/spaces/default', { enabled: true, prompt: 'A journal.', ...config })
}

async function runs(server: TestServer) {
  return (await server.api<{ runs: AgentRun[] }>('GET', '/agent/runs')).body.runs
}

// The socket has said hello: editor triggers need it.
async function connected() {
  const { agentSocket } = await import('../src/agent/socket')
  await waitFor(() => expect(agentSocket.connectionId).not.toBeNull())
}

// The new note run done ahead, as the space opened, is here.
async function aheadReady() {
  const { agentSocket } = await import('../src/agent/socket')
  return await waitFor(
    () => {
      const p = agentSocket['prepared'].get('default')
      if (!p) throw new Error('not done ahead yet')
      return p
    },
    { timeout: 5000 },
  )
}

// A new note runs live, as one made before its run done ahead is back.
async function live() {
  await aheadReady()
  const { agentSocket } = await import('../src/agent/socket')
  agentSocket['dropPrepared']()
}

describe('editor triggers', () => {
  it('note created: the agent writes in the new note, and Undo takes it back', async () => {
    const llm = await startFakeLLM()
    const { user } = await signedIn({ seed: (s) => withAgent(s, llm, { triggers: { noteCreated: 'Ask a question.' } }) })
    await live()
    llm.script.push({ tool: 'insert', args: { at: 'start', text: '## What went well today?' } }, { text: 'Asked.' })
    await user.click(newNoteButton())
    await waitFor(() => expect(bodyText()).toBe('What went well today?'), { timeout: 5000 })
    await user.keyboard('{Control>}z{/Control}')
    expect(bodyText()).toBe('')
  })

  it('Agent is writing… ends once the change shows, before the run does', async () => {
    const llm = await startFakeLLM()
    const { server, user } = await signedIn({ seed: (s) => withAgent(s, llm, { triggers: { noteCreated: 'Ask a question.' } }) })
    await live()
    const finished: { resolve?: () => void } = {}
    const wait = new Promise<void>((resolve) => (finished.resolve = resolve))
    llm.script.push({ tool: 'insert', args: { at: 'start', text: '## What went well today?' } }, { text: 'Asked.', hold: wait })
    await user.click(newNoteButton())
    await screen.findByText('Agent is writing…')
    await waitFor(() => expect(bodyText()).toBe('What went well today?'), { timeout: 5000 })
    await waitFor(() => expect(screen.queryByText('Agent is writing…')).toBeNull())
    expect((await runs(server)).map((r) => r.status)).toEqual(['running'])
    finished.resolve?.()
    await waitFor(async () => expect((await runs(server)).map((r) => r.status)).toEqual(['done']), { timeout: 5000 })
  })

  it('note created, as dev builds mount effects twice', async () => {
    const llm = await startFakeLLM()
    const { server, user } = await signedIn({ strict: true, seed: (s) => withAgent(s, llm, { triggers: { noteCreated: 'Ask a question.' } }) })
    await live()
    llm.script.push({ tool: 'insert', args: { at: 'start', text: '## What went well today?' } }, { text: 'Asked.' })
    await user.click(newNoteButton())
    await waitFor(() => expect(bodyText()).toBe('What went well today?'), { timeout: 5000 })
    // The text shows before the run ends.
    await waitFor(async () => expect((await runs(server)).map((r) => [r.trigger, r.status])).toEqual([['noteCreated', 'done']]), {
      timeout: 5000,
    })
  })

  it('note created, done ahead: the new note opens with it, and Activity has it', async () => {
    const llm = await startFakeLLM()
    // Done as the space opens, before any note.
    llm.script.push({ tool: 'insert', args: { at: 'start', text: '## What went well today?', done: 'Asked.' } })
    const { server, user } = await signedIn({ seed: (s) => withAgent(s, llm, { triggers: { noteCreated: 'Ask a question.' } }) })
    const first = (await aheadReady()).id
    expect(await runs(server)).toEqual([])
    await user.click(newNoteButton())
    // At once: nothing to wait for.
    expect(bodyText()).toBe('What went well today?')
    expect(screen.queryByText('Agent is writing…')).toBeNull()
    await waitFor(async () => expect((await runs(server)).map((r) => [r.trigger, r.status, r.outcome])).toEqual([['noteCreated', 'done', 'edited']]))
    // Saved as the agent's change, which its run can undo.
    await waitFor(async () => {
      const [run] = await runs(server)
      const detail = await server.api<{ edits: { blocked?: string }[] }>('GET', `/agent/runs/${run?.id ?? ''}`)
      expect(detail.body.edits.map((e) => e.blocked ?? '')).toEqual([''])
    }, { timeout: 5000 })
    // The next new note's is done in turn.
    await waitFor(async () => expect((await aheadReady()).id).not.toBe(first), { timeout: 5000 })
  })

  it('a result done ahead the server no longer has: the new note starts over empty, and the run goes live', async () => {
    const llm = await startFakeLLM()
    llm.script.push({ tool: 'insert', args: { at: 'start', text: '## Ahead', done: 'Asked.' } })
    const { server, user } = await signedIn({ seed: (s) => withAgent(s, llm, { triggers: { noteCreated: 'Ask a question.' } }) })
    const p = await aheadReady()
    p.id = 'run_gone'
    llm.script.push({ tool: 'insert', args: { at: 'start', text: '## Live', done: 'Asked.' } })
    await user.click(newNoteButton())
    await waitFor(() => expect(bodyText()).toBe('Live'), { timeout: 5000 })
    await waitFor(async () => expect((await runs(server)).map((r) => [r.trigger, r.status])).toEqual([['noteCreated', 'done']]))
  })

  it('a result done ahead is done again once what it went by changes', async () => {
    const { aheadTiming } = await import('../src/agent/socket')
    const again = aheadTiming.againMs
    aheadTiming.againMs = 50
    onTestFinished(() => {
      aheadTiming.againMs = again
    })
    const llm = await startFakeLLM()
    const { server } = await signedIn({
      seed: (s) => withAgent(s, llm, { referenceNotes: ['Digest'], triggers: { noteCreated: 'Ask a question.' } }),
    })
    const first = (await aheadReady()).id
    await createNote(server, 'Something else', 'Not read')
    await new Promise((r) => setTimeout(r, 300))
    expect((await aheadReady()).id).toBe(first)
    await createNote(server, 'Digest', 'So far')
    await waitFor(async () => expect((await aheadReady()).id).not.toBe(first), { timeout: 5000 })
  })

  it('a result done ahead thrown away while still going is asked for again', async () => {
    const { aheadTiming } = await import('../src/agent/socket')
    const again = aheadTiming.againMs
    aheadTiming.againMs = 50
    onTestFinished(() => {
      aheadTiming.againMs = again
    })
    const llm = await startFakeLLM()
    const finished: { resolve?: () => void } = {}
    const wait = new Promise<void>((resolve) => (finished.resolve = resolve))
    llm.script.push({ text: 'No change.', hold: wait })
    const { server } = await signedIn({ seed: (s) => withAgent(s, llm, { triggers: { noteCreated: 'Ask a question.' } }) })
    await waitFor(() => expect(llm.requests).toHaveLength(1), { timeout: 5000 })
    // Set up again while it runs: that one's thrown away, and the tab hears of it.
    await server.api('PUT', '/agent/spaces/default', { enabled: true, prompt: 'A diary.', triggers: { noteCreated: 'Ask a question.' } })
    finished.resolve?.()
    expect((await aheadReady()).id).toBeTruthy()
    expect(llm.requests.length).toBeGreaterThanOrEqual(2)
  })

  it('started editing fires once, on the first change', async () => {
    const llm = await startFakeLLM()
    const { server, user } = await signedIn({
      seed: async (s) => {
        await withAgent(s, llm, { triggers: { editStarted: 'Note the time.' } })
        await createNote(s, 'Plan', 'Step one')
      },
    })
    await connected()
    await openNote(user, 'Plan')
    await typeAtEnd(user, ', two')
    await typeAtEnd(user, ', three')
    await waitFor(async () => expect((await runs(server)).map((r) => [r.trigger, r.status])).toEqual([['editStarted', 'done']]), {
      timeout: 5000,
    })
  })

  it('started editing fires again after a pause', async () => {
    const { triggerTiming } = await import('../src/agent/triggers')
    const idle = triggerTiming.idleMs
    // Longer than a keystroke takes under load.
    triggerTiming.idleMs = 600
    onTestFinished(() => {
      triggerTiming.idleMs = idle
    })
    const llm = await startFakeLLM()
    const { server, user } = await signedIn({
      seed: async (s) => {
        await withAgent(s, llm, { triggers: { editStarted: 'Note the time.' } })
        await createNote(s, 'Plan', 'Step one')
      },
    })
    await connected()
    await openNote(user, 'Plan')
    await typeAtEnd(user, ', two')
    // Done first: asked again while it's still queued, it's the same run.
    await waitFor(async () => expect((await runs(server)).map((r) => r.status)).toEqual(['done']), { timeout: 5000 })
    // Well past idleMs: under load the editor handles the last keystroke late.
    await new Promise((r) => setTimeout(r, 1500))
    await typeAtEnd(user, ', three')
    await waitFor(async () => expect((await runs(server)).map((r) => r.trigger)).toEqual(['editStarted', 'editStarted']), { timeout: 5000 })
  })

  it('the menu command runs, and a failure shows on the note as it happens', async () => {
    const llm = await startFakeLLM()
    const { user } = await signedIn({
      seed: async (s) => {
        await withAgent(s, llm, { menuLabel: 'Fix typos', triggers: { menu: 'Fix typos.' } })
        await createNote(s, 'Plan', 'Teh plan')
      },
    })
    await connected()
    await openNote(user, 'Plan')
    // More steps than a run may take.
    for (let i = 0; i < 21; i++) llm.script.push({ tool: 'list_tags', args: {} })
    await user.click(screen.getByRole('button', { name: 'More' }))
    await user.click(within(screen.getByRole('menu', { name: 'More' })).getByRole('menuitem', { name: 'Fix typos' }))
    expect(await screen.findByText('The agent’s last run on this note failed: it took too many steps.', {}, { timeout: 5000 })).toBeTruthy()
  })

  it('the menu command says so when it can’t reach the agent', async () => {
    const llm = await startFakeLLM()
    const { agentSocket, socketTiming } = await import('../src/agent/socket')
    const { heldMs } = socketTiming
    socketTiming.heldMs = 50
    onTestFinished(() => {
      socketTiming.heldMs = heldMs
    })
    const { user } = await signedIn({
      seed: async (s) => {
        await withAgent(s, llm, { menuLabel: 'Fix typos', triggers: { menu: 'Fix typos.' } })
        await createNote(s, 'Plan', 'Teh plan')
      },
    })
    await connected()
    await openNote(user, 'Plan')
    // Offline: the connection drops, and isn't tried again until it's back.
    const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    onTestFinished(() => online.mockRestore())
    agentSocket['ws']?.close()
    await waitFor(() => expect(agentSocket.connectionId).toBeNull())
    await user.click(screen.getByRole('button', { name: 'More' }))
    await user.click(within(screen.getByRole('menu', { name: 'More' })).getByRole('menuitem', { name: 'Fix typos' }))
    expect(await screen.findByText('The agent didn’t run: you’re offline.')).toBeTruthy()
    expect(llm.requests).toHaveLength(0)
  })

  it('aren’t offered where the agent doesn’t act on them', async () => {
    const llm = await startFakeLLM()
    const { user } = await signedIn({
      seed: async (s) => {
        await withAgent(s, llm, { triggers: { editDone: 'Tidy up.' } })
        await createNote(s, 'Plan', 'Step one')
      },
    })
    await connected()
    const { agentSocket } = await import('../src/agent/socket')
    const ask = vi.spyOn(agentSocket, 'requestRun')
    onTestFinished(() => ask.mockRestore())
    await openNote(user, 'Plan')
    await typeAtEnd(user, ', two')
    await user.click(screen.getByRole('button', { name: 'More' }))
    expect(within(screen.getByRole('menu', { name: 'More' })).queryAllByRole('menuitem').map((b) => b.textContent)).not.toContain('Fix typos')
    expect(ask).not.toHaveBeenCalled()
  })
})
