import type { ErrorBody, Note } from './types'
import { CLIENT_ID, versionState } from './version'

// The browser's IANA time zone, such as Europe/Lisbon. The server keeps the
// first one an account's devices send, for agents' "today".
const TIME_ZONE = (() => {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone
  } catch {
    return ''
  }
})()

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly field: string | undefined
  readonly current: Note | undefined
  /** Seconds, from Retry-After on 429. */
  readonly retryAfter: number | undefined
  /** upgrade_required: the oldest app version the server takes. */
  readonly minVersion: string | undefined
  /** space_not_empty, space_has_locked: how many notes. */
  readonly count: number | undefined

  constructor(status: number, body: ErrorBody, retryAfter?: number) {
    super(body.message ?? body.error)
    this.name = 'ApiError'
    this.status = status
    this.code = body.error
    this.field = body.field
    this.current = body.current
    this.retryAfter = retryAfter
    this.minVersion = body.minVersion
    this.count = body.count
  }
}

/** Code of an answer notesd didn't give: a redirect, or a page without its JSON error (a proxy's, a portal's). */
export const UNEXPECTED = 'unexpected_response'

/** status 0: a redirect the browser hid (fetch's opaqueredirect). */
export function unexpectedMessage(status: number): string {
  const http = status === 0 ? 'a redirect' : `HTTP ${status}`
  return `Your server answered something notesd didn’t expect (${http}). If you sign in to a proxy or Wi-Fi portal first, do that, then sync again.`
}

/** The request never got an HTTP response: server down, network gone. */
export class NetworkError extends Error {
  constructor(cause: unknown) {
    super('No connection to the server', { cause })
    this.name = 'NetworkError'
  }
}

// Memory only: the token must not outlive the page (spec §10).
let csrfToken: string | null = null

export function setCsrfToken(token: string | null) {
  csrfToken = token
}

export function csrfTokenNow(): string | null {
  return csrfToken
}

type Listener = () => void
const unauthenticatedListeners = new Set<Listener>()

/** Called whenever a request fails because the session is gone, or sync finds it gone. */
export function onUnauthenticated(listener: Listener): () => void {
  unauthenticatedListeners.add(listener)
  return () => unauthenticatedListeners.delete(listener)
}

/** The session is gone, found by something other than a request here (sync). */
export function sessionGone() {
  for (const listener of unauthenticatedListeners) listener()
}

type Options = { signal?: AbortSignal; keepalive?: boolean }

export async function request<T>(
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  path: string,
  body?: unknown,
  options: Options = {},
): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await send(method, path, headers, body === undefined ? undefined : JSON.stringify(body), options)
  if (res.status === 204) return undefined as T
  return json<T>(res)
}

/** Sends bytes as the body, for endpoints that take a file rather than JSON (images). */
export async function upload<T>(path: string, body: Uint8Array<ArrayBuffer>, contentType: string, options: Options = {}): Promise<T> {
  const res = await send('PUT', path, { Accept: 'application/json', 'Content-Type': contentType }, body, options)
  return json<T>(res)
}

// A 2xx that isn't JSON came from something in front of notesd, not from it.
async function json<T>(res: Response): Promise<T> {
  try {
    return (await res.json()) as T
  } catch (err) {
    if (isAbort(err)) throw err
    throw new ApiError(res.status, { error: UNEXPECTED, message: unexpectedMessage(res.status) })
  }
}

/** Fetches a file the server stores as bytes (images), with its type. */
export async function download(path: string, options: Options = {}): Promise<Blob> {
  const res = await send('GET', path, {}, undefined, options)
  return res.blob()
}

/** One request: CSRF, connection failures and error bodies handled; resolves only with an OK response. */
async function send(
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  path: string,
  headers: Record<string, string>,
  body: BodyInit | undefined,
  options: Options,
): Promise<Response> {
  if (method !== 'GET' && csrfToken) headers['X-CSRF-Token'] = csrfToken
  headers['X-Notesd-Client'] = CLIENT_ID
  // The account's time zone, until one is known: agents need "today".
  if (TIME_ZONE) headers['X-Notesd-Time-Zone'] = TIME_ZONE

  let res: Response
  try {
    res = await fetch(`/api/v1${path}`, {
      method,
      headers,
      ...(body !== undefined && { body }),
      credentials: 'same-origin',
      // A redirect on the API is a proxy or portal answering, never notesd: following it would
      // turn its sign-in page into a "successful" save.
      redirect: 'manual',
      signal: options.signal,
      keepalive: options.keepalive,
    })
  } catch (err) {
    if (isAbort(err)) throw err
    throw new NetworkError(err)
  }
  versionState.sawServer(res.headers.get('X-Notesd-Server'))
  if (res.type === 'opaqueredirect' || (res.status >= 300 && res.status < 400)) {
    throw new ApiError(res.status, { error: UNEXPECTED, message: unexpectedMessage(res.status) })
  }
  if (res.ok) return res

  const error = new ApiError(res.status, await errorBody(res), parseRetryAfter(res))
  // invalid_credentials is also a 401 but means "wrong password", not "signed out".
  if (error.code === 'unauthenticated') sessionGone()
  if (error.code === 'upgrade_required') versionState.upgradeRequired(error.minVersion ?? null)
  throw error
}

// By name, not instanceof: DOMException isn't always an Error subclass and
// differs across realms.
function isAbort(err: unknown): boolean {
  return typeof err === 'object' && err !== null && 'name' in err && err.name === 'AbortError'
}

async function errorBody(res: Response): Promise<ErrorBody> {
  try {
    const body = (await res.json()) as Partial<ErrorBody>
    if (typeof body.error === 'string') return body as ErrorBody
  } catch {
    // Proxies and crashes can answer with non-JSON bodies.
  }
  // A 5xx is the server (or its proxy) failing, worth retrying; anything else didn't come from notesd.
  if (res.status >= 500) return { error: 'server_error', message: res.statusText }
  return { error: UNEXPECTED, message: unexpectedMessage(res.status) }
}

function parseRetryAfter(res: Response): number | undefined {
  const seconds = Number(res.headers.get('Retry-After'))
  return Number.isFinite(seconds) && seconds > 0 ? seconds : undefined
}
