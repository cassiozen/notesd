import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, NetworkError, UNEXPECTED, request, upload } from './client'
import { APP_VERSION, CLIENT_ID, serverDiffers, versionState } from './version'

// Everything else about the client is covered by the integration tests in
// test/, against the real server. These are failures that server can't produce.

afterEach(() => {
  vi.unstubAllGlobals()
  versionState.reset()
})

const answer = (res: Response | Partial<Response>) => {
  const fetch = vi.fn().mockResolvedValue(res)
  vi.stubGlobal('fetch', fetch)
  return fetch
}

const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })

describe('request', () => {
  it('survives non-JSON error bodies from proxies', async () => {
    answer(new Response('Bad Gateway', { status: 502 }))
    await expect(request('GET', '/notes')).rejects.toMatchObject({ status: 502, code: 'server_error' })
  })

  it('passes aborts through instead of reporting them as connection loss', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new DOMException('aborted', 'AbortError')))
    const err = await request('GET', '/notes').catch((e: unknown) => e)
    expect(err).not.toBeInstanceOf(NetworkError)
    expect(err).toMatchObject({ name: 'AbortError' })
  })

  it('says which app is asking, and never follows a redirect', async () => {
    const fetch = answer(json({ ok: true }))
    await request('GET', '/notes')
    const init = fetch.mock.calls[0]?.[1] as RequestInit
    expect(new Headers(init.headers).get('X-Notesd-Client')).toBe(CLIENT_ID)
    expect(CLIENT_ID).toBe(`web/${APP_VERSION}`)
    expect(APP_VERSION).not.toMatch(/^v/)
    expect(init.redirect).toBe('manual')
  })

  it('takes a redirect for an answer notesd didn’t give', async () => {
    answer({ type: 'opaqueredirect', status: 0, ok: false, headers: new Headers() })
    await expect(request('PUT', '/notes/a', {})).rejects.toMatchObject({ status: 0, code: UNEXPECTED })
    answer(new Response(null, { status: 302, headers: { Location: '/login' } }))
    await expect(request('PUT', '/notes/a', {})).rejects.toMatchObject({ status: 302, code: UNEXPECTED })
  })

  it('takes a page without notesd’s error body, or a 2xx that isn’t JSON, for an unexpected answer', async () => {
    answer(new Response('<html>Sign in to the Wi-Fi</html>', { status: 403, headers: { 'Content-Type': 'text/html' } }))
    const err = await request('PUT', '/notes/a', {}).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err).toMatchObject({ status: 403, code: UNEXPECTED })
    expect((err as ApiError).message).toContain('(HTTP 403)')

    answer(new Response('<html>portal</html>', { status: 200, headers: { 'Content-Type': 'text/html' } }))
    await expect(request('PUT', '/notes/a', {})).rejects.toMatchObject({ status: 200, code: UNEXPECTED })
    answer(new Response('<html>portal</html>', { status: 200 }))
    await expect(upload('/images/x', new Uint8Array(1), 'image/png')).rejects.toMatchObject({ code: UNEXPECTED })
  })

  it('keeps unknown notesd codes as they are, for the caller to judge', async () => {
    answer(json({ error: 'something_new', message: 'new' }, 409))
    await expect(request('PUT', '/notes/a', {})).rejects.toMatchObject({ status: 409, code: 'something_new' })
  })

  it('marks the app too old on 426 upgrade_required', async () => {
    answer(json({ error: 'upgrade_required', message: 'update notesd', minVersion: '1.2.0' }, 426))
    await expect(request('GET', '/notes')).rejects.toMatchObject({ status: 426, code: 'upgrade_required', minVersion: '1.2.0' })
    expect(versionState.get().upgrade).toEqual({ minVersion: '1.2.0' })
  })

  it.skipIf(APP_VERSION === '0.0.0-dev')('asks for a reload when the server runs another build', async () => {
    answer(json({}, 200, { 'X-Notesd-Server': `v${APP_VERSION}` }))
    await request('GET', '/notes')
    expect(versionState.get().reload).toBe(false)
    answer(json({ error: 'not_found' }, 404, { 'X-Notesd-Server': 'v999.0.0' }))
    await request('GET', '/notes').catch(() => {})
    expect(versionState.get().reload).toBe(true)
  })
})

describe('serverDiffers', () => {
  it('compares without the v, and ignores development builds on either side', () => {
    expect(serverDiffers('v1.0.0', '1.0.0')).toBe(false)
    expect(serverDiffers('v1.0.1', '1.0.0')).toBe(true)
    expect(serverDiffers('dev', '1.0.0')).toBe(false)
    expect(serverDiffers('v1.0.0', '0.0.0-dev')).toBe(false)
    expect(serverDiffers(null, '1.0.0')).toBe(false)
  })
})
