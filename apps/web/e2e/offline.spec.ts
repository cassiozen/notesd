import { request as api, expect } from '@playwright/test'
import { owner, test } from './profile'

// The real thing, against the build: the service worker serves the app, the
// engine's worker the notes, with the network gone.

test.use({ baseURL: 'http://127.0.0.1:5176' })

test('kept on this device, the app opens offline, and what was typed reaches the server later', async ({ page, context, baseURL }) => {
  const server = await api.newContext({ baseURL })
  await server.post('/api/v1/auth/login', { data: { ...owner, client: 'web' } })
  const { csrfToken } = (await (await server.get('/api/v1/auth/session')).json()) as { csrfToken: string }
  // Other tests share the account.
  const title = `Offline ${crypto.randomUUID().slice(0, 8)}`
  const id = crypto.randomUUID()
  const created = await server.post('/api/v1/notes', {
    headers: { 'X-CSRF-Token': csrfToken },
    data: { operationId: crypto.randomUUID(), id, title, content: 'Step one\n' },
  })
  expect(created.status()).toBe(201)

  await page.goto('/')
  await page.getByLabel('Username').fill(owner.username)
  await page.getByLabel('Password').fill(owner.password)
  await page.getByRole('checkbox', { name: /Keep my notes on this device/ }).check()
  await page.getByRole('button', { name: 'Sign in' }).click()
  // Listed from the engine: the first sync is done.
  const row = page.getByRole('complementary', { name: 'Notes' }).getByRole('link', { name: new RegExp(`^${title}`) })
  await expect(row).toBeVisible({ timeout: 30_000 })
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null)

  await context.setOffline(true)
  await page.reload()
  await row.click()
  const body = page.getByRole('textbox', { name: 'Note' })
  await expect(body).toContainText('Step one')
  await body.locator('p').last().click()
  await page.keyboard.press('End')
  await page.keyboard.type(', step two')
  await expect(page.getByText('Saved', { exact: true })).toBeVisible({ timeout: 10_000 })

  await context.setOffline(false)
  await expect
    .poll(async () => ((await (await server.get(`/api/v1/notes/${id}`)).json()) as { content: string }).content, {
      timeout: 60_000,
    })
    .toBe('Step one, step two\n')
  await server.dispose()
})
