// Before first paint, well ahead of the bundle: the picked theme and mode, and index.html's shell frame
// for signed-in visits. A file because the CSP blocks inline scripts. Keys from theme.ts, session-hint.ts, shell.tsx.
try {
  const html = document.documentElement
  const stored = localStorage.getItem('notes.theme')
  // Before themes, notes.theme held the mode.
  const legacy = stored === 'light' || stored === 'dark' ? stored : null
  const saved = localStorage.getItem('notes.mode') || legacy
  const picked = saved === 'light' || saved === 'dark' ? saved : null
  const system = matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  html.dataset.mode = picked || system
  let bar = null
  if (stored && !legacy) {
    try {
      const theme = JSON.parse(stored)
      for (const name in theme) if (name.startsWith('data-')) html.setAttribute(name, theme[name])
      bar = theme.bar || null
    } catch {
      // Unreadable: the default theme until the app stores the picked one again.
    }
  }
  // index.html's metas are the default theme's, one per system mode; a picked mode wins over the system's.
  const metas = [...document.querySelectorAll('meta[name="theme-color"]')]
  const colors = bar || Object.fromEntries(metas.map((m) => [m.media.includes('dark') ? 'dark' : 'light', m.content]))
  for (const meta of metas) meta.content = colors[picked || (meta.media.includes('dark') ? 'dark' : 'light')]
  if (localStorage.getItem('notes.signed-in') === 'true') html.dataset.session = ''
  if (localStorage.getItem('notes.sidebar.hidden') === 'true') html.dataset.sidebarHidden = ''
} catch {
  // Storage blocked: index.html's default theme, light, and no frame; the app draws itself once loaded.
}
