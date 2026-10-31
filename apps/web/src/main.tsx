import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './app'
import { applyFont, readFont } from './font'
import { captureInstallPrompt } from './install'
import { applyTheme, readTheme } from './theme'
import './styles/fonts.css'
import './styles/tokens.css'
import './styles/base.css'
import './styles/boot.css'

// Before the first render so a picked theme doesn't flash the OS one.
applyTheme(readTheme())
applyFont(readFont())
captureInstallPrompt()

const root = document.getElementById('root')
if (!root) throw new Error('#root missing from index.html')

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
)

// Dev skipped: a cached shell would fight Vite's HMR.
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  // Fails outside a secure context (plain-http LAN address); the app just loads from the network.
  navigator.serviceWorker.register('/sw.js').then(
    (reg) => {
      // An installed app resumed from the background doesn't navigate, so the browser never checks for a new build itself.
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') reg.update().catch(() => {})
      })
    },
    () => {},
  )
}
