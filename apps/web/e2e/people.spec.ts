import { expect, type Page } from '@playwright/test'
import { owner, test } from './profile'

async function signIn(page: Page, username: string, password: string) {
  await page.goto('/')
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('button', { name: 'Settings' })).toBeVisible({ timeout: 30_000 })
}

test('an admin invites someone, who joins from the link, and is removed later', async ({ page, browser }) => {
  // The server outlives a single test.
  const name = `sam-${crypto.randomUUID().slice(0, 6)}`
  await signIn(page, owner.username, owner.password)
  await page.goto('/settings/server')
  const people = page.getByRole('region', { name: 'People' })
  await people.getByRole('button', { name: 'Invite someone…' }).click()

  const invite = page.getByRole('dialog', { name: 'Invite someone' })
  await invite.getByLabel('Username').fill(name)
  await invite.getByRole('button', { name: 'Invite' }).click()

  const sent = page.getByRole('dialog', { name: `Send ${name} this link` })
  const url = await sent.getByLabel('Link').inputValue()
  expect(url).toMatch(/^http:\/\/127\.0\.0\.1:5175\/setup#[\w-]{43}$/)
  // The tests run at 127.0.0.1, which no one else could reach.
  await expect(sent.getByText(/only works on this computer/)).toBeVisible()
  await sent.getByRole('button', { name: 'Copy' }).click()
  await expect(sent.getByRole('button', { name: 'Copied' })).toBeVisible()
  await sent.getByRole('button', { name: 'Done' }).click()
  const row = people.getByRole('listitem').filter({ hasText: name })
  await expect(row).toContainText('Invited · link expires in 7 days')

  const theirs = await browser.newContext()
  const them = await theirs.newPage()
  await them.goto(url)
  await expect(them.getByRole('heading', { name: `Welcome, ${name}.` })).toBeVisible()
  await expect(them).toHaveURL(/\/setup$/) // the token is out of the address bar
  await them.getByLabel('Password', { exact: true }).fill('their own password')
  await them.getByLabel('Confirm password').fill('their own password')
  await them.getByRole('button', { name: 'Get started' }).click()
  await expect(them.getByText(`You’re all set, ${name}`)).toBeVisible({ timeout: 30_000 })

  // Once only.
  const again = await theirs.newPage()
  await again.goto(url)
  await expect(again.getByRole('heading', { name: 'This link doesn’t work anymore' })).toBeVisible()
  await again.close()

  // The admin's list still says invited: the server's answer decides what the new link does.
  await expect(row).toContainText('Invited')
  await row.getByRole('button', { name: `Actions for ${name}` }).click()
  await page.getByRole('menuitem', { name: 'New invite link' }).click()
  const reset = page.getByRole('dialog', { name: `Send ${name} this link` })
  await expect(reset).toContainText('choose a new password, which signs them out everywhere')
  await reset.getByRole('button', { name: 'Done' }).click()
  await expect(row).toContainText('Joined')

  await page.reload()
  await expect(row).toContainText('Joined')
  await row.getByRole('button', { name: `Actions for ${name}` }).click()
  await page.getByRole('menuitem', { name: 'Remove from server…' }).click()
  await page.getByRole('dialog', { name: `Remove ${name}?` }).getByRole('button', { name: 'Remove' }).click()
  await expect(row).toHaveCount(0)

  await them.reload()
  await expect(them.getByRole('button', { name: 'Sign in' })).toBeVisible({ timeout: 30_000 })
  await theirs.close()
})

test('an invitation suggests a name, which the invitee may change as they join', async ({ page, browser }) => {
  const username = `mum-${crypto.randomUUID().slice(0, 6)}`
  await signIn(page, owner.username, owner.password)
  await page.goto('/settings/server')
  const people = page.getByRole('region', { name: 'People' })
  await people.getByRole('button', { name: 'Invite someone…' }).click()
  const invite = page.getByRole('dialog', { name: 'Invite someone' })
  await invite.getByLabel('Username').fill(username)
  await invite.getByLabel('Name (optional)').fill('Mum')
  await invite.getByRole('button', { name: 'Invite', exact: true }).click()
  const sent = page.getByRole('dialog', { name: 'Send Mum this link' })
  const url = await sent.getByLabel('Link').inputValue()
  await sent.getByRole('button', { name: 'Done' }).click()

  const theirs = await browser.newContext()
  const them = await theirs.newPage()
  await them.goto(url)
  await expect(them.getByRole('heading', { name: 'Welcome, Mum.' })).toBeVisible()
  const name = them.getByLabel('Your name (optional)')
  await expect(name).toHaveValue('Mum')
  await name.fill('Maria')
  await them.getByLabel('Password', { exact: true }).fill('her own password')
  await them.getByLabel('Confirm password').fill('her own password')
  await them.getByRole('button', { name: 'Get started' }).click()
  await expect(them.getByText('You’re all set, Maria')).toBeVisible({ timeout: 30_000 })
  await theirs.close()

  await page.reload()
  await expect(people.getByRole('listitem').filter({ hasText: username })).toContainText('Maria')
})

test('copies the link on plain http too, where there is no Clipboard API', async ({ page, context, browserName }) => {
  test.skip(browserName !== 'chromium', 'reading the clipboard back takes Chromium’s permissions')
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  // As on http://192.168.1.20: the API only exists in secure contexts, and the tests run on 127.0.0.1, which is one.
  await page.addInitScript(() => {
    Object.defineProperty(Navigator.prototype, 'clipboard', { get: () => undefined, configurable: true })
  })
  await signIn(page, owner.username, owner.password)
  await page.goto('/settings/server')
  expect(await page.evaluate(() => navigator.clipboard === undefined)).toBe(true)
  await page.getByRole('region', { name: 'People' }).getByRole('button', { name: 'Invite someone…' }).click()
  const invite = page.getByRole('dialog', { name: 'Invite someone' })
  const name = `copy-${crypto.randomUUID().slice(0, 6)}`
  await invite.getByLabel('Username').fill(name)
  await invite.getByRole('button', { name: 'Invite', exact: true }).click()

  const sent = page.getByRole('dialog', { name: `Send ${name} this link` })
  const url = await sent.getByLabel('Link').inputValue()
  await sent.getByRole('button', { name: 'Copy' }).click()
  await expect(sent.getByRole('button', { name: 'Copied' })).toBeVisible()

  const reader = await context.newPage()
  await reader.goto('/setup')
  await reader.bringToFront()
  expect(await reader.evaluate(() => navigator.clipboard.readText())).toBe(url)
  await reader.close()
})

test('a used or made-up link says it no longer works', async ({ page }) => {
  await page.goto('/setup#not-a-real-token')
  await expect(page.getByRole('heading', { name: 'This link doesn’t work anymore' })).toBeVisible()
  await page.getByRole('button', { name: 'Go to sign in' }).click()
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
})
