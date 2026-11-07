import { Fragment, type Node as PMNode } from '@tiptap/pm/model'
import { canonical, parse, schema, serialize } from './markdown'

// Block-level three-way merge of note bodies (spec §6.2). The reference: the Kotlin and Swift
// ports follow it step for step; shared/markdown-fixtures/merge.json pins all three byte for byte.

/** partial: `theirs`/`mine` hold that side's version of each conflicting block, and both every change that merged. */
export type BodyMerge =
  | { kind: 'clean'; content: string }
  | { kind: 'partial'; theirs: string; mine: string }
  | { kind: 'conflict' }

/** Above this many LCS cells (after the common prefix) a merge gives up: a copy is cheaper than the wait. */
export const MAX_CELLS = 4_000_000

class TooLarge extends Error {}

/** xs[i], which the caller knows is in range. */
function at<T>(xs: readonly T[], i: number): T {
  const x = xs[i]
  if (x === undefined) throw new RangeError(`No element ${i}`)
  return x
}

/** Both sides of a merged sequence; equal where nothing conflicted. */
type Merged = { theirs: PMNode[]; mine: PMNode[]; conflicts: number }

export function mergeBody(base: string, mine: string, theirs: string): BodyMerge {
  let merged: Merged
  try {
    merged = mergeSeq(children(parse(base)), children(parse(mine)), children(parse(theirs)))
  } catch (err) {
    if (err instanceof TooLarge) return { kind: 'conflict' }
    throw err
  }
  const theirsOut = render(merged.theirs)
  const mineOut = render(merged.mine)
  const outputs = merged.conflicts === 0 ? [theirsOut] : [theirsOut, mineOut]
  // Not surviving its own round trip, or repeating a block more than any input does, means a bug
  // or a diff3 anomaly: a copy loses nothing.
  if (outputs.some((o) => canonical(o) !== o)) return { kind: 'conflict' }
  const most = maxCounts([base, mine, theirs])
  if (outputs.some((o) => exceeds(leafCounts(o), most))) return { kind: 'conflict' }
  if (merged.conflicts === 0) return { kind: 'clean', content: theirsOut }
  if (theirsOut === canonical(theirs) && mineOut === canonical(mine)) return { kind: 'conflict' }
  return { kind: 'partial', theirs: theirsOut, mine: mineOut }
}

function children(node: PMNode): PMNode[] {
  const out: PMNode[] = []
  node.forEach((c) => out.push(c))
  return out
}

function render(blocks: PMNode[]): string {
  return blocks.length === 0 ? '' : serialize(schema.topNodeType.create(null, Fragment.fromArray(blocks)))
}

// --- sequences ----------------------------------------------------------------

/** Each element of `a`'s match in `b`, or -1. Ties go to deleting from `a`: ports must agree, or merges differ. */
function lcs(a: PMNode[], b: PMNode[]): number[] {
  const match = Array.from({ length: a.length }, () => -1)
  let p = 0
  while (p < a.length && p < b.length && at(a, p).eq(at(b, p))) {
    match[p] = p
    p++
  }
  const n = a.length - p
  const m = b.length - p
  if (n * m > MAX_CELLS) throw new TooLarge()
  const w = m + 1
  const table = new Int32Array((n + 1) * w)
  const cell = (i: number, j: number) => table[i * w + j] ?? 0
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      table[i * w + j] = at(a, p + i).eq(at(b, p + j)) ? cell(i + 1, j + 1) + 1 : Math.max(cell(i + 1, j), cell(i, j + 1))
    }
  }
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (at(a, p + i).eq(at(b, p + j))) {
      match[p + i] = p + j
      i++
      j++
    } else if (cell(i + 1, j) >= cell(i, j + 1)) {
      i++
    } else {
      j++
    }
  }
  return match
}

const sameNodes = (a: PMNode[], b: PMNode[]) => a.length === b.length && a.every((n, i) => n.eq(at(b, i)))

/** diff3 (Khanna, Kunal & Pierce): stable runs where all three match, mergeChunk for what lies between. */
function mergeSeq(base: PMNode[], mine: PMNode[], theirs: PMNode[]): Merged {
  const toMine = lcs(base, mine)
  const toTheirs = lcs(base, theirs)
  const out: Merged = { theirs: [], mine: [], conflicts: 0 }
  let i = 0
  let a = 0
  let b = 0
  for (;;) {
    while (i < base.length && toMine[i] === a && toTheirs[i] === b) {
      out.theirs.push(at(base, i))
      out.mine.push(at(base, i))
      i++
      a++
      b++
    }
    if (i === base.length && a === mine.length && b === theirs.length) return out
    let k = i
    while (k < base.length && (toMine[k] === -1 || toTheirs[k] === -1)) k++
    const nextA = k < base.length ? at(toMine, k) : mine.length
    const nextB = k < base.length ? at(toTheirs, k) : theirs.length
    const chunk = mergeChunk(
      base.slice(i, k),
      mine.slice(a, nextA),
      theirs.slice(b, nextB),
      toMine.slice(i, k).map((x) => (x === -1 ? -1 : x - a)),
      toTheirs.slice(i, k).map((x) => (x === -1 ? -1 : x - b)),
    )
    append(out, chunk)
    i = k
    a = nextA
    b = nextB
  }
}

function append(out: Merged, part: Merged) {
  out.theirs.push(...part.theirs)
  out.mine.push(...part.mine)
  out.conflicts += part.conflicts
}

const settled = (nodes: PMNode[]): Merged => ({ theirs: nodes, mine: nodes, conflicts: 0 })
const conflict = (mine: PMNode[], theirs: PMNode[]): Merged => ({ theirs, mine, conflicts: 1 })

/** `toMine`/`toTheirs`: each base element's match in that side's chunk, or -1. */
function mergeChunk(base: PMNode[], mine: PMNode[], theirs: PMNode[], toMine: number[], toTheirs: number[]): Merged {
  if (sameNodes(mine, base)) return settled(theirs)
  if (sameNodes(theirs, base) || sameNodes(mine, theirs)) return settled(mine)
  const mineInPlace = inPlace(base, mine)
  const theirsInPlace = inPlace(base, theirs)
  if (mineInPlace && theirsInPlace) {
    // Paired block by block: only blocks both sides changed can conflict.
    const out: Merged = { theirs: [], mine: [], conflicts: 0 }
    base.forEach((b, k) => append(out, mergeBlock(b, at(mine, k), at(theirs, k))))
    return out
  }
  if (theirsInPlace) return applyAround(base, mine, toMine, theirs) ?? conflict(mine, theirs)
  if (mineInPlace) return applyAround(base, theirs, toTheirs, mine) ?? conflict(mine, theirs)
  return conflict(mine, theirs)
}

/** Edited block by block, without adding, removing or changing the kind of any. */
function inPlace(base: PMNode[], side: PMNode[]): boolean {
  return side.length === base.length && side.every((n, k) => n.type === at(base, k).type)
}

/** `moved`'s sequence with `edited`'s version of each block it kept; null if it removed one `edited` changed. */
function applyAround(base: PMNode[], moved: PMNode[], toMoved: number[], edited: PMNode[]): Merged | null {
  const out: PMNode[] = []
  let j = 0
  for (const [k, b] of base.entries()) {
    const to = at(toMoved, k)
    if (to === -1) {
      if (!at(edited, k).eq(b)) return null
      continue
    }
    out.push(...moved.slice(j, to), at(edited, k))
    j = to + 1
  }
  out.push(...moved.slice(j))
  return settled(out)
}

// --- blocks -------------------------------------------------------------------

/** §6.1's rule for one value: the side that changed it, or null if both changed it differently. */
function field<T>(base: T, mine: T, theirs: T, same: (x: T, y: T) => boolean): { value: T } | null {
  if (same(mine, base)) return { value: theirs }
  if (same(theirs, base) || same(mine, theirs)) return { value: mine }
  return null
}

const sameValue = <T>(x: T, y: T) => x === y
const sameContent = (x: Fragment, y: Fragment) => x.eq(y)

/** Same-kind blocks at one position, changed on at least one side. */
function mergeBlock(base: PMNode, mine: PMNode, theirs: PMNode): Merged {
  if (mine.eq(base)) return settled([theirs])
  if (theirs.eq(base) || mine.eq(theirs)) return settled([mine])
  const whole = conflict([mine], [theirs])
  switch (base.type.name) {
    case 'heading': {
      const level = field(base.attrs.level, mine.attrs.level, theirs.attrs.level, sameValue)
      const content = field(base.content, mine.content, theirs.content, sameContent)
      if (!level || !content) return whole
      return settled([base.type.create({ ...base.attrs, level: level.value }, content.value)])
    }
    case 'orderedList': {
      const start = field(base.attrs.start, mine.attrs.start, theirs.attrs.start, sameValue)
      if (!start) return whole
      return container(base, mine, theirs, { ...base.attrs, start: start.value })
    }
    case 'taskItem': {
      const checked = field(base.attrs.checked, mine.attrs.checked, theirs.attrs.checked, sameValue)
      if (!checked) return whole
      return container(base, mine, theirs, { ...base.attrs, checked: checked.value })
    }
    case 'bulletList':
    case 'taskList':
    case 'listItem':
    case 'blockquote':
      return container(base, mine, theirs, base.attrs)
    default:
      // Paragraphs and raw blocks are atomic: both changed is a conflict.
      return whole
  }
}

/** A container whose children merge as a sequence; each side rebuilt around its own. */
function container(base: PMNode, mine: PMNode, theirs: PMNode, attrs: PMNode['attrs']): Merged {
  const inner = mergeSeq(children(base), children(mine), children(theirs))
  const build = (nodes: PMNode[]): PMNode[] | null => {
    // Emptied (both sides removed different children): the container goes too.
    if (nodes.length === 0) return []
    // A list item must start with a paragraph; anything else conflicts whole.
    if ((base.type.name === 'listItem' || base.type.name === 'taskItem') && at(nodes, 0).type.name !== 'paragraph') {
      return null
    }
    return [base.type.create(attrs, Fragment.fromArray(nodes))]
  }
  const t = build(inner.theirs)
  const m = build(inner.mine)
  if (!t || !m) return conflict([mine], [theirs])
  return { theirs: t, mine: m, conflicts: inner.conflicts }
}

// --- duplication check ----------------------------------------------------------

/** How often each leaf block (paragraph, heading, raw block) occurs, by its JSON. */
function leafCounts(markdown: string): Map<string, number> {
  const counts = new Map<string, number>()
  parse(markdown).descendants((node) => {
    if (!node.isTextblock && !node.isAtom) return true
    if (node.isInline) return false
    const key = JSON.stringify(node.toJSON())
    counts.set(key, (counts.get(key) ?? 0) + 1)
    return false
  })
  return counts
}

function maxCounts(inputs: string[]): Map<string, number> {
  const most = new Map<string, number>()
  for (const input of inputs) {
    for (const [key, n] of leafCounts(input)) most.set(key, Math.max(most.get(key) ?? 0, n))
  }
  return most
}

function exceeds(counts: Map<string, number>, most: Map<string, number>): boolean {
  // A block no input has is new (a heading with a merged level), not a duplicate.
  for (const [key, n] of counts) if (n > (most.get(key) ?? Infinity)) return true
  return false
}
