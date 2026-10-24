import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { body, createNote, getNote, noteTitle, saved, selectInBody, signedIn, typeAtEnd, typeInTitle } from './helpers'

/** Signed in with one note, which opens by itself. */
async function openNote(title: string, content: string) {
  let id = ''
  const ctx = await signedIn({
    seed: async (s) => {
      id = await createNote(s, title, content)
    },
  })
  await waitFor(() => expect(noteTitle()).toBe(title))
  return { ...ctx, id }
}

const tool = (name: string) => screen.getByRole('button', { name })

describe('formatting', () => {
  it('renders stored Markdown as formatted text', async () => {
    await openNote('Rich', '# Heading\n\n**bold** and *italic*\n\n- [x] Done\n- [ ] Todo\n')
    const el = body()
    expect(el.querySelector('h1:not([data-title])')?.textContent).toBe('Heading')
    expect(el.querySelector('strong')?.textContent).toBe('bold')
    expect(el.querySelector('em')?.textContent).toBe('italic')
    const boxes = within(el).getAllByRole('checkbox')
    expect(boxes.map((b) => (b as HTMLInputElement).checked)).toEqual([true, false])
  })

  it('applies toolbar formatting and saves it as Markdown', async () => {
    const { server, user, id } = await openNote('Format', 'Milk and eggs\n')

    selectInBody('Milk')
    await user.click(tool('Bold'))
    expect(tool('Bold').getAttribute('aria-pressed')).toBe('true')
    await saved()
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('**Milk** and eggs\n'))

    selectInBody('eggs')
    await user.click(tool('Text styles'))
    await user.click(tool('Heading'))
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('## **Milk** and eggs\n'))
    expect(tool('Heading').getAttribute('aria-pressed')).toBe('true')

    await user.click(tool('Body'))
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('**Milk** and eggs\n'))
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('group', { name: 'Text styles' })).toBeNull()
  })

  it('makes a checklist with Shift-Mod-L', async () => {
    const { server, user, id } = await openNote('Keys', 'Buy milk\n')
    selectInBody('Buy')
    await user.keyboard('{Control>}{Shift>}l{/Shift}{/Control}')
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('- [ ] Buy milk\n'))
  })

  it('turns lines into a checklist and saves ticks', async () => {
    const { server, user, id } = await openNote('Tasks', 'Buy milk\n')

    selectInBody('Buy')
    await user.click(tool('Checklist'))
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('- [ ] Buy milk\n'))

    await user.click(within(body()).getByRole('checkbox'))
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('- [x] Buy milk\n'))
  })

  it('indents and outdents list items from the toolbar, shown only in a list', async () => {
    const { server, user, id } = await openNote('Lists', '- One\n- Two\n\nAfter\n')
    selectInBody('After')
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Outdent' })).toBeNull())

    // The first item has nothing to nest under.
    selectInBody('One')
    await screen.findByRole('button', { name: 'Outdent' })
    expect(screen.queryByRole('button', { name: 'Indent' })).toBeNull()

    selectInBody('Two')
    await user.click(await screen.findByRole('button', { name: 'Indent' }))
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('- One\n  - Two\n\nAfter\n'))

    await user.click(tool('Outdent'))
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('- One\n- Two\n\nAfter\n'))
  })

  it('adds a link to the selection', async () => {
    const { server, user, id } = await openNote('Links', 'See the docs\n')

    selectInBody('docs')
    await user.click(tool('Link'))
    await user.type(screen.getByLabelText('Link address'), 'example.com/docs{Enter}')

    await waitFor(async () =>
      expect((await getNote(server, id)).content).toBe('See the [docs](https://example.com/docs)\n'),
    )
    expect(screen.queryByLabelText('Link address')).toBeNull()
  })

  it('refuses links that are not web or email addresses', async () => {
    const { server, user, id } = await openNote('Links', 'Click me\n')

    selectInBody('me')
    await user.click(tool('Link'))
    await user.type(screen.getByLabelText('Link address'), 'javascript:alert(1){Enter}')

    expect(screen.getByText('Use a web (https://) or email (mailto:) link.')).toBeTruthy()
    expect((await getNote(server, id)).content).toBe('Click me\n')
  })
})

describe('Markdown the editor cannot show', () => {
  it('keeps code, tables and images exactly as written through an edit', async () => {
    const raw = [
      'Intro',
      '',
      '```go',
      'fmt.Println("*not emphasis*")',
      '```',
      '',
      '| a | b |',
      '| - | - |',
      '| 1 | 2 |',
      '',
      'Run `go test` and see ![chart](chart.png)',
      '',
    ].join('\n')
    const { server, user, id } = await openNote('Raw', raw)
    expect(body().querySelectorAll('pre[data-raw]')).toHaveLength(2)

    await typeAtEnd(user, ' now')
    await saved()
    await waitFor(async () => expect((await getNote(server, id)).content).toBe(raw.replace(/\n$/, ' now\n')))
  })
})

describe('canonical form', () => {
  it('does not resave a note just because it was written in another Markdown style', async () => {
    const { server, user, id } = await openNote('Style', '* one\n* two')
    const before = await getNote(server, id)

    await typeInTitle(user, ' guide')
    await saved()
    const after = await getNote(server, id)
    expect(after.title).toBe('Style guide')
    // The title edit is the only change; no conflict from "*" vs "-".
    expect(after.revision).toBe(before.revision + 1)
    expect(screen.queryByText(/Changed on another device/)).toBeNull()
  })
})

/** Pastes into the editor at the selection, with a clipboard holding these types. */
function paste(data: Record<string, string>) {
  const event = new Event('paste', { bubbles: true, cancelable: true })
  Object.defineProperty(event, 'clipboardData', {
    value: { files: [], getData: (type: string) => data[type] ?? '', types: Object.keys(data) },
  })
  body().dispatchEvent(event)
}

describe('pasting', () => {
  it('reads pasted text as Markdown', async () => {
    const { server, id } = await openNote('Paste', 'Intro\n\nLine\n')
    selectInBody('Line')
    paste({ 'text/plain': '# Heading\n\n- one\n- two\n\nSome **bold** text' })
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('Intro\n\n# Heading\n\n- one\n- two\n\nSome **bold** text\n'))
    expect(body().querySelector('h1:not([data-title])')?.textContent).toBe('Heading')
  })

  it('pastes inline Markdown inside a line', async () => {
    const { server, id } = await openNote('Inline', 'Buy milk\n')
    selectInBody('milk')
    paste({ 'text/plain': '*fresh* milk' })
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('Buy *fresh* milk\n'))
  })

  it('takes the title from Markdown pasted into the title line', async () => {
    const { server, id } = await openNote('Old', 'Body\n')
    selectInBody('Old')
    paste({ 'text/plain': '# Groceries\n\n- [ ] Milk' })
    await waitFor(async () => expect((await getNote(server, id)).title).toBe('Groceries'))
    expect((await getNote(server, id)).content).toBe('- [ ] Milk\n\nBody\n')
  })

  it('reads Markdown copied from a code editor, which comes with unformatted HTML', async () => {
    const { server, id } = await openNote('Code', 'Line\n')
    selectInBody('Line')
    paste({
      'text/plain': '## Plan\n\n1. Write',
      'text/html': '<div style="color: #ccc"><div><span>## Plan</span></div><br><div><span>1. Write</span></div></div>',
    })
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('## Plan\n\n1. Write\n'))
  })

  it('keeps formatted HTML as the browser copied it', async () => {
    const { server, id } = await openNote('Web', 'Line\n')
    selectInBody('Line')
    paste({ 'text/plain': 'Title\n\nText', 'text/html': '<h2>Title</h2><p>Text with <strong>bold</strong></p>' })
    await waitFor(async () => expect((await getNote(server, id)).content).toBe('## Title\n\nText with **bold**\n'))
  })
})
