import { expect } from '@playwright/test'
import { owner, test } from './profile'

// Publishing a space (architecture §7.10): turned on in Space info, read by anyone at its address.

test('a published space: on from Space info, its site readable, then off', async ({ page, browser }) => {
  // The server outlives a single test.
  const tag = crypto.randomUUID().slice(0, 6)
  const name = `Garden ${tag}`

  await page.goto('/')
  await page.getByLabel('Username').fill(owner.username)
  await page.getByLabel('Password').fill(owner.password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('button', { name: 'Settings' })).toBeVisible({ timeout: 30_000 })
  const made = await page.evaluate(
    async (space) => {
      const { csrfToken } = (await (await fetch('/api/v1/auth/session')).json()) as { csrfToken: string }
      const call = (method: string, path: string, body: unknown) =>
        fetch(`/api/v1${path}`, { method, headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken }, body: JSON.stringify(body) })
      await call('PUT', `/spaces/${space.id}`, { name: space.name })
      return (await call('POST', '/notes', { operationId: crypto.randomUUID(), id: crypto.randomUUID(), title: 'Tomatoes', content: 'Planted today. #veg', spaceId: space.id })).status
    },
    { id: `garden-${tag}`, name },
  )
  expect(made).toBe(201)
  await page.reload()

  const switcher = page.getByRole('navigation', { name: 'Sidebar' }).getByRole('button', { name: /^Space: / })
  const spaces = page.getByRole('dialog', { name: 'Spaces' })
  await expect(async () => {
    if (await spaces.isVisible()) await page.keyboard.press('Escape')
    await switcher.click()
    await spaces.getByRole('button', { name: new RegExp(`^${name}(,|$)`) }).click({ timeout: 2_000 })
    await expect(switcher).toHaveAccessibleName(`Space: ${name}`, { timeout: 2_000 })
  }).toPass({ timeout: 15_000 })
  await switcher.click()
  await spaces.getByRole('button', { name: 'Space info', exact: true }).click()
  const info = page.getByRole('dialog', { name })
  const published = info.getByRole('region', { name: 'Published' })
  await published.getByRole('button', { name: 'Publish', exact: true }).click()
  const open = published.getByRole('link', { name: 'Open' })
  await expect(open).toBeVisible()
  const url = await open.getAttribute('href')
  expect(url).toBe(`${new URL(page.url()).origin}/@garden-${tag}/`)

  // Anyone: a browser with no session.
  const visitor = await (await browser.newContext()).newPage()
  await visitor.goto(url ?? '')
  await expect(visitor.getByRole('link', { name, exact: true })).toBeVisible()
  await expect(visitor.getByRole('heading', { name: 'Posts', level: 1 })).toBeVisible()
  await visitor.getByRole('link', { name: 'Tomatoes' }).click()
  await expect(visitor.getByRole('heading', { name: 'Tomatoes', level: 1 })).toBeVisible()
  await expect(visitor.getByRole('link', { name: '#veg' }).first()).toBeVisible()

  await info.getByRole('button', { name: 'Stop publishing…' }).click()
  await info.getByRole('group', { name: `Stop publishing ${name}?` }).getByRole('button', { name: 'Stop publishing' }).click()
  await expect(published.getByRole('button', { name: 'Publish', exact: true })).toBeVisible()
  // Not the visitor's page: browsers may keep it a minute (max-age=60).
  expect((await visitor.request.get(url ?? '')).status()).toBe(404)
})
