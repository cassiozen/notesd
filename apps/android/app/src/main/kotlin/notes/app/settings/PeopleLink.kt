package notes.app.settings

import kotlin.math.ceil

private const val DAY_MS = 24 * 60 * 60 * 1000.0

/**
 * The server is never told its own address: the link uses the one this phone reaches it at. After `#`, so the
 * token stays out of server and proxy logs; the web app's /setup page reads it there.
 */
internal fun setupLinkUrl(server: String, token: String): String = "${server.trimEnd('/')}/setup#$token"

/** "link expires in 7 days", for an invitation still waiting. Rounded up, so a link just made says 7, as promised. */
internal fun linkExpiry(expiresAt: Long?, now: Long = System.currentTimeMillis()): String {
    if (expiresAt == null || expiresAt <= now) return "link expired"
    val days = ceil((expiresAt - now) / DAY_MS).toInt()
    return "link expires in $days ${if (days == 1) "day" else "days"}"
}

/** What the share sheet sends: a line the recipient can act on, then the link. */
internal fun shareText(host: String, pending: Boolean, url: String): String = if (pending) {
    "You're invited to notesd at $host. Choose your password here. The link works once, for 7 days:\n$url"
} else {
    "Here's a link to choose a new notesd password at $host. It works once, for 7 days:\n$url"
}
