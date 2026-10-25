import { defineConfig, devices } from '@playwright/test'

const notesd = '127.0.0.1:8092'
const web = 'http://127.0.0.1:5175'

// Against Vite's dev server, so a test can import src/ modules into the page
// before any screen uses them. CI runs Chromium; the others when changing the engine.
export default defineConfig({
  testDir: 'e2e',
  // WebKit on macOS shares OPFS between contexts, so two tests at once would share a database.
  workers: 1,
  forbidOnly: !!process.env.CI,
  use: { baseURL: web, trace: 'retain-on-failure' },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
  ],
  webServer: [
    { command: `sh e2e/notesd.sh ${notesd}`, url: `http://${notesd}/healthz`, timeout: 120_000 },
    {
      command: 'vite --host 127.0.0.1 --port 5175 --strictPort',
      url: web,
      env: { NOTESD_SERVER: `http://${notesd}` },
    },
  ],
})
