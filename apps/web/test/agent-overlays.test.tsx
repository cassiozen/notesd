import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { AgentConfigInput, AgentRun } from '../src/api/agent'
import { bodyText, createNote, getNote, newNoteButton, openNote, signedIn, typeAtEnd } from './helpers'
import { startFakeLLM, type FakeLLM, type TestServer } from './server'

async function withAgent(s: TestServer, llm: FakeLLM, config: Partial<AgentConfigInput>) {
  await s.api('PUT', '/settings/ai', { baseUrl: llm.url, apiKey: 'sk-test-1234', model: 'm' })
  await s.api('PUT', '/agent/spaces/default', { enabled: true, prompt: 'p', ...config })
}

async function runs(server: TestServer) {
  return (await server.api<{ runs: AgentRun[] }>('GET', '/agent/runs')).body.runs
}

async function connected() {
  const { agentSocket } = await import('../src/agent/socket')
  await waitFor(() => expect(agentSocket.connectionId).not.toBeNull())
}

async function fixTypos(user: Awaited<ReturnType<typeof signedIn>>['user']) {
  await user.click(screen.getByRole('button', { name: 'More' }))
  await user.click(within(screen.getByRole('menu', { name: 'More' })).getByRole('menuitem', { name: 'Fix typos' }))
}

describe('what a run shows beside the note', () => {
  it('a card to read, beside the new note and not in it, goes with ×', async () => {
    const llm = await startFakeLLM()
    const { server, user } = await signedIn({ seed: (s) => withAgent(s, llm, { triggers: { noteCreated: 'Ask a question.' } }) })
    await connected()
    llm.script.push({ tool: 'show_card', args: { body: 'What **made** you smile today?', done: 'Asked.' } })
    await user.click(newNoteButton())
    const card = await screen.findByRole('note', { name: /agent: a note$/ }, { timeout: 5000 })
    expect(card.textContent).toContain('What made you smile today?')
    expect(bodyText()).toBe('')

    await user.click(within(card).getByRole('button', { name: 'Dismiss' }))
    expect(screen.queryByRole('note', { name: /agent: a note$/ })).toBeNull()
    await waitFor(async () => {
      const [run] = await runs(server)
      expect(run?.outcome).toBe('no_change')
      expect(run?.overlays).toEqual({ cards: 1, suggestions: 0, accepted: 0 })
    })
  })

  it('a suggestion’s ✓ makes the change as the run would have, Undo and all; ✗ leaves the note as it is', async () => {
    const llm = await startFakeLLM()
    let id = ''
    const { server, user } = await signedIn({
      seed: async (s) => {
        await withAgent(s, llm, { menuLabel: 'Fix typos', triggers: { menu: 'Fix typos.' } })
        id = await createNote(s, 'Plan', 'Teh plan is godo')
      },
    })
    await connected()
    await openNote(user, 'Plan')
    llm.script.push({
      tool: 'suggest',
      args: {
        changes: [
          { find: 'Teh', replace: 'The', why: 'Typo' },
          { find: 'godo', replace: 'good', why: 'Typo' },
        ],
        done: 'Suggested 2 fixes.',
      },
    })
    await fixTypos(user)
    await waitFor(() => expect(screen.getAllByRole('note', { name: /agent: a suggested change$/ })).toHaveLength(2), { timeout: 5000 })
    const [teh, godo] = screen.getAllByRole('note', { name: /agent: a suggested change$/ })
    if (!teh || !godo) throw new Error('no cards')
    expect(teh.querySelector('del')?.textContent).toBe('Teh')
    expect(teh.querySelector('ins')?.textContent).toBe('The')

    await user.click(within(teh).getByRole('button', { name: 'Accept the change' }))
    expect(bodyText()).toBe('The plan is godo')
    await user.click(within(godo).getByRole('button', { name: 'Reject the change' }))
    expect(screen.queryAllByRole('note')).toHaveLength(0)
    expect(bodyText()).toBe('The plan is godo')
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('The plan is godo\n'))
    await waitFor(async () => {
      const [run] = await runs(server)
      expect(run?.outcome).toBe('edited')
      expect(run?.overlays).toEqual({ cards: 0, suggestions: 2, accepted: 1 })
      expect(run?.edits).toHaveLength(1)
    })

    // As for a change the run made itself.
    await user.click(screen.getByRole('button', { name: 'Undo the agent’s change' }))
    expect(bodyText()).toBe('Teh plan is godo')
  })

  it('a suggestion goes once accepting another changed its text', async () => {
    const llm = await startFakeLLM()
    const { user } = await signedIn({
      seed: async (s) => {
        await withAgent(s, llm, { menuLabel: 'Fix typos', triggers: { menu: 'Fix typos.' } })
        await createNote(s, 'Plan', 'Teh plan')
      },
    })
    await connected()
    await openNote(user, 'Plan')
    llm.script.push({
      tool: 'suggest',
      args: { changes: [{ find: 'Teh plan', replace: 'The plan' }, { find: 'Teh', replace: 'The' }], done: 'Suggested.' },
    })
    await fixTypos(user)
    await waitFor(() => expect(screen.getAllByRole('note')).toHaveLength(2), { timeout: 5000 })
    const [whole] = screen.getAllByRole('note')
    if (!whole) throw new Error('no card')
    await user.click(within(whole).getByRole('button', { name: 'Accept the change' }))
    expect(bodyText()).toBe('The plan')
    await waitFor(() => expect(screen.queryByRole('note')).toBeNull(), { timeout: 3000 })
  })

  it('a suggestion goes once the user’s typing means it can’t be made', async () => {
    const llm = await startFakeLLM()
    const { user } = await signedIn({
      seed: async (s) => {
        await withAgent(s, llm, { menuLabel: 'Fix typos', triggers: { menu: 'Fix typos.' } })
        await createNote(s, 'Plan', 'Teh plan')
      },
    })
    await connected()
    await openNote(user, 'Plan')
    llm.script.push({ tool: 'suggest', args: { changes: [{ find: 'Teh', replace: 'The' }], done: 'Suggested.' } })
    await fixTypos(user)
    await screen.findByRole('note', { name: /agent: a suggested change$/ }, { timeout: 5000 })
    // Twice in the note: which to change isn't clear any more.
    await typeAtEnd(user, ' Teh')
    await waitFor(() => expect(screen.queryByRole('note')).toBeNull(), { timeout: 3000 })
  })
})
