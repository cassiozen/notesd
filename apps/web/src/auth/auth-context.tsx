import { createContext, use, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { auth } from '../api/auth'
import { ApiError, onUnauthenticated, setCsrfToken } from '../api/client'
import type { Person, SessionInfo } from '../api/types'
import { forgetTouchId } from '../locks/touch-id'
import { forgetAiSettings } from '../notes/ai-settings'
import { forgetEmailSettings } from '../notes/email-settings'
import { forgetSharing } from '../notes/sharing'
import { forgetSpaces } from '../notes/spaces'
import { closeNotes, forgetNotesHere, keepIfInstalledApp, openNotes } from '../offline/device'
import { deviceUser, forgetUserId, rememberSession, rememberUserId, userIdOf } from './session-hint'

/** The account's name and photo (SessionInfo's), once the server has said. */
export type Profile = { userId: number; displayName: string; avatar: string }

export type AuthState =
  // retrying: after 'unreachable', where the shell skeleton would just flash between two error screens.
  | { status: 'loading'; retrying?: boolean }
  | { status: 'unreachable' }
  | { status: 'setup' }
  | { status: 'signed-out' }
  // The session ended while the app was in use. The shell stays mounted
  // under a sign-in dialog so unsaved edits survive and retry afterwards.
  | { status: 'expired'; username: string; profile?: Profile }
  // firstRun: just finished onboarding, for the welcome empty state.
  // offline: opened from this device's copy, the server not yet reached.
  // isAdmin, profile: unknown (undefined) until the server answers.
  // userId: the account's (§7.9), unknown from a server before sharing, or offline before any session here.
  | {
      status: 'signed-in'
      username: string
      userId?: number
      isAdmin?: boolean
      profile?: Profile
      firstRun?: boolean
      offline?: boolean
    }

type AuthContextValue = {
  state: AuthState
  signedIn: (info: SessionInfo, opts?: { firstRun?: boolean }) => void
  /** The account's name or photo changed here: the server's answer. */
  profileChanged: (account: Person) => void
  signOut: () => Promise<void>
  retry: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

/** Opened offline: how often to try the server again, besides on the browser's online event. */
export const reconnectTiming = { everyMs: 30_000 }

// What the server told one account (whether it's an admin, for one) mustn't outlive its session.
function forgetAccountSettings() {
  forgetAiSettings()
  forgetEmailSettings()
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading' })

  useEffect(() => {
    void boot().then(setState)
  }, [])

  useEffect(() => {
    if (state.status === 'signed-in') rememberSession(true)
    else if (state.status === 'signed-out' || state.status === 'setup' || state.status === 'expired') rememberSession(false)
  }, [state.status])

  useEffect(
    () =>
      onUnauthenticated(() => {
        setCsrfToken(null)
        setState((s) => (s.status === 'signed-in' ? { status: 'expired', username: s.username, profile: s.profile } : s))
      }),
    [],
  )

  // Opened offline: once the server answers, the session it has decides.
  const offlineAs = state.status === 'signed-in' && state.offline ? state.username : null
  useEffect(() => {
    if (!offlineAs) return
    let busy = false
    let live = true
    const reconnect = async () => {
      if (busy) return
      busy = true
      const next = await fromServer()
      busy = false
      if (!live || next.status === 'unreachable') return
      // Signed out meanwhile, or someone else's session: sign in again, the shell and its edits kept.
      setState(next.status === 'session' && next.info.username === offlineAs ? adopt(next.info) : { status: 'expired', username: offlineAs })
    }
    const timer = setInterval(() => void reconnect(), reconnectTiming.everyMs)
    const online = () => void reconnect()
    window.addEventListener('online', online)
    return () => {
      live = false
      clearInterval(timer)
      window.removeEventListener('online', online)
    }
  }, [offlineAs])

  const signedIn = useCallback((info: SessionInfo, opts: { firstRun?: boolean } = {}) => {
    setCsrfToken(info.csrfToken)
    rememberUserId(info.username, info.userId)
    forgetAccountSettings()
    openNotes(info.username)
    setState({ status: 'signed-in', ...accountOf(info), firstRun: opts.firstRun ?? false })
  }, [])

  const profileChanged = useCallback((account: Person) => {
    setState((s) =>
      s.status === 'signed-in' && s.username === account.username
        ? { ...s, profile: { userId: account.id, displayName: account.displayName, avatar: account.avatar } }
        : s,
    )
  }, [])

  const account = useRef<string | null>(null)
  useEffect(() => {
    account.current = 'username' in state ? state.username : null
  })

  const signOut = useCallback(async () => {
    try {
      await auth.logout()
    } finally {
      setCsrfToken(null)
      // Signed out, nothing of the account stays in this browser.
      void forgetNotesHere().catch(() => undefined)
      if (account.current) forgetTouchId(account.current)
      forgetUserId()
      // Already on the server's notes, the source doesn't change: the next account would get these.
      forgetSpaces()
      forgetSharing()
      forgetAccountSettings()
      setState({ status: 'signed-out' })
    }
  }, [])

  const retry = useCallback(() => {
    setState({ status: 'loading', retrying: true })
    void boot().then(setState)
  }, [])

  const value = useMemo(
    () => ({ state, signedIn, profileChanged, signOut, retry }),
    [state, signedIn, profileChanged, signOut, retry],
  )
  return <AuthContext value={value}>{children}</AuthContext>
}

async function boot(): Promise<AuthState> {
  const answer = await fromServer()
  if (answer.status === 'session') {
    await keepIfInstalledApp(answer.info.username)
    return adopt(answer.info)
  }
  return answer.status === 'unreachable' ? offlineBoot() : answer
}

/** The server's session, not yet used: whose it is decides that. */
async function fromServer(): Promise<{ status: 'setup' | 'signed-out' | 'unreachable' } | { status: 'session'; info: SessionInfo }> {
  // In parallel: boot is on the critical path of every app open.
  const session = auth.session()
  session.catch(() => {}) // unawaited when setup is required
  try {
    const { setupRequired } = await auth.status()
    if (setupRequired) return { status: 'setup' }
    return { status: 'session', info: await session }
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) return { status: 'signed-out' }
    return { status: 'unreachable' }
  }
}

/** The server can't be reached: this device's copy, if it keeps one that has synced. */
function adopt(info: SessionInfo): AuthState {
  setCsrfToken(info.csrfToken)
  rememberUserId(info.username, info.userId)
  openNotes(info.username)
  return { status: 'signed-in', ...accountOf(info) }
}

function accountOf(info: SessionInfo) {
  return {
    username: info.username,
    isAdmin: info.isAdmin,
    profile: profileOf(info),
    ...(info.userId !== undefined && { userId: info.userId }),
  }
}

function profileOf(info: SessionInfo): Profile {
  return { userId: info.userId ?? 0, displayName: info.displayName, avatar: info.avatar }
}

async function offlineBoot(): Promise<AuthState> {
  const user = deviceUser()
  if (!user) return { status: 'unreachable' }
  try {
    const engine = openNotes(user)
    // Never synced (or cleared by the browser): an empty list would look like every note gone.
    const userId = userIdOf(user)
    if (engine && (await engine.call('lastSyncAt')) !== null) {
      return { status: 'signed-in', username: user, offline: true, ...(userId !== undefined && { userId }) }
    }
  } catch {
    // The engine didn't start.
  }
  closeNotes()
  return { status: 'unreachable' }
}

/** Who's signed in, as the apps show them: their chosen name, or else their username. */
export function useAccount(): { username: string; name: string; profile?: Profile } {
  const { state } = useAuth()
  const username = 'username' in state ? state.username : ''
  const profile = 'profile' in state ? state.profile : undefined
  return { username, name: profile?.displayName || username, profile }
}

export function useAuth(): AuthContextValue {
  const ctx = use(AuthContext)
  if (!ctx) throw new Error('useAuth outside AuthProvider')
  return ctx
}
