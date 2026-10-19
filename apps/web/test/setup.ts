import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'
import { resetEmailSettings } from '../src/notes/email-settings'
import { resetSpaces } from '../src/notes/spaces'
import { kdfCost } from '../src/locks/crypto'
import { lockTiming } from '../src/locks/session'
import { timing } from '../src/notes/saver'
import { PHONE, WIDE } from '../src/ui/media'

// Same behaviour, shorter waits.
timing.debounceMs = 40
timing.retryBaseMs = 60
timing.retryMaxMs = 200
timing.undoDeleteMs = 300
timing.quietMs = 100
// The server's minimum; 600k iterations per unlock would dominate the run.
kdfCost.password = 10_000

// jsdom has no media queries. Tests get the wide layout (tags sidebar, list,
// note) unless they ask for another (helpers.phoneLayout, mediumLayout);
// nothing re-evaluates mid-render.
declare global {
  var layout: 'phone' | 'medium' | 'wide'
}
globalThis.layout = 'wide'
window.matchMedia ??= (query: string) =>
  ({
    matches: (query === PHONE && globalThis.layout === 'phone') || (query === WIDE && globalThis.layout === 'wide'),
    media: query,
    onchange: null,
    addEventListener() {},
    removeEventListener() {},
    addListener() {},
    removeListener() {},
    dispatchEvent: () => false,
  }) as MediaQueryList

// jsdom has no layout, so nothing ever scrolls into view; tests use the
// "Load more" button the sentinel renders.
globalThis.IntersectionObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() {
    return []
  }
  root = null
  rootMargin = ''
  thresholds = []
} as unknown as typeof IntersectionObserver

// jsdom has no modal dialog support.
HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) {
  this.open = true
}
HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) {
  this.open = false
}

// ProseMirror measures the DOM to scroll the selection into view; jsdom has
// no layout, so these report empty boxes.
const emptyRect = () => DOMRect.fromRect({ x: 0, y: 0, width: 0, height: 0 })
Range.prototype.getBoundingClientRect ??= emptyRect
Range.prototype.getClientRects ??= () => ({ length: 0, item: () => null, [Symbol.iterator]: [][Symbol.iterator] }) as unknown as DOMRectList
document.elementFromPoint ??= () => null

// Nor does it turn Escape into a cancel event on the topmost modal dialog.
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return
  const open = [...document.querySelectorAll('dialog[open]')]
  open.at(-1)?.dispatchEvent(new Event('cancel', { cancelable: true }))
})

afterEach(() => {
  lockTiming.idleMs = 5 * 60_000
  lockTiming.hiddenMs = 5 * 60_000
})

afterEach(() => {
  cleanup()
  resetEmailSettings()
  resetSpaces()
  globalThis.layout = 'wide'
  localStorage.clear()
  window.history.replaceState(null, '', '/')
})
