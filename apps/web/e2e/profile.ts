import { test as base, type BrowserContext, type Page } from '@playwright/test'

export const owner = { username: 'e2e', password: 'e2e password' }

// A profile on disk: WebKit has no OPFS in an ephemeral context, as Safari has none in a private window.
export const test = base.extend<{ context: BrowserContext; page: Page }>({
  context: async ({ playwright, browserName, baseURL }, provide, info) => {
    const context = await playwright[browserName].launchPersistentContext(info.outputPath('profile'), { baseURL })
    // WebKit on macOS keeps OPFS outside the profile, shared by every test and run.
    const page = context.pages()[0] ?? (await context.newPage())
    await page.goto('/')
    await page.evaluate(async () => {
      const root = await navigator.storage.getDirectory()
      for await (const name of root.keys()) await root.removeEntry(name, { recursive: true })
    })
    await provide(context)
    await context.close()
  },
  page: async ({ context }, provide) => {
    await provide(context.pages()[0] ?? (await context.newPage()))
  },
})

