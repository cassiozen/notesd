import { describe, expect, it } from 'vitest'
import { browserName, platformBadge, sessionName } from './user-agent'

const mac =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36'
const iphone =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1'

describe('session names', () => {
  it('names browsers from their user agent', () => {
    expect(browserName(mac)).toBe('Chrome on macOS')
    expect(browserName(iphone)).toBe('Safari on iPhone')
    expect(browserName('curl/8.7.1')).toBe('Browser')
  })

  it('turns old raw user-agent names into readable ones', () => {
    expect(sessionName(mac, 'web')).toBe('Chrome on macOS')
    expect(sessionName("Mara's iPhone", 'device')).toBe("Mara's iPhone")
    expect(sessionName('', 'device')).toBe('Device')
    expect(sessionName('notesd-android/1.0.0', 'device')).toBe('Android')
    expect(sessionName('notesd-ios/1.0.0', 'device')).toBe('iOS')
  })

  it('badges by platform', () => {
    expect(platformBadge('Safari on iPhone', 'web')).toBe('IOS')
    expect(platformBadge('Chrome on macOS', 'web')).toBe('MAC')
    expect(platformBadge('Kitchen tablet', 'device')).toBe('APP')
    expect(platformBadge(sessionName('notesd-android/1.0.0', 'device'), 'device')).toBe('AND')
    expect(platformBadge(sessionName('notesd-ios/1.0.0', 'device'), 'device')).toBe('IOS')
  })
})
