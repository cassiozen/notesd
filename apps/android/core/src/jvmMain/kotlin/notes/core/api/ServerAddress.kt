package notes.core.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What the user typed as their server, made into a base URL, or why it can't be one. */
sealed interface ServerAddress {
    data class Valid(val url: HttpUrl) : ServerAddress

    data class Invalid(val message: String) : ServerAddress

    companion object {
        /**
         * Accepts a bare host ("notes.example.com", "192.168.1.5:8080") or a
         * full URL. Without a scheme, private addresses get http and the rest
         * https. Plain http is refused outside private addresses: tokens
         * would cross the internet unencrypted.
         */
        fun parse(input: String): ServerAddress {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return Invalid("Enter your server's address.")
            val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed)
            val parsed = if (hasScheme) {
                trimmed.toHttpUrlOrNull()
            } else {
                val host = trimmed.substringBefore('/').substringBefore(':').removeSurrounding("[", "]")
                "${if (isPrivate(host)) "http" else "https"}://$trimmed".toHttpUrlOrNull()
            } ?: return Invalid("That doesn't look like a server address.")
            // "example.com/notes/" and "example.com/notes" are the same server.
            val segments = parsed.pathSegments.dropLastWhile { it.isEmpty() }
            val url = parsed.newBuilder().encodedPath("/").apply { segments.forEach(::addPathSegment) }.build()
            if (url.scheme == "http" && !isPrivate(url.host)) {
                return Invalid("Use https:// for a server outside your local network.")
            }
            if (url.query != null || url.fragment != null) return Invalid("That doesn't look like a server address.")
            return Valid(url)
        }

        /** Loopback, private and link-local addresses, and mDNS names: reachable only on this network. */
        fun isPrivate(host: String): Boolean {
            val h = host.lowercase().removeSurrounding("[", "]")
            if (h == "localhost" || h.endsWith(".local")) return true
            val v4 = h.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return false }
            if (v4 != null) {
                if (v4.any { it !in 0..255 }) return false
                val (a, b) = v4
                return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                    (a == 169 && b == 254)
            }
            if (':' in h) {
                return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe8") ||
                    h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")
            }
            return false
        }
    }
}
