import { Editor } from '@tiptap/core'
import { afterEach, describe, expect, it } from 'vitest'
import fences from '../../../../shared/markdown-fixtures/fences.json'
import { editorExtensions } from './extensions'
import { buildFence, fenceOpener, parseFence } from './fence'
import { parse, serialize } from './markdown'

describe('parseFence', () => {
  for (const c of fences.cases) {
    it(c.name, () => expect(parseFence(c.source)).toEqual(c.fence))
  }
})

describe('buildFence', () => {
  const cases: [string, string, string, string][] = [
    ['code with a language', 'go', 'a := 1', '```go\na := 1\n```'],
    ['empty code', '', '', '```\n```'],
    ['a fence inside the code gets a longer one', 'md', '```js\nx\n```', '````md\n```js\nx\n```\n````'],
    ['a language loses spaces and backticks', 'c `x', 'y', '```cx\ny\n```'],
  ]
  for (const [name, lang, code, expected] of cases) {
    it(name, () => {
      expect(buildFence(lang, code)).toBe(expected)
      expect(parseFence(expected)).toEqual({ lang: lang.replace(/[\s`]/g, ''), code })
    })
  }
})

describe('fenceOpener', () => {
  it('reads ``` and ```lang', () => {
    expect(fenceOpener('```')).toEqual({ lang: '' })
    expect(fenceOpener('```go')).toEqual({ lang: 'go' })
    expect(fenceOpener('~~~ py ')).toEqual({ lang: 'py' })
    expect(fenceOpener('``')).toBeNull()
    expect(fenceOpener('```go x')).toBeNull()
    expect(fenceOpener('a ```')).toBeNull()
  })
})

const mounted: Editor[] = []
afterEach(() => {
  for (const e of mounted.splice(0)) {
    const element = e.options.element
    e.destroy()
    if (element instanceof Element) element.remove()
  }
})

// `typed` is a paragraph's text as typed, not Markdown: ``` alone would parse as a fence.
function open(markdown: string, typed?: string) {
  const element = document.createElement('div')
  document.body.append(element)
  const doc = typed === undefined ? parse(markdown).toJSON() : { content: [{ type: 'paragraph', content: [{ type: 'text', text: typed }] }] }
  const e = new Editor({
    element,
    extensions: editorExtensions,
    content: { type: 'doc', content: [{ type: 'title', content: [{ type: 'text', text: 'Note' }] }, ...doc.content] },
  })
  mounted.push(e)
  return e
}
const body = (e: Editor) => {
  const doc = e.state.doc
  return serialize(doc.type.create(null, doc.content.cut(doc.child(0).nodeSize)))
}
const box = (e: Editor) => {
  const textarea = e.view.dom.querySelector('textarea')
  if (!textarea) throw new Error('no code box')
  return textarea
}
const key = (el: Element, k: string) => el.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true }))

describe('the code box', () => {
  it('``` and Enter opens a code box that writes a fence', () => {
    const e = open('', '```go')
    e.commands.focus('end')
    key(e.view.dom, 'Enter')
    const textarea = box(e)
    textarea.value = 'fmt.Println(1)'
    key(textarea, 'Escape')
    expect(body(e)).toBe('```go\nfmt.Println(1)\n```\n')
    expect(e.view.dom.querySelector('textarea')).toBeNull()
  })

  it('leaving an empty code box removes it', () => {
    const e = open('', '```')
    e.commands.focus('end')
    key(e.view.dom, 'Enter')
    key(box(e), 'Escape')
    expect(body(e)).not.toContain('```')
  })

  it('a click edits a fence; unchanged keeps its source byte for byte', () => {
    const e = open('~~~  py\nx = 1\n~~~')
    const pre = e.view.dom.querySelector('pre[data-fence]') as HTMLElement
    pre.click()
    key(box(e), 'Escape')
    expect(body(e)).toBe('~~~  py\nx = 1\n~~~\n')
  })

  it('the language field suggests the languages that get highlighted', async () => {
    const e = open('```\nx\n```')
    ;(e.view.dom.querySelector('pre[data-fence]') as HTMLElement).click()
    const input = e.view.dom.querySelector('input[data-lang]')
    const list = document.getElementById(input?.getAttribute('list') ?? '')
    await expect.poll(() => list?.querySelector('option[value="go"]')).not.toBeNull()
  })

  it('a code box left with a language but no code removes the block', () => {
    const e = open('', '```go')
    e.commands.focus('end')
    key(e.view.dom, 'Enter')
    key(box(e), 'Escape')
    expect(body(e)).not.toContain('```')
  })

  it('emptying the only block in the body or a quote leaves an empty line, not an invalid note', () => {
    for (const markdown of ['```js\nx\n```', '> ```js\n> x\n> ```']) {
      const e = open(markdown)
      ;(e.view.dom.querySelector('pre[data-fence]') as HTMLElement).click()
      box(e).value = ''
      key(box(e), 'Escape')
      expect(() => e.state.doc.check()).not.toThrow()
      expect(body(e)).not.toContain('```')
      expect(e.state.selection.$from.parent.type.name).toBe('paragraph')
    }
  })

  it('highlights a known language and leaves an unknown one plain', async () => {
    const e = open('```js\nconst a = "x"\n```\n\n```foobar\nconst a = "x"\n```')
    const [known, unknown] = e.view.dom.querySelectorAll('pre[data-fence] code')
    await expect.poll(() => known?.querySelector('.hljs-keyword')?.textContent).toBe('const')
    expect(known?.querySelector('.hljs-string')?.textContent).toBe('"x"')
    expect(unknown?.children.length).toBe(0)
    expect(unknown?.textContent).toBe('const a = "x"')
  })

  it('other raw blocks stay read-only', () => {
    const e = open('| a |\n| - |')
    ;(e.view.dom.querySelector('pre[data-raw]') as HTMLElement).click()
    expect(e.view.dom.querySelector('textarea')).toBeNull()
  })
})
