import { inDesktopApp } from '../desktop'

/** This session's name, which the server stores: the browser's, or the macOS app's. */
export function deviceName(): string {
  return inDesktopApp() ? 'notesd for macOS' : browserName()
}

/** "Chrome on macOS" from a user agent string; older sessions stored the raw string. */
export function browserName(ua = typeof navigator === 'undefined' ? '' : navigator.userAgent): string {
  const browser = /Edg\//.test(ua)
    ? 'Edge'
    : /Firefox\//.test(ua)
      ? 'Firefox'
      : /Chrome\/|CriOS\//.test(ua)
        ? 'Chrome'
        : /Safari\//.test(ua)
          ? 'Safari'
          : 'Browser'
  const os = osName(ua)
  return os ? `${browser} on ${os}` : browser
}

function osName(ua: string): string {
  if (/iPad/.test(ua)) return 'iPad'
  if (/iPhone|iPod/.test(ua)) return 'iPhone'
  if (/Android/.test(ua)) return 'Android'
  if (/Mac OS X|Macintosh/.test(ua)) return 'macOS'
  if (/Windows/.test(ua)) return 'Windows'
  if (/CrOS/.test(ua)) return 'ChromeOS'
  if (/Linux/.test(ua)) return 'Linux'
  return ''
}

/** Three-letter badge for a session: the platform when the name says it. */
export function platformBadge(name: string, kind: 'web' | 'device'): string {
  const n = name.toLowerCase()
  if (/iphone|ios/.test(n)) return 'IOS'
  if (/ipad/.test(n)) return 'IPD'
  if (/android/.test(n)) return 'AND'
  if (/mac/.test(n)) return 'MAC'
  if (/windows/.test(n)) return 'WIN'
  if (/linux|chromeos/.test(n)) return 'LNX'
  return kind === 'device' ? 'APP' : 'WEB'
}

/** A session's display name; older sessions stored the raw user agent, and apps that sent no device name theirs. */
export function sessionName(name: string, kind: 'web' | 'device'): string {
  if (name.startsWith('Mozilla/')) return browserName(name)
  const app = /^notesd-(android|ios)\//i.exec(name)?.[1]?.toLowerCase()
  if (app) return app === 'ios' ? 'iOS' : 'Android'
  return name || (kind === 'web' ? 'Browser' : 'Device')
}
