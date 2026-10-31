import { createHash } from 'node:crypto'
import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import react from '@vitejs/plugin-react'
import { transformWithOxc } from 'vite'
import { defineConfig } from 'vitest/config'

const outDir = resolve(import.meta.dirname, '../../server/internal/web/dist')
// The sync engine, from Kotlin (apps/android/core); `pnpm core` builds it.
const core = resolve(import.meta.dirname, '../android/core/build/dist/js/productionLibrary/notes-android-core.mjs')
const server = process.env.NOTESD_SERVER ?? 'http://127.0.0.1:8080'

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
          // .woff: fallback that any browser with service workers skips for .woff2.
          .filter((f) => f.isFile() && !f.name.startsWith('.') && f.name !== 'sw.js' && !/\.(map|woff)$/.test(f.name))
          .map((f) => resolve(f.parentPath, f.name).slice(outDir.length).replaceAll('\\', '/'))
          .toSorted()
        // Content hash, not names: public/ files keep their name when edited.
        const hash = createHash('sha256')
        for (const f of files) hash.update(f).update(readFileSync(resolve(outDir, `.${f}`)))
        const src = resolve(import.meta.dirname, 'src/sw.ts')
        const { code } = await transformWithOxc(readFileSync(src, 'utf8'), src, { lang: 'ts' })
        const header = `const PRECACHE = ${JSON.stringify(files)};\nconst VERSION = '${hash.digest('hex').slice(0, 16)}';\n`
        writeFileSync(resolve(outDir, 'sw.js'), header + code)
      },
    },
  ],
  resolve: { alias: { '@notes/core': core } },
  // Pre-bundling moves sqlite-wasm away from the .wasm it loads relative to itself.
  optimizeDeps: { exclude: ['@sqlite.org/sqlite-wasm'] },
  worker: { format: 'es' },
  build: { outDir, emptyOutDir: true },
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
      },
      // Node, not jsdom: sqlite-wasm loads its .wasm from disk only there.
      { extends: true, test: { name: 'engine', include: ['test/engine/**/*.test.ts'], environment: 'node' } },
    ],
  },
})
