import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { agent, type AgentConfig } from '../src/api/agent'
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
    expect(within(dialog).queryByRole('checkbox', { name: /Set up an agent next/ })).toBeNull()
  })

  it('is set up right after making a space, from an example', async () => {
    const { server, user } = await signedIn({ seed: withAI })
    const dialog = await openSpaceDialog(user, 'New space', 'New space')
    await user.type(within(dialog).getByLabelText('Name'), 'Diary')
    await user.click(within(dialog).getByRole('radio', { name: 'Duck' }))
    await user.click(await within(dialog).findByRole('checkbox', { name: /Set up an agent next/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Create' }))

    const page = await screen.findByRole('dialog', { name: 'Diary agent' })
    await user.click(within(page).getByRole('button', { name: /^Journal/ }))
    expect((within(page).getByRole('checkbox', { name: 'Agent' }) as HTMLInputElement).checked).toBe(true)
    expect(within(page).getByText('Notes go to 127.0.0.1', { exact: false })).toBeTruthy()
    expect(within(page).getByRole('article', { name: 'When a new note is opened' })).toBeTruthy()
    await user.click(within(page).getByRole('button', { name: 'Save' }))

    await within(page).findByRole('button', { name: 'Done' })
    const diary = (await server.api<{ spaces: Space[] }>('GET', '/spaces')).body.spaces.find((s) => s.name === 'Diary')
    const [config] = await configs(server)
    expect(config?.spaceId).toBe(diary?.id)
    expect(config?.enabled).toBe(true)
    expect(Object.keys(config?.triggers ?? {}).toSorted()).toEqual(['movedOut', 'newNoteDone', 'noteCreated', 'noteDeleted'])
  })

  it('gets rules added one at a time, showing the server’s complaint on the field', async () => {
    const { server, user } = await signedIn({ seed: withAI })
    const dialog = await openSpaceDialog(user, 'Edit Notes', 'Edit space')
    expect(within(dialog).getByText('Not set up')).toBeTruthy()
    await user.click(within(dialog).getByRole('button', { name: 'Set up an agent…' }))
    const page = await screen.findByRole('dialog', { name: 'Notes agent' })

    await user.click(within(page).getByRole('checkbox', { name: 'Agent' }))
    await user.type(within(page).getByLabelText('What this space is for'), 'My notes.')
    await user.click(within(page).getByRole('button', { name: '+ Add rule' }))
    await user.click(within(page).getByRole('button', { name: /^When you ask, from the note’s menu/ }))
    const added = document.activeElement as HTMLTextAreaElement
    expect([added.value, added.selectionStart, added.selectionEnd]).toEqual(['Fix spelling and obvious typos only.', 0, added.value.length])
    // Taken: it isn't offered again.
    await user.click(within(page).getByRole('button', { name: '+ Add rule' }))
    expect(within(page).queryByRole('button', { name: /^When you ask, from the note’s menu/ })).toBeNull()
    await user.click(within(page).getByRole('button', { name: 'Close' }))
    // It starts from a suggestion, selected: typing replaces it.
    const prompt = within(page).getByLabelText(/^When you ask, from the note’s menu: what the agent does/) as HTMLTextAreaElement
    expect(prompt.value).toBe('Fix spelling and obvious typos only.')
    expect((within(page).getByLabelText('Command name, as the menu shows it') as HTMLInputElement).value).toBe('Fix typos')
    prompt.focus()
    prompt.select()
    await user.keyboard('Fix typos only.')
    await user.type(within(page).getByLabelText('Note title'), 'Digest{Enter}')

    // A menu rule needs the command's name.
    await user.clear(within(page).getByLabelText('Command name, as the menu shows it'))
    await user.click(within(page).getByRole('button', { name: 'Save' }))
    expect(await within(page).findByText('Name the command, such as Fix typos.')).toBeTruthy()
    await user.type(within(page).getByLabelText('Command name, as the menu shows it'), 'Fix typos')
    await user.click(within(page).getByRole('button', { name: 'Save' }))
    await within(page).findByRole('button', { name: 'Done' })
    expect(await configs(server)).toMatchObject([
      {
        spaceId: 'default',
        enabled: true,
        prompt: 'My notes.',
        menuLabel: 'Fix typos',
        triggers: { menu: 'Fix typos only.' },
        referenceNotes: ['Digest'],
      },
    ])

    // Removing a rule, and turning it off, keep the rest.
    await user.click(within(page).getByRole('button', { name: 'Remove rule: When you ask, from the note’s menu' }))
    await user.click(within(page).getByRole('checkbox', { name: 'Agent' }))
    await user.click(within(page).getByRole('button', { name: 'Save' }))
    await within(page).findByRole('button', { name: 'Done' })
    expect(await configs(server)).toMatchObject([{ enabled: false, prompt: 'My notes.', triggers: {}, referenceNotes: ['Digest'] }])
  })

  it('keeps what’s typed while it saves', async () => {
    const { server, user } = await signedIn({ seed: withAI })
    const dialog = await openSpaceDialog(user, 'Edit Notes', 'Edit space')
    await user.click(await within(dialog).findByRole('button', { name: 'Set up an agent…' }))
    const page = await screen.findByRole('dialog', { name: 'Notes agent' })
    const purpose = within(page).getByLabelText('What this space is for')
    await user.type(purpose, 'My notes.')

    const put = agent.put.bind(agent)
    let release!: () => void
    const held = new Promise<void>((r) => (release = r))
    const spy = vi.spyOn(agent, 'put').mockImplementation(async (...args) => {
      await held
      return put(...args)
    })
    try {
      await user.click(within(page).getByRole('button', { name: 'Save' }))
      await user.type(purpose, ' Mostly work.')
      release()
      await within(page).findByText('Saved')
      expect(await configs(server)).toMatchObject([{ prompt: 'My notes.' }])
      expect((purpose as HTMLTextAreaElement).value).toBe('My notes. Mostly work.')
      expect(within(page).getByRole('button', { name: 'Save' })).toBeTruthy()
    } finally {
      spy.mockRestore()
    }
  })

  it('asks before Back leaves unsaved changes', async () => {
    const { user } = await signedIn({ seed: withAI })
    const dialog = await openSpaceDialog(user, 'Edit Notes', 'Edit space')
    await user.click(await within(dialog).findByRole('button', { name: 'Set up an agent…' }))
    const page = await screen.findByRole('dialog', { name: 'Notes agent' })
    await user.type(within(page).getByLabelText('What this space is for'), 'My notes.')
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    try {
      const at = window.location.pathname
      window.history.back()
      await waitFor(() => expect(confirm).toHaveBeenCalledTimes(1))
      // Kept: still the page, with what was typed.
      expect(window.location.pathname).toBe(at)
      expect(screen.getByRole('dialog', { name: 'Notes agent' })).toBeTruthy()
      window.history.back()
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Notes agent' })).toBeNull())
    } finally {
      confirm.mockRestore()
    }
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
    expect(await within(dialog).findByText('Paused: AI isn’t set up on this server')).toBeTruthy()
    await user.click(within(dialog).getByRole('button', { name: 'Edit agent…' }))
    const page = await screen.findByRole('dialog', { name: 'Notes agent' })
    expect(within(page).getByText(/^Paused: AI isn’t set up/)).toBeTruthy()
    expect(within(page).queryByText(/Notes go to/)).toBeNull()
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
