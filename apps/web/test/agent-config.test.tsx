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

/** The row of Space info's list that opens the agent's page. */
const agentRow = (info: HTMLElement) => within(info).findByRole('button', { name: 'Agent' })

/** What a row of Space info's list says: its description. */
const status = (row: HTMLElement) => document.getElementById(row.getAttribute('aria-describedby') ?? '')?.textContent

/** The space's agent: its page, opened in Space info's own dialog. */
async function agentPage(user: User, name = 'Notes') {
  const info = await spaceInfo(user, name)
  await user.click(await agentRow(info))
  return { info, page: await screen.findByRole('dialog', { name: 'Agent' }) }
}

/** From the agent's page, back to the rest of Space info. */
const back = (user: User, page: HTMLElement, name = 'Notes') => user.click(within(page).getByRole('button', { name: `Back to ${name}` }))

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
    expect(within(info).queryByRole('button', { name: 'Agent' })).toBeNull()
  })

  it('turns on from Space info, from an example', async () => {
    const { server, user } = await signedIn({
      seed: async (s) => {
        await withAI(s)
        await s.api('PUT', '/spaces/diary', { name: 'Diary', icon: 'duck' })
      },
    })
    const info = await spaceInfo(user, 'Diary')
    const row = await agentRow(info)
    expect(status(row)).toBe('Off')
    await user.click(row)
    const page = await screen.findByRole('dialog', { name: 'Agent' })
    expect(page).toBe(info)
    expect(document.activeElement).toBe(within(page).getByRole('heading', { name: 'Agent' }))
    // Off, it's one line; turned on, the rest opens in place.
    expect(within(page).queryByLabelText('What this space is for')).toBeNull()
    await user.click(within(page).getByRole('checkbox', { name: 'Agent' }))
    expect(within(page).getByText('Notes go to 127.0.0.1', { exact: false })).toBeTruthy()
    await user.click(within(page).getByRole('button', { name: 'Recipes' }))
    expect(within(page).getByRole('article', { name: 'When you finish a new note' })).toBeTruthy()
    expect((within(page).getByLabelText('Command name, as the menu shows it') as HTMLInputElement).value).toBe('Scale to 2 servings')
    // Something's written: no examples to start from over it.
    expect(within(page).queryByRole('group', { name: 'Start from an example' })).toBeNull()
    await user.click(within(page).getByRole('button', { name: 'Save' }))

    await saved(page)
    const [config] = await configs(server)
    expect(config?.spaceId).toBe('diary')
    expect(config?.enabled).toBe(true)
    expect(Object.keys(config?.triggers ?? {}).toSorted()).toEqual(['menu', 'newNoteDone'])
    expect(config?.menuLabel).toBe('Scale to 2 servings')
    expect(config?.referenceNotes ?? []).toEqual([])
    // Saved, its Activity is on the page; back on Space info, the row says how it's set.
    expect(within(page).getByRole('button', { name: 'Activity…' })).toBeTruthy()
    await back(user, page, 'Diary')
    expect(document.activeElement).toBe(await agentRow(info))
    expect(status(await agentRow(info))).toBe('On · 2 rules')
  })

  it('gets rules added one at a time, showing the server’s complaint on the field', async () => {
    const { server, user } = await signedIn({ seed: withAI })
    const { page } = await agentPage(user)

    await user.click(within(page).getByRole('checkbox', { name: 'Agent' }))
    await user.type(within(page).getByLabelText('What this space is for'), 'My notes.')
    expect(within(page).getByText('No rules yet', { exact: false })).toBeTruthy()
    await user.click(within(page).getByRole('button', { name: '+ Add rule' }))
    await user.click(within(page).getByRole('button', { name: /^When you ask, from the note’s menu/ }))
    // Empty and ready to type.
    const prompt = within(page).getByLabelText(/^When you ask, from the note’s menu: what the agent does/) as HTMLTextAreaElement
    expect(document.activeElement).toBe(prompt)
    expect(prompt.value).toBe('')
    // Taken: it isn't offered again.
    await user.click(within(page).getByRole('button', { name: '+ Add rule' }))
    expect(within(page).queryByRole('button', { name: /^When you ask, from the note’s menu/ })).toBeNull()
    await user.click(within(page).getByRole('button', { name: 'Close' }))
    await user.type(prompt, 'Fix typos only.')
    await user.type(within(page).getByLabelText('Command name, as the menu shows it'), 'Fix typos')
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

  it('offers examples only while nothing’s written, and an idea for an empty rule', async () => {
    const { user } = await signedIn({
      seed: async (s) => {
        await withAI(s)
        await s.api('PUT', '/agent/spaces/default', { enabled: false, prompt: 'Mine.', triggers: { newNoteDone: 'Tag it.' } })
      },
    })
    const { page } = await agentPage(user)
    expect(within(page).queryByRole('group', { name: 'Start from an example' })).toBeNull()
    await user.clear(within(page).getByLabelText('What this space is for'))
    await user.click(within(page).getByRole('button', { name: 'Remove rule: When you finish a new note' }))
    expect(within(page).getByRole('group', { name: 'Start from an example' })).toBeTruthy()

    await user.click(within(page).getByRole('button', { name: '+ Add rule' }))
    await user.click(within(page).getByRole('button', { name: /^When you finish editing a note/ }))
    const prompt = within(page).getByLabelText(/^When you finish editing a note: what the agent does/) as HTMLTextAreaElement
    await user.click(within(page).getByRole('button', { name: 'Finished tasks last' }))
    expect(prompt.value).toBe('Move finished tasks to the end of their list.')
    expect(document.activeElement).toBe(prompt)
    expect(within(page).queryByRole('button', { name: 'Finished tasks last' })).toBeNull()
    // Taken, it's mine: the switch stays as it was.
    expect((within(page).getByRole('checkbox', { name: 'Agent' }) as HTMLInputElement).checked).toBe(false)
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

  it('counts an empty rule as a change, and won’t save it until it says something or goes', async () => {
    const { server, user } = await signedIn({
      seed: async (s) => {
        await withAI(s)
        await s.api('PUT', '/agent/spaces/default', { enabled: true, prompt: 'Tags.', triggers: { newNoteDone: 'Tag it.' } })
      },
    })
    const { page } = await agentPage(user)
    await user.click(await within(page).findByRole('button', { name: '+ Add rule' }))
    await user.click(within(page).getByRole('button', { name: /^When you ask, from the note’s menu/ }))
    await user.click(within(page).getByRole('button', { name: 'Save' }))
    expect(await within(page).findByText('Say what the agent does then, or remove the rule.')).toBeTruthy()
    expect((await configs(server))[0]?.triggers).toEqual({ newNoteDone: 'Tag it.' })

    await user.click(within(page).getByRole('button', { name: 'Remove rule: When you ask, from the note’s menu' }))
    await saved(page)
  })

  it('says when it couldn’t load, and tries again, in a space it was never set up in', async () => {
    const list = vi.spyOn(agent, 'list').mockRejectedValue(new TypeError('Failed to fetch'))
    const { user } = await signedIn({ seed: withAI })
    const info = await spaceInfo(user, 'Notes')
    await waitFor(async () => expect(status(await agentRow(info))).toBe('Couldn’t load the agent.'))
    await user.click(await agentRow(info))
    const page = await screen.findByRole('dialog', { name: 'Agent' })
    expect(within(page).getByRole('alert').textContent).toBe('Couldn’t load the agent.')
    list.mockRestore()
    await user.click(within(page).getByRole('button', { name: 'Try again' }))
    expect(await within(page).findByRole('checkbox', { name: 'Agent' })).toBeTruthy()
    await back(user, page)
    expect(status(await agentRow(info))).toBe('Off')
  })

  it('says when it couldn’t load, and tries again', async () => {
    const list = vi.spyOn(agent, 'list').mockRejectedValue(new TypeError('Failed to fetch'))
    const { user } = await signedIn({
      seed: async (s) => {
        await withAI(s)
        await s.api('PUT', '/agent/spaces/default', { enabled: true, prompt: 'Tags.', triggers: { newNoteDone: 'Tag it.' } })
      },
    })
    const info = await spaceInfo(user, 'Notes')
    await waitFor(async () => expect(status(await agentRow(info))).toBe('Couldn’t load the agent.'))
    await user.click(await agentRow(info))
    const page = await screen.findByRole('dialog', { name: 'Agent' })
    list.mockRestore()
    await user.click(await within(page).findByRole('button', { name: 'Try again' }))
    expect(await within(page).findByLabelText('What this space is for')).toBeTruthy()
    await back(user, page)
    expect(status(await agentRow(info))).toBe('On · 1 rule')
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
    // The trait's problem, said in its row.
    await waitFor(async () => expect(status(await agentRow(info))).toBe('AI isn’t set up'))
    await user.click(await agentRow(info))
    const page = await screen.findByRole('dialog', { name: 'Agent' })
    expect(await within(page).findByText(/^Paused: AI isn’t set up/)).toBeTruthy()
    expect(within(page).queryByText(/Notes go to/)).toBeNull()
  })

  it('asks under Done before Space info closes on unsaved changes', async () => {
    const { user } = await signedIn({ seed: withAI })
    // From the switcher, so Escape reaches Space info rather than Settings under it.
    await user.click(await screen.findByRole('button', { name: /^Space:/ }))
    await user.click(within(screen.getByRole('dialog', { name: 'Spaces' })).getByRole('button', { name: 'Space info' }))
    const info = await screen.findByRole('dialog', { name: 'Notes' })
    await user.click(await agentRow(info))
    const page = await screen.findByRole('dialog', { name: 'Agent' })
    await user.click(within(page).getByRole('checkbox', { name: 'Agent' }))

    // Asked on the page, where the changes are.
    await user.click(within(page).getByRole('button', { name: 'Done' }))
    const ask = within(page).getByRole('group', { name: 'Discard your changes?' })
    expect(ask.textContent).toContain('Your changes to the agent aren’t saved.')
    await user.click(within(ask).getByRole('button', { name: 'Cancel' }))
    expect((within(page).getByRole('checkbox', { name: 'Agent' }) as HTMLInputElement).checked).toBe(true)

    // Escape goes back, keeping them, and the row says so.
    await user.keyboard('{Escape}')
    expect(await screen.findByRole('dialog', { name: 'Notes' })).toBe(info)
    expect(status(await agentRow(info))).toBe('Unsaved changes')
    await user.click(await agentRow(info))
    expect(within(page).queryByRole('button', { name: 'Activity…' })).toBeNull()
    expect((within(page).getByRole('checkbox', { name: 'Agent' }) as HTMLInputElement).checked).toBe(true)
    await back(user, page)

    // Escape on the rest asks; Discard closes.
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
    const info = await spaceInfo(user, 'Work')
    expect(status(await agentRow(info))).toBe('On · 1 rule')
    await user.click(await agentRow(info))
    const page = await screen.findByRole('dialog', { name: 'Agent' })
    expect((within(page).getByLabelText('What this space is for') as HTMLTextAreaElement).value).toBe('Work.')
  })

  it('can be turned on by a member', async () => {
    const { server, user } = await signedIn({ as: 'ana', seed: async (s) => void (await sharedWork(s, false)) })
    const { page } = await agentPage(user, 'Work')
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
