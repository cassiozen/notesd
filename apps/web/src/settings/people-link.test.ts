import { describe, expect, it } from 'vitest'
import { isLoopback, linkExpiry, setupLinkURL } from './people-link'

describe('setupLinkURL', () => {
  it('puts the token after # on the page’s own address', () => {
    expect(setupLinkURL('abc_-123', 'https://notes.example.com')).toBe('https://notes.example.com/setup#abc_-123')
    expect(setupLinkURL('t', 'http://192.168.1.20:8080')).toBe('http://192.168.1.20:8080/setup#t')
  })
})

describe('isLoopback', () => {
  it.each(['localhost', 'LOCALHOST', 'notes.localhost', '127.0.0.1', '127.1.2.3', '[::1]'])('%s only works here', (h) => {
    expect(isLoopback(h)).toBe(true)
  })
  it.each(['notes.example.com', '192.168.1.20', 'pi.tailnet.ts.net', '10.0.0.127', 'localhost.example.com'])(
    '%s reaches other devices',
    (h) => {
      expect(isLoopback(h)).toBe(false)
    },
  )
})

describe('linkExpiry', () => {
  const now = Date.UTC(2026, 0, 1)
  const day = 24 * 60 * 60 * 1000
  it('counts days left, rounding up', () => {
    expect(linkExpiry(now + 7 * day, now)).toBe('link expires in 7 days')
    expect(linkExpiry(now + 7 * day - 1000, now)).toBe('link expires in 7 days')
    expect(linkExpiry(now + day + 5, now)).toBe('link expires in 2 days')
    expect(linkExpiry(now + 5, now)).toBe('link expires in 1 day')
  })
  it('says so once it has expired, or there is none', () => {
    expect(linkExpiry(now, now)).toBe('link expired')
    expect(linkExpiry(undefined, now)).toBe('link expired')
  })
})
