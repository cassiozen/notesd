import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { AgentRun } from '../src/api/agent'
import { openSettings, signedIn } from './helpers'
import { startFakeLLM, type FakeLLM, type TestServer } from './server'

const DIGEST = 'Items\n'

/** AI and an agent that, when a note is deleted, adds a line to Digest. */
async function withDigestAgent(server: TestServer, llm: FakeLLM) {
  // The page's, so the run's day is the one it shows usage for, even when UTC is on another.
  await server.api('PUT', '/settings/account', { timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone })
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
    // Its own request, beside the list's.
    expect(await within(sheet).findByText(/^Today: 24 of 1,000,000 tokens$/)).toBeTruthy()

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

  it('keeps focus on the space filter, and Done leaves no way Back into it', async () => {
    const llm = await startFakeLLM()
    const { user } = await signedIn({ seed: (s) => withDigestAgent(s, llm) })
    const before = window.history.length
    const sheet = await openActivity(user)
    const select = within(sheet).getByRole('combobox', { name: 'Space' })
    await user.selectOptions(select, '')
    await within(sheet).findByRole('list', { name: 'Runs' })
    expect(document.activeElement).toBe(within(sheet).getByRole('combobox', { name: 'Space' }))
    await user.click(within(sheet).getByRole('button', { name: 'Done' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Activity' })).toBeNull())
    expect(window.history.length).toBe(before + 1)
  })

  it('waits for Edit space’s changes to be saved or dropped', async () => {
    const llm = await startFakeLLM()
    const { user } = await signedIn({ seed: (s) => withDigestAgent(s, llm) })
    await openSettings(user, 'Spaces')
    const card = await screen.findByRole('region', { name: 'Spaces' })
    await user.click(await within(card).findByRole('button', { name: 'Edit Notes' }))
    const dialog = await screen.findByRole('dialog', { name: 'Edit space' })
    const activity = await within(dialog).findByRole('button', { name: 'Activity…' })
    expect((activity as HTMLButtonElement).disabled).toBe(false)
    await user.type(within(dialog).getByLabelText('Name'), '!')
    expect((activity as HTMLButtonElement).disabled).toBe(true)
    expect(within(dialog).getByText('Save or cancel first.')).toBeTruthy()
    expect((within(dialog).getByRole('button', { name: 'Edit agent…' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('isn’t in the menu of a space without an agent', async () => {
    const { server, user } = await signedIn()
    await waitFor(() => expect(server.answered('GET', '/agent/spaces')).toBeGreaterThan(0))
    await new Promise((r) => setTimeout(r, 0))
    await user.click(await screen.findByRole('button', { name: /^Space:/ }))
    await screen.findByRole('menuitem', { name: 'New space…' })
    expect(screen.queryByRole('menuitem', { name: 'Activity…' })).toBeNull()
    // Nor its agent, without AI to run one.
    expect(screen.queryByRole('menuitem', { name: 'Agent…' })).toBeNull()
  })

  it('opens the space’s agent from its menu, and goes back there from Activity', async () => {
    const llm = await startFakeLLM()
    const { user } = await signedIn({ seed: (s) => withDigestAgent(s, llm) })
    await user.click(await screen.findByRole('button', { name: /^Space:/ }))
    await user.click(await screen.findByRole('menuitem', { name: 'Agent…' }))
    const page = await screen.findByRole('dialog', { name: 'Notes agent' })
    await user.click(await within(page).findByRole('button', { name: 'Activity…' }))
    const activity = await screen.findByRole('dialog', { name: 'Activity' })
    await user.click(within(activity).getByRole('button', { name: 'Done' }))
    await screen.findByRole('dialog', { name: 'Notes agent' })
    expect(window.location.pathname).toBe('/spaces/default/agent')
  })
})

/** A note moved into a space whose agent then takes too many steps. */
async function failedRun(server: TestServer, llm: FakeLLM) {
  await server.api('PUT', '/settings/ai', { baseUrl: llm.url, apiKey: 'sk-test-1234', model: 'm' })
  await server.api('PUT', '/spaces/home', { name: 'Home' })
  await server.api('PUT', '/agent/spaces/default', { enabled: true, prompt: 'Lists.', triggers: { movedIn: 'Tidy it.' } })
  await server.api('POST', '/notes', { operationId: 'op-n', id: 'eggs', title: 'Eggs', content: 'Eggs', spaceId: 'home' })
  for (let i = 0; i < 21; i++) llm.script.push({ tool: 'get_note', args: {} })
  await server.api('PUT', '/notes/eggs', { operationId: 'op-m', baseRevision: 1, title: 'Eggs', content: 'Eggs', spaceId: 'default' })
  await waitFor(
    async () => {
      const { runs } = (await server.api<{ runs: AgentRun[] }>('GET', '/agent/runs')).body
      expect(runs[0]?.status).toBe('failed')
    },
    { timeout: 5000 },
  )
}

describe('a failed run', () => {
  it('shows on its note, opens in Activity, and can be dismissed', async () => {
    const llm = await startFakeLLM()
    const { user } = await signedIn({ path: '/notes/eggs', seed: (s) => failedRun(s, llm) })
    expect(await screen.findByText('The agent’s last run on this note failed: it took too many steps.')).toBeTruthy()

    await user.click(screen.getByRole('button', { name: 'Activity' }))
    const sheet = await screen.findByRole('dialog', { name: 'Run' })
    expect(within(within(sheet).getByRole('region', { name: 'Summary' })).getByText('Failed: it took too many steps')).toBeTruthy()
    await user.click(within(sheet).getByRole('button', { name: 'Done' }))

    await user.click(await screen.findByRole('button', { name: 'Dismiss' }))
    expect(screen.queryByText(/last run on this note failed/)).toBeNull()
  })
})
