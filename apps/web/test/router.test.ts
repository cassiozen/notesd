import { afterEach, describe, expect, it, vi } from 'vitest'
import { closeOver, moveOver, navigate, openOver, parseRoute, transitionFor } from '../src/router'
import { phoneLayout } from './helpers'

describe('routes', () => {
  it('reads a Settings category from the path, and drops one it doesn’t know', () => {
    expect(parseRoute('/settings')).toEqual({ name: 'settings' })
    expect(parseRoute('/settings/security')).toEqual({ name: 'settings', section: 'security' })
    expect(parseRoute('/settings/nope')).toEqual({ name: 'settings' })
    expect(parseRoute('/settings/Security')).toEqual({ name: 'settings' })
    expect(parseRoute('/settings/sync-data')).toEqual({ name: 'settings' })
    expect(parseRoute('/settings/a/b')).toEqual({ name: 'notes' })
  })
})

describe('navigation transitions', () => {
  afterEach(() => {
    window.history.replaceState(null, '', '/')
    // jsdom has none; one test stubs it.
    Reflect.deleteProperty(document, 'startViewTransition')
  })

  it('pushes deeper and pops shallower on a phone', () => {
    phoneLayout()
    expect(transitionFor('/', '/notes/a')).toBe('forward')
    expect(transitionFor('/notes/a', '/')).toBe('back')
    expect(transitionFor('/tags', '/')).toBe('forward')
    expect(transitionFor('/', '/settings')).toBeNull()
    expect(transitionFor('/settings', '/settings/spaces')).toBeNull()
  })

  it('plays none on a wider screen, so the list keeps taking hover and clicks', () => {
    expect(transitionFor('/notes/a', '/notes/b')).toBeNull()
    expect(transitionFor('/', '/notes/a')).toBeNull()
  })

  it('runs the move inside a view transition where the browser has them', async () => {
    phoneLayout()
    const start = vi.fn((update: () => void) => {
      expect(document.documentElement.dataset.nav).toBe('forward')
      update()
      return { finished: Promise.resolve() }
    })
    Object.assign(document, { startViewTransition: start })

    navigate('/notes/a')
    expect(start).toHaveBeenCalledOnce()
    expect(window.location.pathname).toBe('/notes/a')
    await Promise.resolve()
    await Promise.resolve()
    expect(document.documentElement.dataset.nav).toBeUndefined()

    // A redirect isn't something the user did: no animation.
    navigate('/', { replace: true })
    expect(start).toHaveBeenCalledOnce()
    expect(window.location.pathname).toBe('/')
  })

  it('moves without animating when motion is reduced', () => {
    phoneLayout()
    const start = vi.fn()
    Object.assign(document, { startViewTransition: start })
    const matchMedia = window.matchMedia
    vi.spyOn(window, 'matchMedia').mockImplementation((q) =>
      q === '(prefers-reduced-motion: reduce)' ? ({ matches: true } as MediaQueryList) : matchMedia(q),
    )

    navigate('/notes/a')
    expect(start).not.toHaveBeenCalled()
    expect(window.location.pathname).toBe('/notes/a')
    vi.restoreAllMocks()
  })
})

describe('pages opened over others', () => {
  afterEach(() => window.history.replaceState(null, '', '/'))

  it('each closes back to where it was opened, nested ones in turn', () => {
    window.history.replaceState(null, '', '/notes/a')
    openOver('/settings')
    moveOver('/settings/spaces')
    openOver('/activity', { space: 'work' })
    expect(window.history.state).toMatchObject({ space: 'work', returnTo: '/settings/spaces' })
    closeOver('/', { replace: true })
    expect(window.location.pathname).toBe('/settings/spaces')
    closeOver('/')
    expect(window.location.pathname).toBe('/notes/a')
  })

  it('opened straight from its address, closes to the fallback', () => {
    window.history.replaceState(null, '', '/activity')
    closeOver('/notes/b', { replace: true })
    expect(window.location.pathname).toBe('/notes/b')
  })
})
