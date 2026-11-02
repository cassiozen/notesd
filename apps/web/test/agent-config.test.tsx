import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { AgentConfig } from '../src/api/agent'
import type { Space } from '../src/api/types'
import { openSettings, signedIn } from './helpers'
import { startFakeLLM, type TestServer } from './server'

async function withAI(server: TestServer) {
  const llm = await startFakeLLM()
  const res = await server.api('PUT', '/settings/ai', { baseUrl: llm.url, apiKey: 'sk-test-1234', model: 'm' })
  expect(res.status).toBe(200)
}

const configs = async (server: TestServer) =>
  (await server.api<{ spaces: AgentConfig[] }>('GET', '/agent/spaces')).body.spaces

async function openSpaceDialog(user: Awaited<ReturnType<typeof signedIn>>['user'], button: string, title: string) {
  await openSettings(user, 'Spaces')
  const card = await screen.findByRole('region', { name: 'Spaces' })
  await user.click(await within(card).findByRole('button', { name: button }))
  return screen.findByRole('dialog', { name: title })
}

describe('a space’s agent', () => {
  it('isn’t offered until AI is set up', async () => {
    const { server, user } = await signedIn()
    const dialog = await openSpaceDialog(user, 'New space', 'New space')
    await waitFor(() => {
      expect(server.answered('GET', '/settings/ai')).toBeGreaterThan(0)
      expect(server.answered('GET', '/agent/spaces')).toBeGreaterThan(0)
    })
    // The answers' bodies are read, and the stores set, a task later.
    await new Promise((r) => setTimeout(r, 0))
    expect(within(dialog).queryByRole('checkbox', { name: 'Add an agent' })).toBeNull()
  })

  it('is added with a new space, from an example, after saying where notes go', async () => {
    const { server, user } = await signedIn({ seed: withAI })
    const dialog = await openSpaceDialog(user, 'New space', 'New space')
    await user.type(within(dialog).getByLabelText('Name'), 'Diary')
    await user.click(within(dialog).getByRole('radio', { name: 'Duck' }))

    await user.click(await within(dialog).findByRole('checkbox', { name: 'Add an agent' }))
    expect(within(dialog).getByText('Notes in this space will be sent to 127.0.0.1', { exact: false })).toBeTruthy()
    await user.click(within(dialog).getByRole('button', { name: 'Journal' }))
    expect((within(dialog).getByLabelText('What this space is for') as HTMLTextAreaElement).value).toMatch(/^A journal\./)
    expect((within(dialog).getByLabelText('Note created prompt') as HTMLTextAreaElement).value).toMatch(/Digest/)
    await user.click(within(dialog).getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'New space' })).toBeNull())
    const diary = (await server.api<{ spaces: Space[] }>('GET', '/spaces')).body.spaces.find((s) => s.name === 'Diary')
    const [config] = await configs(server)
    expect(config?.spaceId).toBe(diary?.id)
    expect(config?.enabled).toBe(true)
    expect(Object.keys(config?.triggers ?? {}).toSorted()).toEqual(['movedOut', 'newNoteDone', 'noteCreated', 'noteDeleted'])
  })

  it('is edited with the space, showing the server’s complaint on the field', async () => {
    const { server, user } = await signedIn({ seed: withAI })
    const dialog = await openSpaceDialog(user, 'Edit Notes', 'Edit space')
    await user.click(await within(dialog).findByRole('checkbox', { name: 'Agent' }))
    await user.type(within(dialog).getByLabelText('What this space is for'), 'My notes.')

    // A menu prompt needs the command's name.
    await user.click(within(dialog).getByText('Run from menu'))
    await user.type(within(dialog).getByLabelText('Run from menu prompt'), 'Fix typos only.')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await within(dialog).findByText('Name the command, such as Fix typos.')).toBeTruthy()

    await user.type(within(dialog).getByLabelText('Command name'), 'Fix typos')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Edit space' })).toBeNull())
    expect(await configs(server)).toMatchObject([
      { spaceId: 'default', enabled: true, prompt: 'My notes.', menuLabel: 'Fix typos', triggers: { menu: 'Fix typos only.' } },
    ])

    // Opening it again shows what was saved; turning it off keeps the words.
    const again = await openSpaceDialog(user, 'Edit Notes', 'Edit space')
    const toggle = await within(again).findByRole('checkbox', { name: 'Agent' })
    expect((toggle as HTMLInputElement).checked).toBe(true)
    await user.click(toggle)
    await user.click(within(again).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Edit space' })).toBeNull())
    expect(await configs(server)).toMatchObject([{ enabled: false, prompt: 'My notes.', triggers: { menu: 'Fix typos only.' } }])
  })

  it('shows as paused when AI is removed', async () => {
    const { user } = await signedIn({
      seed: async (s) => {
        await withAI(s)
        await s.api('PUT', '/agent/spaces/default', { enabled: true, prompt: 'Tags.', triggers: { newNoteDone: 'Tag it.' } })
        await s.api('DELETE', '/settings/ai')
      },
    })
    const dialog = await openSpaceDialog(user, 'Edit Notes', 'Edit space')
    expect(await within(dialog).findByText(/^Paused: AI isn’t set up/)).toBeTruthy()
    expect(within(dialog).queryByText(/will be sent to/)).toBeNull()
  })
})

describe('time zone', () => {
  it('is filled in from the browser and can be changed', async () => {
    const { server, user } = await signedIn()
    await openSettings(user, 'Server')
    const card = await screen.findByRole('region', { name: 'Time zone' })
    const select = (await within(card).findByLabelText('Time zone')) as HTMLSelectElement
    const browser = Intl.DateTimeFormat().resolvedOptions().timeZone
    expect(select.value).toBe(browser)
    expect(within(card).getByText(/Set from the first device/)).toBeTruthy()

    const other = browser === 'Asia/Tokyo' ? 'Europe/Lisbon' : 'Asia/Tokyo'
    await user.selectOptions(select, other)
    await waitFor(async () =>
      expect((await server.api('GET', '/settings/account')).body).toEqual({ timeZone: other, timeZoneSource: 'user' }),
    )
  })
})
