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

describe('editor triggers', () => {
  it('note created: the agent writes in the new note, and Undo takes it back', async () => {
    const llm = await startFakeLLM()
    const { user } = await signedIn({ seed: (s) => withAgent(s, llm, { triggers: { noteCreated: 'Ask a question.' } }) })
    await connected()
    llm.script.push({ tool: 'insert', args: { at: 'start', text: '## What went well today?' } }, { text: 'Asked.' })
    await user.click(newNoteButton())
    await waitFor(() => expect(bodyText()).toBe('What went well today?'), { timeout: 5000 })
    await user.keyboard('{Control>}z{/Control}')
    expect(bodyText()).toBe('')
  })

  it('Agent is writing… ends once the change shows, before the run does', async () => {
    const llm = await startFakeLLM()
    const { server, user } = await signedIn({ seed: (s) => withAgent(s, llm, { triggers: { noteCreated: 'Ask a question.' } }) })
    await connected()
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
    await connected()
    llm.script.push({ tool: 'insert', args: { at: 'start', text: '## What went well today?' } }, { text: 'Asked.' })
    await user.click(newNoteButton())
    await waitFor(() => expect(bodyText()).toBe('What went well today?'), { timeout: 5000 })
    // The text shows before the run ends.
    await waitFor(async () => expect((await runs(server)).map((r) => [r.trigger, r.status])).toEqual([['noteCreated', 'done']]), {
      timeout: 5000,
    })
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
