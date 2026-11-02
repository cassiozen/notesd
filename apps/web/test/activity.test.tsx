import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { AgentRun } from '../src/api/agent'
import { signedIn } from './helpers'
import { startFakeLLM, type FakeLLM, type TestServer } from './server'

const DIGEST = 'Items\n'

/** AI and an agent that, when a note is deleted, adds a line to Digest. */
async function withDigestAgent(server: TestServer, llm: FakeLLM) {
  await server.api('PUT', '/settings/ai', { baseUrl: llm.url, apiKey: 'sk-test-1234', model: 'm' })
  await server.api('PUT', '/agent/spaces/default', {
    enabled: true,
    prompt: 'A shopping list.',
    triggers: { noteDeleted: 'Add what the note said to Digest.' },
  })
  await server.api('POST', '/notes', { operationId: 'op-digest', id: 'digest', title: 'Digest', content: DIGEST })
  await server.api('POST', '/notes', { operationId: 'op-milk', id: 'milk', title: 'Milk', content: 'Milk' })
  llm.script.push({ tool: 'edit_note', args: { id: 'digest', find: 'Items', replace: 'Items\n- Milk' } }, { text: 'Added Milk.' })
  await server.api('DELETE', '/notes/milk', { operationId: 'op-del', baseRevision: 1 })
  await waitFor(
    async () => {
      const { runs } = (await server.api<{ runs: AgentRun[] }>('GET', '/agent/runs')).body
      expect(runs[0]?.status).toBe('done')
    },
    { timeout: 5000 },
  )
}

async function openActivity(user: Awaited<ReturnType<typeof signedIn>>['user']) {
  await user.click(await screen.findByRole('button', { name: /^Space:/ }))
  await user.click(await screen.findByRole('menuitem', { name: 'Activity…' }))
  return screen.findByRole('dialog', { name: 'Activity' })
}

describe('Activity', () => {
  it('lists a run, shows what it changed, and undoes it', async () => {
    const llm = await startFakeLLM()
    const { server, user } = await signedIn({ seed: (s) => withDigestAgent(s, llm) })
    const sheet = await openActivity(user)

    const runs = await within(sheet).findByRole('list', { name: 'Runs' })
    const row = within(runs).getByRole('button', { name: /Deleted note/ })
    expect(row.textContent).toContain('Note deleted')
    expect(row.textContent).toContain('Changed the note')
    expect(within(sheet).getByText(/^Today: 24 of 1,000,000 tokens$/)).toBeTruthy()

    await user.click(row)
    const changes = await within(sheet).findByRole('region', { name: 'Changes to Digest' })
    const lines = within(changes).getByRole('list', { name: 'Line changes' })
    expect(lines.textContent).toBe('Unchanged:  ItemsAdded: +- Milk')
    expect(within(sheet).getByText('Added Milk.')).toBeTruthy()

    await user.click(within(sheet).getByRole('button', { name: 'Undo' }))
    expect(await within(sheet).findByText('Undone.')).toBeTruthy()
    expect(within(sheet).queryByRole('button', { name: 'Undo' })).toBeNull()
    expect((await server.api<{ content: string }>('GET', '/notes/digest')).body.content).toBe(DIGEST)

    await user.click(within(sheet).getByRole('button', { name: 'Back to Activity' }))
    expect((await within(sheet).findByRole('button', { name: /Deleted note/ })).textContent).toContain('Undone')
  })

  it('says why a change can’t be undone', async () => {
    const llm = await startFakeLLM()
    const { server, user } = await signedIn({
      seed: async (s) => {
        await withDigestAgent(s, llm)
        await s.api('PUT', '/notes/digest', { operationId: 'op-mine', baseRevision: 2, title: 'Digest', content: 'Mine' })
      },
    })
    const sheet = await openActivity(user)
    await user.click(await within(sheet).findByRole('button', { name: /Deleted note/ }))
    expect(await within(sheet).findByText(/^Can’t undo: The note changed since/)).toBeTruthy()
    expect(within(sheet).queryByRole('button', { name: 'Undo' })).toBeNull()
    expect((await server.api<{ content: string }>('GET', '/notes/digest')).body.content).toBe('Mine')
  })

  it('isn’t in the menu of a space without an agent', async () => {
    const { server, user } = await signedIn()
    await waitFor(() => expect(server.answered('GET', '/agent/spaces')).toBeGreaterThan(0))
    await new Promise((r) => setTimeout(r, 0))
    await user.click(await screen.findByRole('button', { name: /^Space:/ }))
    await screen.findByRole('menuitem', { name: 'New space…' })
    expect(screen.queryByRole('menuitem', { name: 'Activity…' })).toBeNull()
  })
})
