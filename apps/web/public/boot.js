// Before first paint, well ahead of the bundle: index.html's shell frame in the right theme, signed-in visits only.
// A file because the CSP blocks inline scripts. Keys from theme.ts, session-hint.ts, shell.tsx, space-theme.ts.
try {
  const html = document.documentElement
  const theme = localStorage.getItem('notes.theme')
  if (theme === 'light' || theme === 'dark') html.dataset.theme = theme
  if (localStorage.getItem('notes.signed-in') === 'true') {
    html.dataset.session = ''
    // The app opens on the default space: its colours, not the app icon's, from the first frame.
    const space = localStorage.getItem('notes.space-theme')
    if (space) html.dataset.space = space
  }
  if (localStorage.getItem('notes.sidebar.hidden') === 'true') html.dataset.sidebarHidden = ''
} catch {
  // Storage blocked: no frame, the app draws itself once loaded.
}
