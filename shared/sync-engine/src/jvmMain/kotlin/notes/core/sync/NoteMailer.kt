package notes.core.sync

import notes.core.api.ApiClient
import notes.core.api.ApiResult
import notes.core.db.NoteStore

sealed interface EmailOutcome {
    data object Sent : EmailOutcome

    /** The server couldn't be reached, so it doesn't have this note's latest text. */
    data object Offline : EmailOutcome

    /** Reached the server, but this note's changes didn't go up (signed out, a conflict to resolve). */
    data object NotSynced : EmailOutcome

    /** The server or Resend refused; [message] says why, for the person sending. */
    data class Failed(val message: String, val field: String? = null) : EmailOutcome
}

/**
 * Emails a note through notesd. The server sends its own copy, so this syncs
 * first and passes the revision the note is at here: the email is exactly
 * what's on screen. A note changed elsewhere in between is synced and sent
 * again, once.
 */
class NoteMailer(private val store: NoteStore, private val engine: SyncEngine, private val api: () -> ApiClient?) {
    suspend fun send(id: String, to: List<String>): EmailOutcome {
        repeat(2) {
            when (engine.sync()) {
                SyncOutcome.Offline -> return EmailOutcome.Offline
                SyncOutcome.Done, SyncOutcome.Failed -> Unit
                SyncOutcome.NotConnected, SyncOutcome.SignedOut -> return EmailOutcome.NotSynced
            }
            val note = store.get(id) ?: return EmailOutcome.Failed("This note was deleted.")
            if (note.deletedAt != null) return EmailOutcome.Failed("This note was deleted.")
            val revision = note.serverRevision?.takeIf { !note.dirty } ?: return EmailOutcome.NotSynced
            val client = api() ?: return EmailOutcome.NotSynced
            when (val r = client.emailNote(id, to, revision)) {
                is ApiResult.Ok -> return EmailOutcome.Sent

                is ApiResult.Conflict -> if (r.code != STALE) return EmailOutcome.Failed(sentence(r.message))

                is ApiResult.Unreachable -> return EmailOutcome.Offline

                ApiResult.Unauthenticated -> return EmailOutcome.NotSynced

                is ApiResult.RateLimited -> return EmailOutcome.Failed("Too many attempts. Try again in a minute.")

                is ApiResult.Rejected -> return EmailOutcome.Failed(
                    when (r.error) {
                        "email_failed" -> "Resend didn't send it: ${r.message}"
                        "not_found" -> "This note was deleted."
                        else -> sentence(r.message)
                    },
                    r.field
                )
            }
        }
        return EmailOutcome.Failed("This note keeps changing on another device. Try again in a moment.")
    }

    private fun sentence(s: String): String {
        val c = s.replaceFirstChar { it.uppercase() }
        return if (c.endsWith('.')) c else "$c."
    }

    private companion object {
        const val STALE = "stale_revision"
    }
}
