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

type User = Awaited<ReturnType<typeof signedIn>>['user']

/** Settings › Spaces, then the named space's row: its Space info. */
async function spaceInfo(user: User, name: string) {
  if (!screen.queryByRole('region', { name: 'Spaces' })) await openSettings(user, 'Spaces')
  const card = await screen.findByRole('region', { name: 'Spaces' })
  await user.click(await within(card).findByRole('button', { name: new RegExp(`^${name}, `) }))
  return screen.findByRole('dialog', { name })
}

/** Space info's Agent section, once it's there. */
const agentSection = (info: HTMLElement) => within(info).findByRole('region', { name: 'Agent' })

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

  it('turns on in Space info, from an example, after saying where notes go', async () => {
    const { server, user } = await signedIn({ seed: withAI })
    const info = await spaceInfo(user, 'Notes')
    const section = await agentSection(info)
    await user.click(within(section).getByRole('checkbox', { name: 'Add an agent' }))
    expect(within(section).getByText('Notes in this space will be sent to 127.0.0.1', { exact: false })).toBeTruthy()
    await user.click(within(section).getByRole('button', { name: 'Journal' }))
    expect((within(section).getByLabelText('What this space is for') as HTMLTextAreaElement).value).toMatch(/^A journal\./)
    expect((within(section).getByLabelText('Note created prompt') as HTMLTextAreaElement).value).toMatch(/Digest/)
    await user.click(within(section).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(within(section).queryByRole('button', { name: 'Save' })).toBeNull())
    const [config] = await configs(server)
    expect(config?.spaceId).toBe('default')
    expect(config?.enabled).toBe(true)
    expect(Object.keys(config?.triggers ?? {}).toSorted()).toEqual(['movedOut', 'newNoteDone', 'noteCreated', 'noteDeleted'])
    // On: the server's trait, by the space's name.
    expect(await within(info).findByRole('img', { name: 'Agent' })).toBeTruthy()
    expect(screen.getByRole('dialog', { name: 'Notes' })).toBe(info)
  })

  it('is changed in Space info, showing the server’s complaint on the field', async () => {
    const { server, user } = await signedIn({
      seed: async (s) => {
        await withAI(s)
        await s.api('PUT', '/agent/spaces/default', { enabled: true, prompt: 'My notes.', triggers: { newNoteDone: 'Tag it.' } })
      },
    })
    const info = await spaceInfo(user, 'Notes')
    const section = await agentSection(info)

    // A menu prompt needs the command's name.
    await user.click(await within(section).findByText('Run from menu'))
    await user.type(within(section).getByLabelText('Run from menu prompt'), 'Fix typos only.')
    await user.click(within(section).getByRole('button', { name: 'Save' }))
    expect(await within(section).findByText('Name the command, such as Fix typos.')).toBeTruthy()

    await user.type(within(section).getByLabelText('Command name'), 'Fix typos')
    await user.click(within(section).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(within(section).queryByRole('button', { name: 'Save' })).toBeNull())
    expect(await configs(server)).toMatchObject([
      { spaceId: 'default', enabled: true, prompt: 'My notes.', menuLabel: 'Fix typos', triggers: { menu: 'Fix typos only.' } },
    ])

    // Turned off, it keeps the words, and its section stays to turn it on again.
    const toggle = within(section).getByRole('checkbox', { name: 'Agent' })
    expect((toggle as HTMLInputElement).checked).toBe(true)
    await user.click(toggle)
    await user.click(within(section).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(within(section).queryByRole('button', { name: 'Save' })).toBeNull())
    expect(await configs(server)).toMatchObject([{ enabled: false, prompt: 'My notes.', triggers: { menu: 'Fix typos only.' } }])
    await waitFor(() => expect(within(info).queryByRole('img', { name: 'Agent' })).toBeNull())
    expect(within(section).getByRole('checkbox', { name: 'Agent' })).toBeTruthy()
  })

  it('asks under Done before Space info closes on unsaved changes', async () => {
    const { user } = await signedIn({ seed: withAI })
    // From the switcher, so Escape reaches Space info rather than Settings under it.
    await user.click(await screen.findByRole('button', { name: /^Space:/ }))
    await user.click(within(screen.getByRole('dialog', { name: 'Spaces' })).getByRole('button', { name: 'Space info' }))
    const info = await screen.findByRole('dialog', { name: 'Notes' })
    const section = await agentSection(info)
    await user.click(within(section).getByRole('checkbox', { name: 'Add an agent' }))

    await user.click(within(info).getByRole('button', { name: 'Done' }))
    const ask = within(info).getByRole('group', { name: 'Discard your changes?' })
    expect(ask.textContent).toContain('Your changes to the agent aren’t saved.')
    await user.click(within(ask).getByRole('button', { name: 'Cancel' }))
    expect((within(section).getByRole('checkbox', { name: 'Add an agent' }) as HTMLInputElement).checked).toBe(true)

    // Escape asks too; Discard closes.
    await user.keyboard('{Escape}')
    await user.click(within(within(info).getByRole('group', { name: 'Discard your changes?' })).getByRole('button', { name: 'Discard' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Notes' })).toBeNull())
  })

  it('shows as paused when AI is removed', async () => {
    const { user } = await signedIn({
      seed: async (s) => {
        await withAI(s)
        await s.api('PUT', '/agent/spaces/default', { enabled: true, prompt: 'Tags.', triggers: { newNoteDone: 'Tag it.' } })
        await s.api('DELETE', '/settings/ai')
      },
    })
    const info = await spaceInfo(user, 'Notes')
    // The trait's problem, said with its glyph.
    expect(await within(info).findByRole('img', { name: 'Agent (AI isn’t set up)' })).toBeTruthy()
    const section = await agentSection(info)
    expect(await within(section).findByText(/^Paused: AI isn’t set up/)).toBeTruthy()
    expect(within(section).queryByText(/will be sent to/)).toBeNull()
  })

  it('keeps its space from being shared while it’s on', async () => {
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
