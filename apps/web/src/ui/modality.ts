const MODIFIERS = new Set(['Shift', 'Control', 'Alt', 'Meta', 'CapsLock', 'Fn'])

/**
 * Marks <html data-keyboard> while the last input was a key, cleared by any pointer.
 * :focus-visible alone isn't enough: browsers (WebKit most) match it on script-moved
 * focus after a click — a menu focusing its first item, showModal(), autoFocus.
 */
export function trackModality(root: HTMLElement = document.documentElement): () => void {
  const key = (e: KeyboardEvent) => {
    if (!MODIFIERS.has(e.key)) root.dataset.keyboard = ''
  }
  const pointer = () => {
    delete root.dataset.keyboard
  }
  // Capture: a handler that stops propagation mustn't hide the input from us.
  document.addEventListener('keydown', key, true)
  document.addEventListener('pointerdown', pointer, true)
  return () => {
    document.removeEventListener('keydown', key, true)
    document.removeEventListener('pointerdown', pointer, true)
    delete root.dataset.keyboard
  }
}
