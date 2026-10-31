import { createHash } from 'node:crypto'
import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import react from '@vitejs/plugin-react'
import { transformWithOxc } from 'vite'
import { defineConfig } from 'vitest/config'

const outDir = resolve(import.meta.dirname, '../../server/internal/web/dist')
// The sync engine, from Kotlin (apps/android/core); `pnpm core` builds it.
const core = resolve(import.meta.dirname, '../android/core/build/dist/js/productionLibrary/notes-android-core.mjs')
const sqliteNode = resolve(import.meta.dirname, 'node_modules/@sqlite.org/sqlite-wasm/dist/node.mjs')
const server = process.env.NOTESD_SERVER ?? 'http://127.0.0.1:8080'
const base = (path: string) => path.slice(path.lastIndexOf('/') + 1)

export default defineConfig({
  plugins: [
    react(),
    {
      // emptyOutDir wipes the tracked .gitkeep that keeps //go:embed working
      // on a fresh clone; put it back.
      name: 'keep-gitkeep',
      apply: 'build',
      closeBundle() {
        writeFileSync(resolve(outDir, '.gitkeep'), '')
      },
    },
    {
      // After the build (and public/ copy) so the precache list is every file actually served.
      name: 'service-worker',
      apply: 'build',
      async closeBundle() {
        const files = readdirSync(outDir, { recursive: true, withFileTypes: true })
          // .woff: fallback that any browser with service workers skips for .woff2. sqlite-wasm's
          // worker1 API and "opfs" VFS ship along but go unused: the engine opens the SAH-pool VFS itself.
          .filter(
            (f) =>
              f.isFile() &&
              !f.name.startsWith('.') &&
              f.name !== 'sw.js' &&
              !/\.(map|woff)$/.test(f.name) &&
              !/^sqlite3-(worker1|opfs-async-proxy)-/.test(f.name),
          )
          .map((f) => resolve(f.parentPath, f.name).slice(outDir.length).replaceAll('\\', '/'))
          .toSorted()
        // Content hash, not names: public/ files keep their name when edited.
        const hash = createHash('sha256')
        for (const f of files) hash.update(f).update(readFileSync(resolve(outDir, `.${f}`)))
        // The engine's worker and what only it loads: a browser that keeps no notes never needs them.
        const text = (f: string) => readFileSync(resolve(outDir, `.${f}`), 'utf8')
        // A font the CSP blocks fails quietly (console error, fallback face): fail the build instead.
        const inlined = files.find((f) => f.endsWith('.css') && text(f).includes('url(data:font'))
        if (inlined) throw new Error(`service-worker: ${inlined} inlines a data: font, which the CSP's font-src blocks`)
        const worker = files.find((f) => /^\/assets\/worker-[^/]+\.js$/.test(f))
        // Not found, the engine would be precached for every browser, silently.
        if (!worker) throw new Error('service-worker: no /assets/worker-*.js in the build')
        const shell = files.filter((f) => f !== worker && /\.(js|html)$/.test(f)).map(text).join('\n')
        const engine = files.filter((f) => f === worker || (text(worker).includes(base(f)) && !shell.includes(base(f))))
        const precache = files.filter((f) => !engine.includes(f))
        const src = resolve(import.meta.dirname, 'src/sw.ts')
        const { code } = await transformWithOxc(readFileSync(src, 'utf8'), src, { lang: 'ts' })
        const header =
          `const PRECACHE = ${JSON.stringify(precache)};\nconst ENGINE = ${JSON.stringify(engine)};\n` +
          `const VERSION = '${hash.digest('hex').slice(0, 16)}';\n`
        writeFileSync(resolve(outDir, 'sw.js'), header + code)
      },
    },
  ],
  resolve: { alias: { '@notes/core': core } },
  // Pre-bundling moves sqlite-wasm away from the .wasm it loads relative to itself.
  optimizeDeps: { exclude: ['@sqlite.org/sqlite-wasm'] },
  worker: { format: 'es' },
  build: {
    outDir,
    emptyOutDir: true,
    // The CSP's font-src is 'self' only (server/internal/web/web.go): an inlined data: font is blocked.
    assetsInlineLimit: (file) => (/\.woff2?$/.test(file) ? false : undefined),
  },
  server: {
    // changeOrigin must stay false (the string shorthand turns it on): the
    // server rejects mutations whose Origin host differs from Host, and the
    // browser's Origin is the Vite dev server.
    proxy: Object.fromEntries(
      ['/api', '/healthz', '/readyz'].map((path) => [path, { target: server, changeOrigin: false }]),
    ),
  },
  test: {
    globalSetup: ['test/global-setup.ts'],
    // Above asyncUtilTimeout (test/setup.ts), so a slow wait fails with its own message, not a bare timeout.
    testTimeout: 15_000,
    projects: [
      {
        extends: true,
        test: {
          name: 'app',
          include: ['{src,test}/**/*.test.{ts,tsx}'],
          exclude: ['test/engine/**'],
          environment: 'jsdom',
          setupFiles: ['test/setup.ts'],
        },
        // The Node build in jsdom too, for tests on the engine: the browser one fetches its .wasm.
        resolve: { alias: { '@sqlite.org/sqlite-wasm': sqliteNode } },
      },
      { extends: true, test: { name: 'engine', include: ['test/engine/**/*.test.ts'], environment: 'node' } },
    ],
  },
})
