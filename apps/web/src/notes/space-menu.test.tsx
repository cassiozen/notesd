import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Space } from '../api/types'
import { cardLine, SpaceMenu, type SpaceInvite } from './space-menu'

const space = (id: string, name: string, traits: Space['traits'] = []): Space => ({ id, name, icon: '', traits }) as Space

const notes = space('notes', 'Notes')
const family = space('family', 'Family', [
  { key: 'shared', label: 'Shared', summary: 'With Ana and Ben', exposure: 'Visible to Ana and Ben' },
  { key: 'published', label: 'Published', summary: 'family.example', problem: 'Publishing failed' },
])
const work = space('work', 'Work', [{ key: 'shared', label: 'Shared', summary: 'With Ben' }])
const invite: SpaceInvite = { id: 'inv1', spaceName: 'Book club', spaceIcon: '', from: 'Ana' }

function open(props: Partial<Parameters<typeof SpaceMenu>[0]> = {}) {
  const handlers = { onSwitch: vi.fn(), onCreate: vi.fn(), onInfo: vi.fn(), onJoin: vi.fn(), onDecline: vi.fn() }
  const user = userEvent.setup()
  render(<SpaceMenu spaces={[notes, family, work]} current="family" {...handlers} {...props} />)
  return { user, ...handlers, show: () => user.click(screen.getByRole('button', { name: /^Space: / })) }
}

const pop = () => screen.getByRole('dialog', { name: 'Spaces' })

describe('cardLine', () => {
  it('words a trait by its label and summary, a problem in their place', () => {
    expect(cardLine({ key: 'shared', label: 'Shared', summary: 'With Ana' })).toBe('Shared · With Ana')
    expect(cardLine({ key: 'agent', label: 'Agent', summary: '' })).toBe('Agent')
    expect(cardLine({ key: 'published', label: 'Published', summary: 'x', problem: 'Publishing failed' })).toBe('Publishing failed')
  })
})

describe('SpaceMenu', () => {
  it('without invitations or changes, shows none', async () => {
    const { show } = open()
    await show()
    expect(within(pop()).queryByRole('list', { name: 'Invitations' })).toBeNull()
    expect(within(pop()).getByRole('button', { name: /^Work, / }).getAttribute('aria-label')).toBe('Work, Shared')
  })

  it('joins or declines an invitation in place', async () => {
    const { show, user, onJoin, onDecline } = open({ invites: [invite] })
    await show()
    const invites = within(pop()).getByRole('list', { name: 'Invitations' })
    expect(invites.textContent).toContain('Ana invited you to Book club')
    await user.click(within(invites).getByRole('button', { name: 'Join Book club' }))
    expect(onJoin).toHaveBeenCalledWith('inv1')
    expect(screen.queryByRole('dialog', { name: 'Spaces' })).toBeNull()

    await show()
    await user.click(within(pop()).getByRole('button', { name: 'Decline Book club' }))
    expect(onDecline).toHaveBeenCalledWith('inv1')
  })

  it('says what changed in a space instead of its traits, with a dot', async () => {
    const { show } = open({ changes: { work: 'Ben edited 3 notes · 2h' } })
    await show()
    const row = within(pop()).getByRole('button', { name: /^Work, / })
    expect(row.getAttribute('aria-label')).toBe('Work, Ben edited 3 notes · 2h')
    expect(row.textContent).toBe('WorkBen edited 3 notes · 2h')
    expect(row.querySelector('[data-trait="shared"]')).toBeTruthy()
  })

  it('opens another space’s info without switching to it', async () => {
    const { show, user, onInfo, onSwitch } = open()
    await show()
    await user.click(within(pop()).getByRole('button', { name: 'Space info: Work' }))
    expect(onInfo).toHaveBeenCalledWith('work')
    expect(onSwitch).not.toHaveBeenCalled()
  })

  it('switches, opens the current space’s info and starts a new one', async () => {
    const { show, user, onInfo, onSwitch, onCreate } = open()
    await show()
    await user.click(within(pop()).getByRole('button', { name: /^Notes, / }))
    expect(onSwitch).toHaveBeenCalledWith('notes')
    await show()
    await user.click(within(pop()).getByRole('button', { name: 'Space info' }))
    expect(onInfo).toHaveBeenCalledWith('family')
    await show()
    await user.click(within(pop()).getByRole('button', { name: 'New space…' }))
    expect(onCreate).toHaveBeenCalled()
  })
})
