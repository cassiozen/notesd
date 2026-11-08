import { useSyncExternalStore } from 'react'

// The macOS app (apps/desktop) marks the page before any of it runs; a browser never does.
export function inDesktopApp(): boolean {
  return document.documentElement.dataset.desktop === 'macos'
}

/**
 * In the macOS app, where the window can be moved from, with no title bar of its own: 'self' is the element's own
 * background, 'deep' anything inside it but controls. Double-clicking one zooms the window.
 */
export function dragRegion(kind: 'self' | 'deep'): { 'data-tauri-drag-region'?: string } {
  return inDesktopApp() ? { 'data-tauri-drag-region': kind === 'deep' ? 'deep' : 'true' } : {}
}

function subscribeFullscreen(onChange: () => void): () => void {
  if (!inDesktopApp()) return () => {}
  const observer = new MutationObserver(onChange)
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-fullscreen'] })
  return () => observer.disconnect()
}

/**
 * Whether the macOS app's window buttons are showing (hidden in full screen): the top row beside them is where
 * the sidebar's tools go, as in native apps; anywhere else they sit at the bottom.
 */
export function useWindowButtons(): boolean {
  return useSyncExternalStore(
    subscribeFullscreen,
    () => inDesktopApp() && !document.documentElement.hasAttribute('data-fullscreen'),
    () => false,
  )
}
