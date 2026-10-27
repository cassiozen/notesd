import type { ErrorBody, Note } from './types'

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly field: string | undefined
  readonly current: Note | undefined
  /** Seconds, from Retry-After on 429. */
  readonly retryAfter: number | undefined

  constructor(status: number, body: ErrorBody, retryAfter?: number) {
    super(body.message ?? body.error)
    this.name = 'ApiError'
    this.status = status
    this.code = body.error
    this.field = body.field
    this.current = body.current
    this.retryAfter = retryAfter
  }
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
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  path: string,
  body?: unknown,
  options: Options = {},
): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await send(method, path, headers, body === undefined ? undefined : JSON.stringify(body), options)
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

/** Sends bytes as the body, for endpoints that take a file rather than JSON (images). */
export async function upload<T>(path: string, body: Uint8Array<ArrayBuffer>, contentType: string, options: Options = {}): Promise<T> {
  const res = await send('PUT', path, { Accept: 'application/json', 'Content-Type': contentType }, body, options)
  return (await res.json()) as T
}

/** Fetches a file the server stores as bytes (images), with its type. */
export async function download(path: string, options: Options = {}): Promise<Blob> {
  const res = await send('GET', path, {}, undefined, options)
  return res.blob()
}

/** One request: CSRF, connection failures and error bodies handled; resolves only with an OK response. */
async function send(
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  path: string,
  headers: Record<string, string>,
  body: BodyInit | undefined,
  options: Options,
): Promise<Response> {
  if (method !== 'GET' && csrfToken) headers['X-CSRF-Token'] = csrfToken

  let res: Response
  try {
    res = await fetch(`/api/v1${path}`, {
      method,
      headers,
      ...(body !== undefined && { body }),
      credentials: 'same-origin',
      signal: options.signal,
      keepalive: options.keepalive,
    })
  } catch (err) {
    if (isAbort(err)) throw err
    throw new NetworkError(err)
  }
  if (res.ok) return res

  const error = new ApiError(res.status, await errorBody(res), parseRetryAfter(res))
  // invalid_credentials is also a 401 but means "wrong password", not "signed out".
  if (error.code === 'unauthenticated') sessionGone()
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
  return { error: res.status >= 500 ? 'server_error' : 'http_error', message: res.statusText }
}

function parseRetryAfter(res: Response): number | undefined {
  const seconds = Number(res.headers.get('Retry-After'))
  return Number.isFinite(seconds) && seconds > 0 ? seconds : undefined
}
