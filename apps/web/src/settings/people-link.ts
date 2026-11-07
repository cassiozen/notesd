/**
 * The server is never told its own address, so a setup link uses the one this page was opened at: the
 * address the admin reaches it by, and likely the one the people they invite will use too.
 */
export function setupLinkURL(token: string, origin = window.location.origin): string {
  // After #: browsers don't send it, so the token stays out of server and proxy logs.
  return `${origin}/setup#${token}`
}

/** Opened on the server itself: a link to this address only works on this computer. */
export function isLoopback(hostname = window.location.hostname): boolean {
  const h = hostname.toLowerCase()
  return h === 'localhost' || h.endsWith('.localhost') || h === '[::1]' || /^127\.\d+\.\d+\.\d+$/.test(h)
}

const DAY = 24 * 60 * 60 * 1000

/** "link expires in 7 days", for an invitation still waiting. */
export function linkExpiry(expiresAt: number | undefined, now = Date.now()): string {
  if (expiresAt === undefined || expiresAt <= now) return 'link expired'
  // Up, so a link just made says 7 days, as it was promised.
  const days = Math.ceil((expiresAt - now) / DAY)
  return `link expires in ${days} ${days === 1 ? 'day' : 'days'}`
}
