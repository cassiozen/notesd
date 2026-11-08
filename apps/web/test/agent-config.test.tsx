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

type User = Awaited<ReturnType<typeof signedIn>>['user']

/** Settings › Spaces, then the named space's row: its Space info. */
async function spaceInfo(user: User, name: string) {
  if (!screen.queryByRole('region', { name: 'Spaces' })) await openSettings(user, 'Spaces')
  const card = await screen.findByRole('region', { name: 'Spaces' })
  await user.click(await within(card).findByRole('button', { name: new RegExp(`^${name}, `) }))
  return screen.findByRole('dialog', { name })
}

/** The space's agent: its section of Space info. */
async function agentPage(user: User, name = 'Notes') {
  const info = await spaceInfo(user, name)
  return { info, page: await within(info).findByRole('region', { name: 'Agent' }) }
}

/** Saved: its Save goes. */
const saved = (page: HTMLElement) => waitFor(() => expect(within(page).queryByRole('button', { name: 'Save' })).toBeNull())

describe('a space’s agent', () => {
  it('isn’t offered until AI is set up', async () => {
    const { server, user } = await signedIn()
    const info = await spaceInfo(user, 'Notes')
    await waitFor(() => {
      expect(server.answered('GET', '/settings/ai')).toBeGreaterThan(0)
      expect(server.answered('GET', '/agent/spaces')).toBeGreaterThan(0)
    })
    // The answers' bodies are read, and the stores set, a task later.
    await new Promise((r) => setTimeout(r, 0))
    expect(within(info).queryByRole('region', { name: 'Agent' })).toBeNull()
  })

  it('turns on from Space info, from an example', async () => {
    const { server, user } = await signedIn({
      seed: async (s) => {
        await withAI(s)
        await s.api('PUT', '/spaces/diary', { name: 'Diary', icon: 'duck' })
      },
    })
    const { info, page } = await agentPage(user, 'Diary')
    // Off, it's one line; turned on, the rest opens in place.
    expect(within(page).queryByLabelText('What this space is for')).toBeNull()
    await user.click(within(page).getByRole('checkbox', { name: 'Agent' }))
    await user.click(within(page).getByRole('button', { name: /^Journal/ }))
    expect((within(page).getByRole('checkbox', { name: 'Agent' }) as HTMLInputElement).checked).toBe(true)
    expect(within(page).getByText('Notes go to 127.0.0.1', { exact: false })).toBeTruthy()
    expect(within(page).getByRole('article', { name: 'When a new note is opened' })).toBeTruthy()
    await user.click(within(page).getByRole('button', { name: 'Save' }))

    await saved(page)
    const [config] = await configs(server)
    expect(config?.spaceId).toBe('diary')
    expect(config?.enabled).toBe(true)
    expect(Object.keys(config?.triggers ?? {}).toSorted()).toEqual(['movedOut', 'newNoteDone', 'noteCreated', 'noteDeleted'])
    // Still on Space info: the server's trait by the space's name, and its Activity.
    expect(await within(info).findByRole('img', { name: 'Agent' })).toBeTruthy()
    expect(within(page).getByRole('button', { name: 'Activity…' })).toBeTruthy()
  })

  it('gets rules added one at a time, showing the server’s complaint on the field', async () => {
    const { server, user } = await signedIn({ seed: withAI })
    const { page } = await agentPage(user)

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
    await saved(page)
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
    await saved(page)
    expect(await configs(server)).toMatchObject([{ enabled: false, prompt: 'My notes.', triggers: {}, referenceNotes: ['Digest'] }])
  })

  it('keeps what’s typed while it saves', async () => {
    const { server, user } = await signedIn({ seed: withAI })
    const { page } = await agentPage(user)
    await user.click(within(page).getByRole('checkbox', { name: 'Agent' }))
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

  it('shows as paused when AI is removed', async () => {
    const { user } = await signedIn({
      seed: async (s) => {
        await withAI(s)
        await s.api('PUT', '/agent/spaces/default', { enabled: true, prompt: 'Tags.', triggers: { newNoteDone: 'Tag it.' } })
        await s.api('DELETE', '/settings/ai')
      },
    })
    const { info, page } = await agentPage(user)
    // The trait's problem, said with its glyph.
    expect(await within(info).findByRole('img', { name: 'Agent (AI isn’t set up)' })).toBeTruthy()
    expect(await within(page).findByText(/^Paused: AI isn’t set up/)).toBeTruthy()
    expect(within(page).queryByText(/Notes go to/)).toBeNull()
  })

  it('asks under Done before Space info closes on unsaved changes', async () => {
    const { user } = await signedIn({ seed: withAI })
    // From the switcher, so Escape reaches Space info rather than Settings under it.
    await user.click(await screen.findByRole('button', { name: /^Space:/ }))
    await user.click(within(screen.getByRole('dialog', { name: 'Spaces' })).getByRole('button', { name: 'Space info' }))
    const info = await screen.findByRole('dialog', { name: 'Notes' })
    const page = await within(info).findByRole('region', { name: 'Agent' })
    await user.click(within(page).getByRole('checkbox', { name: 'Agent' }))

    await user.click(within(info).getByRole('button', { name: 'Done' }))
    const ask = within(info).getByRole('group', { name: 'Discard your changes?' })
    expect(ask.textContent).toContain('Your changes to the agent aren’t saved.')
    await user.click(within(ask).getByRole('button', { name: 'Cancel' }))
    expect((within(page).getByRole('checkbox', { name: 'Agent' }) as HTMLInputElement).checked).toBe(true)

    // Escape asks too; Discard closes.
    await user.keyboard('{Escape}')
    await user.click(within(within(info).getByRole('group', { name: 'Discard your changes?' })).getByRole('button', { name: 'Discard' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Notes' })).toBeNull())
  })
})

describe('sharing a space with an agent', () => {
  it('is refused while the agent is on', async () => {
    const { server, user } = await signedIn({
      seed: async (s) => {
        await withAI(s)
        await s.api('PUT', '/spaces/work', { name: 'Work', icon: '' })
        await s.api('PUT', '/agent/spaces/work', { enabled: true, prompt: 'Work.', triggers: { newNoteDone: 'Tag it.' } })
        await s.addAccount('ana')
      },
    })
    const info = await spaceInfo(user, 'Work')
    const people = await within(info).findByRole('region', { name: 'People' })
    await user.click(await within(people).findByRole('button', { name: 'Invite ana' }))
    expect(await within(people).findByText(/^Turn off its agent to share it/)).toBeTruthy()
    const work = (await server.api<{ spaces: Space[] }>('GET', '/spaces')).body.spaces.find((s) => s.id === 'work')
    expect(work?.shared).toBeUndefined()
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
