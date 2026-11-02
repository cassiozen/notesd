import { spawn, type ChildProcess } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { createServer } from 'node:http'
import type { AddressInfo } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { inject, onTestFinished } from 'vitest'

export type TestServer = {
  url: string
  /** Browser requests fail as if the network were down. */
  setOffline: (offline: boolean) => void
  /** Browser requests claim this Origin, e.g. a misconfigured proxy's. */
  setOrigin: (origin: string) => void
  /** The next matching browser request reaches the server, but its response is lost. */
  loseNextResponse: (match: (method: string, path: string) => boolean) => void
  /** The JSON bodies the browser sent, oldest first. */
  sent: (method: string, path: string) => unknown[]
  /** How many of the browser's requests to method and path have been answered. */
  answered: (method: string, path: string) => number
  /** Calls the API as a separate client (its own cookie jar). */
  api: <T = unknown>(method: string, path: string, body?: unknown) => Promise<{ status: number; body: T }>
  /** Like api, for endpoints that take or return bytes (images). */
  bytes: (
    method: string,
    path: string,
    upload?: { type: string; data: Uint8Array<ArrayBuffer> },
  ) => Promise<{ status: number; type: string; data: Uint8Array }>
  stop: () => Promise<void>
}

const nodeFetch = globalThis.fetch

/**
 * Starts a real notesd on a free port with an empty data dir and points
 * the app's fetch at it, with a cookie jar standing in for the browser's.
 */
export async function startServer({ resendUrl }: { resendUrl?: string } = {}): Promise<TestServer> {
  const dataDir = mkdtempSync(join(tmpdir(), 'notes-data-'))
  const args = ['--data-dir', dataDir, '--listen', '127.0.0.1:0', ...(resendUrl ? ['--resend-url', resendUrl] : [])]
  // An exported NOTESD_OWNER_* would create the owner at startup, before the test can.
  const env = { ...process.env, NOTESD_OWNER_USERNAME: '', NOTESD_OWNER_PASSWORD: '' }
  const proc = spawn(inject('serverBin'), args, { env })
  const url = await readStartup(proc)

  const browser = cookieFetch(url)
  globalThis.fetch = browser

  const other = cookieFetch(url)
  const server: TestServer = {
    url,
    setOffline: (offline) => {
      browser.offline = offline
    },
    setOrigin: (origin) => {
      browser.origin = origin
    },
    loseNextResponse: (match) => {
      browser.lose = match
    },
    sent: (method, path) =>
      browser.log.filter((r) => r.method === method && r.path === `/api/v1${path}`).map((r) => r.body),
    answered: (method, path) =>
      browser.answered.filter((r) => r.method === method && r.path === `/api/v1${path}`).length,
    async api(method, path, body) {
      const res = await other(`/api/v1${path}`, {
        method,
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': other.csrf ?? '' },
        ...(body !== undefined && { body: JSON.stringify(body) }),
      })
      const text = await res.text()
      const parsed: unknown = text ? JSON.parse(text) : undefined
      if (parsed && typeof parsed === 'object' && 'csrfToken' in parsed) {
        other.csrf = String(parsed.csrfToken)
      }
      return { status: res.status, body: parsed as never }
    },
    async bytes(method, path, upload) {
      if (!other.csrf) await server.api('GET', '/auth/session')
      const res = await other(`/api/v1${path}`, {
        method,
        headers: { 'X-CSRF-Token': other.csrf ?? '', ...(upload && { 'Content-Type': upload.type }) },
        ...(upload && { body: upload.data }),
      })
      return { status: res.status, type: res.headers.get('Content-Type') ?? '', data: new Uint8Array(await res.arrayBuffer()) }
    },
    async stop() {
      if (proc.exitCode === null) {
        const exited = new Promise((r) => proc.once('exit', r))
        proc.kill()
        await exited
      }
      rmSync(dataDir, { recursive: true, force: true })
    },
  }
  onTestFinished(async () => {
    globalThis.fetch = nodeFetch
    await server.stop()
  })
  return server
}

export type FakeResend = {
  url: string
  /** Bodies of accepted sends. */
  sent: { from: string; to: string[]; subject: string; html: string; text: string }[]
  /** When set, sends fail with this status and message, as Resend's would. */
  refuse: { status: number; message: string } | null
}

/** Stands in for Resend's API: POST /emails. */
export async function startFakeResend(): Promise<FakeResend> {
  const fake: FakeResend = { url: '', sent: [], refuse: null }
  const srv = createServer((req, res) => {
    let raw = ''
    req.on('data', (c: Buffer) => (raw += c.toString()))
    req.on('end', () => {
      res.setHeader('Content-Type', 'application/json')
      if (fake.refuse) {
        res.statusCode = fake.refuse.status
        res.end(JSON.stringify({ statusCode: fake.refuse.status, name: 'validation_error', message: fake.refuse.message }))
        return
      }
      fake.sent.push(JSON.parse(raw) as FakeResend['sent'][number])
      res.end(JSON.stringify({ id: `email-${fake.sent.length}` }))
    })
  })
  await new Promise<void>((r) => srv.listen(0, '127.0.0.1', r))
  fake.url = `http://127.0.0.1:${(srv.address() as AddressInfo).port}`
  onTestFinished(() => new Promise<void>((r) => srv.close(() => r())))
  return fake
}

export type FakeLLM = {
  /** The base URL to save as the API address. */
  url: string
  /** Bodies of chat completion requests, in order. */
  requests: { model: string; messages: unknown[]; tools?: unknown[] }[]
  /** When true, the model answers in text instead of calling a tool. */
  textOnly: boolean
}

/** Stands in for an OpenAI-compatible API: POST /v1/chat/completions, answering with a call to the first tool. */
export async function startFakeLLM(): Promise<FakeLLM> {
  const fake: FakeLLM = { url: '', requests: [], textOnly: false }
  const srv = createServer((req, res) => {
    let raw = ''
    req.on('data', (c: Buffer) => (raw += c.toString()))
    req.on('end', () => {
      res.setHeader('Content-Type', 'application/json')
      if (req.url !== '/v1/chat/completions') {
        res.statusCode = 404
        res.end(JSON.stringify({ error: { message: 'not found' } }))
        return
      }
      const body = JSON.parse(raw) as FakeLLM['requests'][number] & { tools?: { function: { name: string } }[] }
      fake.requests.push(body)
      const tool = body.tools?.[0]
      const message =
        fake.textOnly || !tool
          ? { role: 'assistant', content: 'pong' }
          : {
              role: 'assistant',
              tool_calls: [{ id: 'call_1', type: 'function', function: { name: tool.function.name, arguments: '{"ok":true}' } }],
            }
      res.end(JSON.stringify({ model: 'fake-model-1', choices: [{ message, finish_reason: 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 2 } }))
    })
  })
  await new Promise<void>((r) => srv.listen(0, '127.0.0.1', r))
  fake.url = `http://127.0.0.1:${(srv.address() as AddressInfo).port}/v1`
  onTestFinished(() => new Promise<void>((r) => srv.close(() => r())))
  return fake
}

/** Creates the owner account through the API, as another device would. */
export async function createOwner(server: TestServer, username = 'me', password = 'correct horse') {
  const res = await server.api('POST', '/auth/setup', { username, password })
  if (res.status !== 201) throw new Error(`setup failed: ${res.status}`)
  return { username, password }
}

function readStartup(proc: ChildProcess): Promise<string> {
  return new Promise((resolve, reject) => {
    let log = ''
    const onData = (chunk: Buffer) => {
      log += chunk.toString()
      const listen = /listen=(\S+)/.exec(log)?.[1]
      if (listen) {
        proc.stdout?.off('data', onData)
        proc.stderr?.off('data', onData)
        resolve(`http://${listen}`)
      }
    }
    proc.stdout?.on('data', onData)
    proc.stderr?.on('data', onData)
    proc.once('exit', (code) => reject(new Error(`notesd exited (${code}):\n${log}`)))
  })
}

type CookieFetch = typeof fetch & {
  csrf?: string
  offline?: boolean
  origin?: string
  lose?: ((method: string, path: string) => boolean) | undefined
  log: { method: string; path: string; body: unknown }[]
  answered: { method: string; path: string }[]
}

function cookieFetch(base: string): CookieFetch {
  const jar = new Map<string, string>()
  const fn = (async (input, init = {}) => {
    const url = new URL(String(input), base)
    if (fn.offline) throw new TypeError('Failed to fetch')
    if (typeof init.body === 'string') {
      fn.log.push({ method: init.method ?? 'GET', path: url.pathname, body: JSON.parse(init.body) as unknown })
    }
    const headers = new Headers(init.headers)
    // Same-origin browser requests carry Origin on mutations.
    headers.set('Origin', fn.origin ?? base)
    if (jar.size > 0) headers.set('Cookie', [...jar].map(([k, v]) => `${k}=${v}`).join('; '))

    // jsdom's AbortSignal isn't Node's; bridge it so aborts still reach fetch.
    const controller = new AbortController()
    init.signal?.addEventListener('abort', () => controller.abort(init.signal?.reason))
    if (init.signal?.aborted) controller.abort(init.signal.reason)

    const res = await nodeFetch(url, { ...init, headers, signal: controller.signal })
    if (fn.lose?.(init.method ?? 'GET', url.pathname)) {
      fn.lose = undefined
      throw new TypeError('Failed to fetch')
    }
    for (const cookie of res.headers.getSetCookie()) {
      const [pair = '', ...attrs] = cookie.split(';')
      const eq = pair.indexOf('=')
      const name = pair.slice(0, eq).trim()
      const expired = attrs.some((a) => /^\s*max-age=(0|-)/i.test(a))
      if (expired) jar.delete(name)
      else jar.set(name, pair.slice(eq + 1).trim())
    }
    fn.answered.push({ method: init.method ?? 'GET', path: url.pathname })
    return res
  }) as CookieFetch
  fn.log = []
  fn.answered = []
  return fn
}
