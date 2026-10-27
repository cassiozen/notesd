import { AuthProvider, useAuth } from './auth/auth-context'
import { AuthLayout } from './auth/auth-layout'
import { Login, LoginForm } from './auth/login'
import { Onboarding } from './auth/onboarding'
import { hadSession } from './auth/session-hint'
import { Shell } from './notes/shell'
import { useNotesSourceVersion } from './notes/source'
import { ShellSkeleton } from './notes/shell-skeleton'
import { Button } from './ui/button'
import { Dialog } from './ui/dialog'
import { Kicker } from './ui/kicker'
import styles from './auth/auth.module.css'

export function App() {
  return (
    <AuthProvider>
      <AuthGate />
    </AuthProvider>
  )
}

function AuthGate() {
  const { state, retry } = useAuth()
  const notesVersion = useNotesSourceVersion()
  switch (state.status) {
    case 'loading':
      // Signed out last time: blank beats a shell that turns into the login screen.
      return !state.retrying && hadSession() ? <ShellSkeleton /> : null
    case 'unreachable':
      return (
        <AuthLayout>
          <div className={styles.stack} role="alert">
            <Kicker>Connection</Kicker>
            <h1>Can’t reach the server</h1>
            <p className={styles.lede}>Check that the notes server is running, then try again.</p>
            <Button variant="solid" onClick={retry}>
              Try again
            </Button>
          </div>
        </AuthLayout>
      )
    case 'setup':
      return <Onboarding />
    case 'signed-out':
      return <Login />
    case 'signed-in':
    case 'expired':
      return (
        <>
          <Shell key={notesVersion} username={state.username} />
          {state.status === 'expired' && (
            <Dialog labelledBy="session-ended-title">
              <LoginForm
                heading="Sign in again"
                headingId="session-ended-title"
                username={state.username}
                notice="Your session ended. Sign in again — your changes are kept and will be saved."
              />
            </Dialog>
          )}
        </>
      )
  }
}
