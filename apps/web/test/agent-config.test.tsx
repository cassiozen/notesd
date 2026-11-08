import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { agent, type AgentConfig } from '../src/api/agent'
import { createNote, openSettings, signedIn } from './helpers'
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
    expect(within(page).getByText('Notes go to 127.0.0.1', { exact: false })).toBeTruthy()
    await user.click(within(page).getByRole('button', { name: 'Journal' }))
    await user.click(within(page).getByRole('button', { name: 'Use all' }))
    expect(within(page).getByRole('article', { name: 'When a new note is opened' })).toBeTruthy()
    expect(within(page).getByRole('list', { name: 'Notes it always reads' }).textContent).toContain('Digest')
    await user.click(within(page).getByRole('button', { name: 'Save' }))

    await saved(page)
    const [config] = await configs(server)
    expect(config?.spaceId).toBe('diary')
    expect(config?.enabled).toBe(true)
    expect(Object.keys(config?.triggers ?? {}).toSorted()).toEqual(['movedOut', 'newNoteDone', 'noteCreated', 'noteDeleted'])
    expect(config?.referenceNotes).toEqual(['Digest'])
    // Still on Space info: the server's trait by the space's name, and its Activity.
    expect(await within(info).findByRole('img', { name: 'Agent' })).toBeTruthy()
    expect(within(page).getByRole('button', { name: 'Activity…' })).toBeTruthy()
  })

  it('gets rules added one at a time, showing the server’s complaint on the field', async () => {
    const { server, user } = await signedIn({ seed: withAI })
    const { page } = await agentPage(user)

    await user.click(within(page).getByRole('checkbox', { name: 'Agent' }))
    await user.type(within(page).getByLabelText('What this space is for'), 'My notes.')
    expect(within(page).getByText('No rules yet', { exact: false })).toBeTruthy()
    await user.click(within(page).getByRole('button', { name: '+ Add rule' }))
    await user.click(within(page).getByRole('button', { name: /^When you ask, from the note’s menu/ }))
    // Empty and ready to type, with the examples' ideas for it.
    const prompt = within(page).getByLabelText(/^When you ask, from the note’s menu: what the agent does/) as HTMLTextAreaElement
    expect(document.activeElement).toBe(prompt)
    expect(prompt.value).toBe('')
    // Taken: it isn't offered again.
    await user.click(within(page).getByRole('button', { name: '+ Add rule' }))
    expect(within(page).queryByRole('button', { name: /^When you ask, from the note’s menu/ })).toBeNull()
    await user.click(within(page).getByRole('button', { name: 'Close' }))
    const ideas = within(page).getByRole('group', { name: 'Ideas' })
    await user.click(within(ideas).getByRole('button', { name: 'Suggest typo fixes, from Tidy tags' }))
    expect(prompt.value).toMatch(/^Suggest a fix for each spelling mistake/)
    expect(document.activeElement).toBe(prompt)
    expect((within(page).getByLabelText('Command name, as the menu shows it') as HTMLInputElement).value).toBe('Fix typos')
    expect(within(page).queryByRole('group', { name: 'Ideas' })).toBeNull()
    await user.clear(prompt)
    await user.type(prompt, 'Fix typos only.')
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

  it('shows an example’s parts where they’d go, each to take or leave', async () => {
    const { server, user } = await signedIn({
      seed: async (s) => {
        await withAI(s)
        await s.api('PUT', '/agent/spaces/default', { enabled: false, prompt: 'Mine.', triggers: { newNoteDone: 'Tag it.' } })
      },
    })
    const { page } = await agentPage(user)
    await user.click(within(page).getByRole('button', { name: 'Journal' }))
    expect(within(page).getByRole('button', { name: 'Journal', pressed: true })).toBeTruthy()

    // Beside a rule of mine for the same moment, it's another version of it.
    const version = within(page).getByRole('complementary', { name: 'Journal’s rule: When you finish a new note' })
    expect(within(version).getByRole('button', { name: 'Use instead' })).toBeTruthy()
    // A moment I have no rule for: added with the note it needs read.
    const opened = within(page).getByRole('complementary', { name: 'Journal’s rule: When a new note is opened' })
    await user.click(within(opened).getByRole('button', { name: 'Add' }))
    expect(within(page).getByRole('article', { name: 'When a new note is opened' })).toBeTruthy()
    expect(within(page).queryByRole('complementary', { name: 'Journal’s rule: When a new note is opened' })).toBeNull()
    expect(within(page).getByRole('list', { name: 'Notes it always reads' }).textContent).toContain('Digest')
    expect((within(page).getByLabelText('What this space is for') as HTMLTextAreaElement).value).toBe('Mine.')

    // Closed: what wasn't taken goes; what was stays, unsaved.
    await user.click(within(page).getByRole('button', { name: 'Journal' }))
    expect(within(page).queryByRole('complementary')).toBeNull()
    expect(within(page).getByRole('article', { name: 'When you finish a new note' })).toBeTruthy()
    expect((within(page).getByLabelText(/^When you finish a new note: what the agent does/) as HTMLTextAreaElement).value).toBe('Tag it.')
    expect((within(page).getByRole('checkbox', { name: 'Agent' }) as HTMLInputElement).checked).toBe(false)
    await user.click(within(page).getByRole('button', { name: 'Save' }))
    await saved(page)
    expect(await configs(server)).toMatchObject([
      { enabled: false, prompt: 'Mine.', triggers: { newNoteDone: 'Tag it.', noteCreated: expect.stringContaining('follow-ups') }, referenceNotes: ['Digest'] },
    ])
  })

  it('moves a rule to another moment, keeping what it says', async () => {
    const { user } = await signedIn({
      seed: async (s) => {
        await withAI(s)
        await s.api('PUT', '/agent/spaces/default', { enabled: true, prompt: 'Mine.', triggers: { newNoteDone: 'Tag it.' } })
      },
    })
    const { page } = await agentPage(user)
    const rule = within(page).getByRole('article', { name: 'When you finish a new note' })
    await user.selectOptions(within(rule).getByRole('combobox', { name: 'When' }), 'editDone')
    const moved = within(page).getByRole('article', { name: 'When you finish editing a note' })
    expect((within(moved).getByRole('textbox') as HTMLTextAreaElement).value).toBe('Tag it.')
    expect(document.activeElement).toBe(within(moved).getByRole('combobox', { name: 'When' }))
    expect(within(page).queryByRole('article', { name: 'When you finish a new note' })).toBeNull()
  })

  it('offers the space’s note titles to read, and says which aren’t there yet', async () => {
    const { user } = await signedIn({
      seed: async (s) => {
        await withAI(s)
        await createNote(s, 'Weekly digest', 'Things.')
        // The agent would look for "Spaced digest": never offered.
        await createNote(s, 'Spaced  digest', 'Things.')
        await s.api('PUT', '/agent/spaces/default', { enabled: true, prompt: 'Mine.', referenceNotes: ['Glossary'] })
      },
    })
    const { page } = await agentPage(user)
    const refs = within(page).getByRole('list', { name: 'Notes it always reads' })
    await waitFor(() => expect(refs.textContent).toContain('not here yet'))
    const title = within(page).getByLabelText('Note title') as HTMLInputElement
    await user.type(title, 'dige')
    await waitFor(() => expect([...(title.list?.options ?? [])].map((o) => o.value)).toEqual(['Weekly digest']))
  })

  it('says when there’s no room for a note a rule it took needs', async () => {
    const { user } = await signedIn({
      seed: async (s) => {
        await withAI(s)
        await s.api('PUT', '/agent/spaces/default', { enabled: true, prompt: 'Mine.', referenceNotes: ['A', 'B', 'C', 'D', 'E'] })
      },
    })
    const { page } = await agentPage(user)
    await user.click(within(page).getByRole('button', { name: 'Journal' }))
    const opened = within(page).getByRole('complementary', { name: 'Journal’s rule: When a new note is opened' })
    await user.click(within(opened).getByRole('button', { name: 'Add' }))
    await user.click(within(page).getByRole('button', { name: 'Journal' }))
    expect(within(page).getByText('No room for Digest, which a rule needs', { exact: false })).toBeTruthy()
    // Room made: it's offered, and taken.
    await user.click(within(page).getByRole('button', { name: 'Stop reading E' }))
    await user.click(within(page).getByRole('button', { name: 'Read Digest: a rule needs it' }))
    expect(within(page).getByRole('list', { name: 'Notes it always reads' }).textContent).toContain('Digest')
    expect(within(page).queryByText('No room', { exact: false })).toBeNull()
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

/** Work, shared with ana, who joined; the owner signs in unless as says otherwise. */
async function sharedWork(s: TestServer, agentOn: boolean) {
  await withAI(s)
  await s.api('PUT', '/spaces/work', { name: 'Work', icon: '' })
  await s.api('POST', '/notes', { operationId: 'op-milk', id: 'milk', title: 'Milk', content: 'Milk', spaceId: 'work' })
  if (agentOn) await s.api('PUT', '/agent/spaces/work', { enabled: true, prompt: 'Work.', triggers: { noteDeleted: 'Note it.' } })
  const ana = await s.addAccount('ana')
  expect((await s.api('POST', '/spaces/work/invites', { userId: ana.id })).status).toBe(201)
  const [invite] = (await ana.api<{ invites: { id: string }[] }>('GET', '/invites')).body.invites
  expect((await ana.api('POST', `/invites/${invite?.id}/accept`)).status).toBe(200)
  return ana
}

describe('a shared space’s agent', () => {
  it('goes with the space when it’s shared, and any member manages it', async () => {
    const { user } = await signedIn({ as: 'ana', seed: async (s) => void (await sharedWork(s, true)) })
    const { info, page } = await agentPage(user, 'Work')
    expect((within(page).getByLabelText('What this space is for') as HTMLTextAreaElement).value).toBe('Work.')
    expect(within(info).getByRole('img', { name: 'Shared, Agent' })).toBeTruthy()
  })

  it('can be turned on by a member', async () => {
    const { server, user } = await signedIn({ as: 'ana', seed: async (s) => void (await sharedWork(s, false)) })
    const info = await spaceInfo(user, 'Work')
    const page = await within(info).findByRole('region', { name: 'Agent' })
    await user.click(within(page).getByRole('checkbox', { name: 'Agent' }))
    await user.type(within(page).getByLabelText('What this space is for'), 'Work.')
    await user.click(within(page).getByRole('button', { name: 'Save' }))
    await saved(page)
    expect((await server.api('GET', '/agent/spaces/work')).body).toMatchObject({ enabled: true, prompt: 'Work.' })
  })

  it('shows each run in every member’s Activity, saying who it was for', async () => {
    let ana: Awaited<ReturnType<typeof sharedWork>> | undefined
    const { user } = await signedIn({ seed: async (s) => void (ana = await sharedWork(s, true)) })
    if (!ana) throw new Error('ana wasn’t made')
    expect((await ana.api('DELETE', '/notes/milk', { operationId: 'op-del', baseRevision: 1 })).status).toBe(200)
    const { page } = await agentPage(user, 'Work')
    await user.click(await within(page).findByRole('button', { name: 'Activity…' }))
    const sheet = await screen.findByRole('dialog', { name: 'Activity' })
    const runs = await within(sheet).findByRole('list', { name: 'Runs' })
    expect(within(runs).getByRole('button').textContent).toContain('for ana')
    expect(await within(sheet).findByText(/^Today in this space: .* tokens$/)).toBeTruthy()
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
