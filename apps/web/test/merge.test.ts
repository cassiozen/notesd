import { readFileSync, writeFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import fixtures from '../../../shared/markdown-fixtures/merge.json'
import { canonical, parse } from '../src/editor/markdown'
import { type BodyMerge, mergeBody } from '../src/editor/merge'

type Expect = { clean: string } | { partial: { theirs: string; mine: string } } | string

function expected(e: Expect): BodyMerge {
  if (typeof e === 'string') return { kind: 'conflict' }
  if ('clean' in e) return { kind: 'clean', content: e.clean }
  return { kind: 'partial', theirs: e.partial.theirs, mine: e.partial.mine }
}

describe('merge fixtures', () => {
  for (const c of fixtures.cases) {
    it(c.name, () => {
      expect(mergeBody(c.base, c.mine, c.theirs)).toEqual(expected(c.expect as Expect))
    })
  }
})

const numbered = (prefix: string) => Array.from({ length: 2100 }, (_, i) => `${prefix}${i}`).join('\n\n')

describe('merge size bound', () => {
  it('gives up on huge rewrites rather than stall', () => {
    const doc = numbered
    expect(mergeBody(doc('a'), doc('b'), doc('c'))).toEqual({ kind: 'conflict' })
  })
})

// --- properties on random documents ---------------------------------------------

function random(seed: number) {
  let s = seed
  return () => {
    s = (s + 0x6d2b79f5) | 0
    let t = Math.imul(s ^ (s >>> 15), 1 | s)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const WORDS = ['milk', 'eggs', 'plan', 'trip', 'call', 'idea', 'fix', 'read']

function generator(next: () => number) {
  const pick = <T>(xs: T[]) => xs[Math.floor(next() * xs.length)] as T
  const text = () => `${pick(WORDS)} ${pick(WORDS)}`
  const block = (): string => {
    switch (pick(['p', 'p', 'h', 'list', 'tasks', 'quote', 'fence'])) {
      case 'h':
        return `${'#'.repeat(1 + Math.floor(next() * 3))} ${text()}`
      case 'list':
        return Array.from({ length: 1 + Math.floor(next() * 3) }, () => `- ${text()}`).join('\n')
      case 'tasks':
        return Array.from({ length: 1 + Math.floor(next() * 3) }, () => `- [${next() < 0.5 ? ' ' : 'x'}] ${text()}`).join('\n')
      case 'quote':
        return `> ${text()}`
      case 'fence':
        return '```\n' + text() + '\n```'
      default:
        return text()
    }
  }
  const doc = () => Array.from({ length: Math.floor(next() * 6) }, block)
  // A side's edit: change, add, remove or move a block, or toggle or reword inside one.
  const edit = (blocks: string[]): string[] => {
    const out = [...blocks]
    for (let n = 1 + Math.floor(next() * 2); n > 0; n--) {
      const at = Math.floor(next() * (out.length + 1))
      const kind = out.length === 0 ? 'add' : pick(['add', 'remove', 'reword', 'toggle', 'move', 'level'])
      const i = Math.min(at, out.length - 1)
      if (kind === 'add') out.splice(at, 0, block())
      else if (kind === 'remove') out.splice(i, 1)
      else if (kind === 'move') out.push(...out.splice(i, 1))
      else if (kind === 'toggle') out[i] = (out[i] ?? '').replace(/\[( |x)\]/, (_, c: string) => (c === ' ' ? '[x]' : '[ ]'))
      else if (kind === 'level') out[i] = (out[i] ?? '').replace(/^#{1,3} /, (h) => (h.length === 4 ? '# ' : `#${h}`))
      else out[i] = (out[i] ?? '').replace(/[a-z]+/, pick(WORDS))
    }
    return out
  }
  return { doc, edit }
}

const md = (blocks: string[]) => canonical(blocks.join('\n\n'))

/** Paragraphs and raw blocks: the text a merge must never drop. */
function leaves(markdown: string): Set<string> {
  const out = new Set<string>()
  parse(markdown).descendants((node) => {
    if (node.type.name === 'paragraph' || node.type.name === 'rawBlock') {
      // An empty note parses to one empty paragraph: nothing to keep.
      if (node.content.size > 0 || node.type.name === 'rawBlock') out.add(JSON.stringify(node.toJSON()))
      return false
    }
    return true
  })
  return out
}

describe('merge properties', () => {
  const next = random(20261107)
  const { doc, edit } = generator(next)
  for (let n = 0; n < 400; n++) {
    const base = doc()
    const x = edit(base)
    const y = edit(base)
    const [b, mine, theirs] = [md(base), md(x), md(y)]
    it(`case ${n}`, () => {
      expect(mergeBody(b, mine, b)).toEqual({ kind: 'clean', content: mine })
      expect(mergeBody(b, b, theirs)).toEqual({ kind: 'clean', content: theirs })
      expect(mergeBody(b, mine, mine)).toEqual({ kind: 'clean', content: mine })

      const merged = mergeBody(b, mine, theirs)
      const swapped = mergeBody(b, theirs, mine)
      if (merged.kind === 'partial') {
        expect(swapped).toEqual({ kind: 'partial', theirs: merged.mine, mine: merged.theirs })
      } else {
        expect(swapped).toEqual(merged)
      }
      const outputs = merged.kind === 'clean' ? [merged.content] : merged.kind === 'partial' ? [merged.theirs, merged.mine] : []
      for (const o of outputs) expect(canonical(o)).toBe(o)

      // Whatever a side added survives, in the merge or in one of the two partial versions.
      const kept = new Set(outputs.flatMap((o) => Array.from(leaves(o))))
      if (merged.kind !== 'conflict') {
        const before = leaves(b)
        for (const side of [mine, theirs]) {
          for (const leaf of leaves(side)) if (!before.has(leaf)) expect(kept).toContain(leaf)
        }
      }
    })
  }
})

// Random cases with this implementation's answers, which the Kotlin and Swift ports must match.
// NOTESD_REGENERATE=1 rewrites the file after a deliberate change to the algorithm.
describe('generated merge corpus', () => {
  // Tests run from apps/web.
  const path = '../../shared/markdown-fixtures/merge-generated.json'
  const next = random(7)
  const { doc, edit } = generator(next)
  const cases = Array.from({ length: 300 }, (_, n) => {
    const base = doc()
    return { name: `generated ${n}`, base: md(base), mine: md(edit(base)), theirs: md(edit(base)) }
  })
  const answer = (c: (typeof cases)[number]) => {
    const r = mergeBody(c.base, c.mine, c.theirs)
    return r.kind === 'clean' ? { clean: r.content } : r.kind === 'partial' ? { partial: { theirs: r.theirs, mine: r.mine } } : 'conflict'
  }
  if (process.env.NOTESD_REGENERATE) {
    const lines = cases.map((c) => '    ' + JSON.stringify({ ...c, expect: answer(c) }))
    const description = 'Generated by apps/web/test/merge.test.ts (NOTESD_REGENERATE=1); same shape as merge.json.'
    writeFileSync(path, `{\n  "description": ${JSON.stringify(description)},\n  "cases": [\n${lines.join(',\n')}\n  ]\n}\n`)
  }
  const stored = JSON.parse(readFileSync(path, 'utf8')) as { cases: { name: string; expect: Expect }[] }
  it('matches the stored answers', () => {
    expect(stored.cases.map((c) => c.expect)).toEqual(cases.map(answer))
  })
})
