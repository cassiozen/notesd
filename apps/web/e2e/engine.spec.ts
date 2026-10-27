import { expect, type Page } from '@playwright/test'
import type { LocalNote } from '../src/offline/protocol'
import type { Engine, connectEngine } from '../src/offline/tab'
import { owner, test } from './profile'

// The engine alone: each page imports it from src/ and drives it directly.

declare global {
  interface Window {
    connectEngine: typeof connectEngine
    engine: Engine
    saves: Promise<LocalNote | null>[]
    changes: number
  }
}

/** Opens the app and connects the engine; the first page in a context signs in, the rest share its session. */
async function connect(page: Page, { signIn = false } = {}) {
  await page.goto('/')
  await page.addScriptTag({
    type: 'module',
    content: "import { connectEngine } from '/src/offline/tab.ts'; window.connectEngine = connectEngine",
  })
  await page.waitForFunction(() => 'connectEngine' in window)
  await page.evaluate(
    async (arg) => {
      const res = arg.signIn
        ? await fetch('/api/v1/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username: arg.username, password: arg.password, client: 'web' }),
          })
        : await fetch('/api/v1/auth/session')
      const { csrfToken } = (await res.json()) as { csrfToken: string }
      window.engine = window.connectEngine(arg.username, csrfToken)
    },
    { signIn, ...owner },
  )
}

const synced = (page: Page) =>
  expect.poll(() => page.evaluate(async () => (await window.engine.call('status')).kind), { timeout: 15_000 }).toBe('Synced')

const titles = (page: Page) =>
  page.evaluate(async () => (await window.engine.call('list', null, null)).map((n) => n.title).toSorted())

const onServer = (page: Page, id: string) =>
  page.evaluate(async (note) => (await fetch(`/api/v1/notes/${note}`)).status, id)

const engineLocks = (page: Page) =>
  page.evaluate(async () => (await navigator.locks.query()).held?.filter((l) => l.name === 'notesd-engine').length)

test('notes stay on this device across a reload, and reach the server by themselves', async ({ page, context }) => {
  // Uploads fail until the end, so only this device can have the note.
  const uploads = '**/api/v1/notes**'
  await context.route(uploads, (route) => (route.request().method() === 'GET' ? route.continue() : route.abort()))
  await connect(page, { signIn: true })
  await synced(page)
  const id = crypto.randomUUID()
  await page.evaluate((note) => window.engine.call('save', { id: note, title: 'Kept', content: 'here\n', spaceId: 'default' }), id)

  await page.reload()
  await connect(page)

  expect(await titles(page)).toContain('Kept')
  expect(await onServer(page, id)).toBe(404)
  await context.unroute(uploads)
  await page.evaluate(() => window.engine.call('requestSync'))
  await expect.poll(() => onServer(page, id), { timeout: 15_000 }).toBe(200)
})

test('tabs share one engine, and see what the others change', async ({ context }) => {
  const a = await context.newPage()
  await connect(a, { signIn: true })
  await synced(a)
  const b = await context.newPage()
  await connect(b)
  await a.evaluate(() => {
    window.changes = 0
    window.engine.on((e) => {
      if (e.type === 'change') window.changes++
    })
  })

  await b.evaluate(() => window.engine.call('save', { id: crypto.randomUUID(), title: 'From B', content: '', spaceId: 'default' }))

  await expect.poll(() => a.evaluate(() => window.changes)).toBeGreaterThan(0)
  expect(await titles(a)).toContain('From B')
  expect(await titles(b)).toEqual(await titles(a))
  expect(await engineLocks(b)).toBe(1)
})

test('when the tab running the engine closes, the next takes over and no call is lost', async ({ context }) => {
  const a = await context.newPage()
  await connect(a, { signIn: true })
  await synced(a)
  const b = await context.newPage()
  await connect(b)
  const ids = Array.from({ length: 30 }, () => crypto.randomUUID())
  // Other browsers' runs share the account.
  const run = crypto.randomUUID().slice(0, 8)

  // Some of these are on their way to A, or running there, when it goes.
  await b.evaluate(
    (arg) => {
      window.saves = arg.ids.map((id, i) =>
        window.engine.call('save', { id, title: `${arg.run} ${i}`, content: '', spaceId: 'default' }),
      )
    },
    { ids, run },
  )
  await a.close()

  const saved = await b.evaluate(async () => (await Promise.all(window.saves)).filter((n) => n !== null).length)
  expect(saved).toBe(30)
  expect((await titles(b)).filter((t) => t.startsWith(run))).toHaveLength(30)
  // A may have answered every save before closing, B not holding the lock yet.
  await expect.poll(() => engineLocks(b)).toBe(1)
  await b.evaluate(() => window.engine.call('sync'))
  for (const id of ids) expect(await onServer(b, id)).toBe(200)
})
