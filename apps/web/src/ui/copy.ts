/**
 * Copies text, reporting whether it worked. Call it straight from a click: the fallback needs the
 * click's user activation.
 *
 * navigator.clipboard only exists in secure contexts (https, localhost), and notesd is often reached
 * over plain http on a home network, so it falls back to execCommand('copy'), which works there.
 */
export async function copyText(text: string): Promise<boolean> {
  // Not awaited before the fallback when there's no API: Safari only allows it in the click's own task.
  if (navigator.clipboard === undefined) return legacyCopy(text)
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return legacyCopy(text)
  }
}

function legacyCopy(text: string): boolean {
  const focused = document.activeElement instanceof HTMLElement ? document.activeElement : null
  const field = document.createElement('textarea')
  field.value = text
  field.readOnly = true
  field.setAttribute('aria-hidden', 'true')
  Object.assign(field.style, { position: 'fixed', top: '0', left: '0', opacity: '0', pointerEvents: 'none' })
  // A modal <dialog> makes everything outside it inert, and an inert field can't be selected.
  ;(focused?.closest('dialog') ?? document.body).append(field)
  field.focus({ preventScroll: true })
  field.select()
  let copied = false
  try {
    copied = document.execCommand('copy')
  } catch {
    copied = false
  }
  field.remove()
  focused?.focus({ preventScroll: true })
  return copied
}
