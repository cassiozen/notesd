// Before first paint, well ahead of the bundle: the picked theme and mode, and index.html's shell frame
// for signed-in visits. A file because the CSP blocks inline scripts. Keys from theme.ts, session-hint.ts, shell.tsx.
try {
  const html = document.documentElement
  const stored = localStorage.getItem('notes.theme')
  // Before themes, notes.theme held the mode.
  const legacy = stored === 'light' || stored === 'dark' ? stored : null
  const mode = localStorage.getItem('notes.mode') || legacy
  const dark = mode ? mode === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches
  html.dataset.mode = dark ? 'dark' : 'light'
  if (stored && !legacy) {
    const attrs = JSON.parse(stored)
    for (const name in attrs) if (name.startsWith('data-')) html.setAttribute(name, attrs[name])
  }
  if (localStorage.getItem('notes.signed-in') === 'true') html.dataset.session = ''
  if (localStorage.getItem('notes.sidebar.hidden') === 'true') html.dataset.sidebarHidden = ''
} catch {
  // Storage blocked: index.html's default theme, light, and no frame; the app draws itself once loaded.
}
