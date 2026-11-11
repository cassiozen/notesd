import { expect, type Page } from '@playwright/test'
import { owner, test } from './profile'

// Shared spaces (architecture §7.9) between two accounts, each in a browser of its own.

async function signIn(page: Page, username: string, password: string) {
  await page.goto('/')
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('button', { name: 'Settings' })).toBeVisible({ timeout: 30_000 })
}

/** The API as the page's account, as the app would call it. */
function api<T>(page: Page, method: string, path: string, body?: unknown): Promise<{ status: number; body: T }> {
  return page.evaluate(
    async (req) => {
      const { csrfToken } = (await (await fetch('/api/v1/auth/session')).json()) as { csrfToken: string }
      const res = await fetch(`/api/v1${req.path}`, {
        method: req.method,
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
        ...(req.body !== undefined && { body: JSON.stringify(req.body) }),
      })
      const text = await res.text()
      return { status: res.status, body: (text ? JSON.parse(text) : undefined) as never }
    },
    { method, path, body },
  )
}

const switcher = (page: Page) => page.getByRole('navigation', { name: 'Sidebar' }).getByRole('button', { name: /^Space: / })
const spaces = (page: Page) => page.getByRole('dialog', { name: 'Spaces' })

async function switchTo(page: Page, name: string) {
  const target = `Space: ${name}`
  // After a reload the app may still be reopening the last note's space: if that lands while the switcher is
  // open, the space moves from its list to its card. Retried from a closed switcher until it's the current one.
  await expect(async () => {
    if (await spaces(page).isVisible()) await page.keyboard.press('Escape')
    if ((await switcher(page).getAttribute('aria-label')) === target) return
    await switcher(page).click()
    await spaces(page).getByRole('button', { name: new RegExp(`^${name}(,|$)`) }).click({ timeout: 2_000 })
  }).toPass({ timeout: 15_000 })
  await expect(switcher(page)).toHaveAccessibleName(target)
}

test('a space shared with someone: they join, edit, and leave', async ({ page, browser }) => {
  // Two browsers, each signing in and syncing: more than the default budget on a busy machine.
  test.setTimeout(60_000)
  // The server outlives a single test.
  const tag = crypto.randomUUID().slice(0, 6)
  const space = { id: `trip-${tag}`, name: `Trip ${tag}` }
  const member = { username: `ana-${tag}`, password: 'ana password' }

  await signIn(page, owner.username, owner.password)
  const made = await api<{ link: { token: string } }>(page, 'POST', '/users', { username: member.username })
  expect(made.status).toBe(201)
  expect((await api(page, 'PUT', `/spaces/${space.id}`, { name: space.name })).status).toBe(200)
  const note = crypto.randomUUID()
  const created = await api(page, 'POST', '/notes', { operationId: crypto.randomUUID(), id: note, title: 'Packing', content: 'Boots\n', spaceId: space.id })
  expect(created.status).toBe(201)
  await page.reload()

  // The owner shares it from Space info's People page, pressing the member's name; they're listed there as invited.
  await switchTo(page, space.name)
  await switcher(page).click()
  await spaces(page).getByRole('button', { name: 'Space info', exact: true }).click()
  await page.getByRole('dialog', { name: space.name }).getByRole('button', { name: 'People', exact: true }).click()
  const people = page.getByRole('dialog', { name: 'People' })
  await people.getByRole('button', { name: `Invite ${member.username}` }).click()
  await expect(people.getByRole('list', { name: 'People in this space' })).toContainText(`${member.username}Invited`)
  await people.getByRole('button', { name: 'Done' }).click()

  // The member, in a browser of their own, joins from the switcher.
  const theirs = await browser.newContext()
  const them = await theirs.newPage()
  await them.goto('/setup')
  await them.evaluate(
    async (arg) => {
      await fetch('/api/v1/auth/setup-link/use', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(arg) })
    },
    { token: made.body.link.token, password: member.password },
  )
  await signIn(them, member.username, member.password)
  await switcher(them).click()
  const invites = spaces(them).getByRole('list', { name: 'Invitations' })
  await expect(invites).toContainText(`${owner.username} invited you to ${space.name}`)
  await invites.getByRole('button', { name: `Join ${space.name}` }).click()
  await expect(switcher(them)).toHaveAccessibleName(`Space: ${space.name}`)

  // They edit the note; the owner sees who did.
  await them.getByRole('complementary', { name: 'Notes' }).getByRole('link', { name: /^Packing/ }).click()
  const editor = them.getByRole('textbox', { name: 'Note' })
  await editor.locator('p').last().click()
  await them.keyboard.press('End')
  await them.keyboard.type(' and socks')
  await expect(them.getByText('Saved', { exact: true })).toBeVisible({ timeout: 10_000 })
  await expect.poll(async () => (await api<{ content: string }>(page, 'GET', `/notes/${note}`)).body.content).toBe('Boots and socks\n')

  await page.reload()
  await switchTo(page, space.name)
  const row = page.getByRole('complementary', { name: 'Notes' }).getByRole('link', { name: /^Packing/ })
  await expect(row).toContainText(`${member.username} · `)
  await row.click()
  await expect(page.getByText(new RegExp(`^Edited by ${member.username} · `))).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'Note' })).toContainText('Boots and socks')

  // The member leaves; the space goes from their switcher.
  await switcher(them).click()
  await spaces(them).getByRole('button', { name: 'Space info', exact: true }).click()
  await them.getByRole('dialog', { name: space.name }).getByRole('button', { name: 'People', exact: true }).click()
  const theirInfo = them.getByRole('dialog', { name: 'People' })
  await theirInfo.getByRole('button', { name: 'Leave space…' }).click()
  await theirInfo.getByRole('group', { name: `Leave ${space.name}?` }).getByRole('button', { name: 'Leave' }).click()
  await expect(switcher(them)).toHaveAccessibleName('Space: Notes')
  await switcher(them).click()
  await expect(spaces(them).getByRole('button', { name: new RegExp(`^${space.name}(,|$)`) })).toHaveCount(0)
  await theirs.close()
})
