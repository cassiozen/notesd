import { afterEach, describe, expect, it, vi } from 'vitest'
import { copyText } from './copy'

function setClipboard(clipboard: Pick<Clipboard, 'writeText'> | undefined) {
  Object.defineProperty(navigator, 'clipboard', { value: clipboard, configurable: true })
}

/** What execCommand('copy') would have copied: the selected text, and where the field it was in sat. */
function stubExecCommand(result: boolean | Error) {
  const seen: { text: string; inDialog: boolean }[] = []
  Object.defineProperty(document, 'execCommand', {
    configurable: true,
    value: vi.fn((command: string) => {
      const field = document.activeElement
      if (command === 'copy' && field instanceof HTMLTextAreaElement) {
        seen.push({
          text: field.value.slice(field.selectionStart, field.selectionEnd),
          inDialog: field.closest('dialog') !== null,
        })
      }
      if (result instanceof Error) throw result
      return result
    }),
  })
  return seen
}

afterEach(() => {
  setClipboard(undefined)
  Reflect.deleteProperty(document, 'execCommand')
  document.body.replaceChildren()
})

describe('copyText', () => {
  it('uses the Clipboard API where there is one', async () => {
    const writeText = vi.fn(() => Promise.resolve())
    setClipboard({ writeText })
    const seen = stubExecCommand(true)
    expect(await copyText('https://notes.example.com/setup#t')).toBe(true)
    expect(writeText).toHaveBeenCalledWith('https://notes.example.com/setup#t')
    expect(seen).toEqual([])
  })

  it('falls back to execCommand on plain http, where there is none', async () => {
    setClipboard(undefined)
    const seen = stubExecCommand(true)
    expect(await copyText('http://192.168.1.20:8080/setup#t')).toBe(true)
    expect(seen).toEqual([{ text: 'http://192.168.1.20:8080/setup#t', inDialog: false }])
    expect(document.querySelector('textarea')).toBeNull()
  })

  it('falls back when the Clipboard API refuses', async () => {
    setClipboard({ writeText: () => Promise.reject(new DOMException('denied', 'NotAllowedError')) })
    const seen = stubExecCommand(true)
    expect(await copyText('x')).toBe(true)
    expect(seen.map((s) => s.text)).toEqual(['x'])
  })

  it('copies from inside the open dialog, and gives focus back', async () => {
    setClipboard(undefined)
    const seen = stubExecCommand(true)
    const dialog = document.createElement('dialog')
    dialog.open = true
    const button = document.createElement('button')
    dialog.append(button)
    document.body.append(dialog)
    button.focus()
    expect(await copyText('link')).toBe(true)
    expect(seen).toEqual([{ text: 'link', inDialog: true }])
    expect(document.activeElement).toBe(button)
  })

  it('reports failure when neither works', async () => {
    setClipboard(undefined)
    stubExecCommand(false)
    expect(await copyText('x')).toBe(false)
    stubExecCommand(new Error('not supported'))
    expect(await copyText('x')).toBe(false)
  })
})
