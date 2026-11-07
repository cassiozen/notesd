package notes.core.db

import notes.core.crypto.LockedEnvelope
import notes.core.markdown.BodyMerge
import notes.core.markdown.canonicalMarkdown
import notes.core.markdown.mergeMarkdown

/**
 * §6.1 per-field merge against the last synced version: a field changed on
 * one side only takes that side; null when both changed it differently.
 */
internal object Merge {
    fun field(base: String?, mine: String, theirs: String, same: (String, String) -> Boolean): String? = when {
        base != null && same(mine, base) -> theirs
        same(theirs, mine) || (base != null && same(theirs, base)) -> mine
        else -> null
    }

    val sameText = { a: String, b: String -> a == b }

    /**
     * Another client's Markdown style ("* a", "__b__") isn't an edit: compare the canonical form.
     * A locked body (§10.1) is compared as stored: its envelope only changes when its text does, and
     * sync merges without the key, so changed on both sides means a conflicted copy (still locked).
     */
    val sameMarkdown = { a: String, b: String ->
        a == b ||
            (!LockedEnvelope.isLocked(a) && !LockedEnvelope.isLocked(b) && canonicalMarkdown(a) == canonicalMarkdown(b))
    }

    /**
     * §6.2: a body both sides changed, merged block by block. Locked bodies always conflict, and so does
     * a merge that fails: a conflicted copy is the answer that can't lose text.
     */
    fun body(base: String?, mine: String, theirs: String): BodyMerge {
        if (base == null || listOf(base, mine, theirs).any(LockedEnvelope::isLocked)) return BodyMerge.Conflict
        return try {
            mergeMarkdown(base, mine, theirs)
        } catch (_: Exception) {
            BodyMerge.Conflict
        }
    }
}
