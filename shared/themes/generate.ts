// Definitions → each platform's code (committed, so Xcode and Gradle never need Node).
// `node generate.ts` writes it; `--check` fails if any of it is stale. Format: docs/contributing/themes.md.

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { kotlin, KOTLIN } from './lib/kotlin.ts'
import { ROOT, load } from './lib/model.ts'
import { swift, SWIFT } from './lib/swift.ts'
import { WEB_CSS, WEB_TS, webCss, webTs } from './lib/web.ts'

/** Every output, by path from the repository root. */
export function outputs(): Map<string, string> {
  const lib = load()
  return new Map([
    [WEB_CSS, webCss(lib)],
    [WEB_TS, webTs(lib)],
    [SWIFT, swift(lib)],
    [KOTLIN, kotlin(lib)],
  ])
}

/** The outputs that differ from what's on disk. */
export function stale(): string[] {
  return [...outputs()].filter(([path, content]) => {
    try {
      return readFileSync(resolve(ROOT, path), 'utf8') !== content
    } catch {
      return true
    }
  }).map(([path]) => path)
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  if (process.argv.includes('--check')) {
    const paths = stale()
    if (paths.length) {
      console.error(`Out of date; run \`mise run themes\`:\n${paths.join('\n')}`)
      process.exit(1)
    }
  } else {
    for (const [path, content] of outputs()) {
      mkdirSync(dirname(resolve(ROOT, path)), { recursive: true })
      writeFileSync(resolve(ROOT, path), content)
      console.log(path)
    }
  }
}
