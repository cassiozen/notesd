package notes.app.notes

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import notes.core.db.Space
import notes.core.db.SpaceNews

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/** People as trait summaries name them: "Ana", "Ana and Ben", "Ana, Ben and Cy", "Ana, Ben and 2 others". */
fun names(people: List<String>): String = when (people.size) {
    0 -> ""
    1 -> people[0]
    2 -> "${people[0]} and ${people[1]}"
    3 -> "${people[0]}, ${people[1]} and ${people[2]}"
    else -> "${people[0]}, ${people[1]} and ${people.size - 2} others"
}

/** How long ago, in a few characters for a line that's already full: "now", "5m", "2h", "3d", then a date. */
fun ago(
    ts: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): String {
    val d = (now - ts).coerceAtLeast(0)
    return when {
        d < MINUTE -> "now"

        d < HOUR -> "${d / MINUTE}m"

        d < DAY -> "${d / HOUR}h"

        d < 7 * DAY -> "${d / DAY}d"

        else -> {
            val at = Instant.ofEpochMilli(ts).atZone(zone)
            val sameYear = at.year == Instant.ofEpochMilli(now).atZone(zone).year
            val format = if (sameYear) {
                DateTimeFormatter.ofPattern("MMM d", locale)
            } else {
                DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
            }
            at.format(format)
        }
    }
}

/** The switcher's line for what others changed in a shared space (§7.9): "Ben edited 3 notes · 2h". */
fun changeLine(news: SpaceNews, now: Long = System.currentTimeMillis()): String {
    // Everyone who made them has left since: still worth the dot.
    val who = names(news.usernames).ifEmpty { "Someone" }
    val what = if (news.count == 1) "a note" else "${news.count} notes"
    return "$who edited $what · ${ago(news.latestAt, now)}"
}

/** A note row's time when another member changed it last: "Ana · 2h". */
fun byLine(name: String, at: Long, now: Long = System.currentTimeMillis()): String = "$name · ${ago(at, now)}"

/**
 * Who last changed a note in [space], by username, when that was another member (§7.9); null for your own
 * change, a space that isn't shared, or someone no longer in it. [me] null: nothing, as it can't be told.
 */
fun editedBy(space: Space?, updatedBy: Long?, me: Long?): String? {
    val shared = space?.shared ?: return null
    if (updatedBy == null || me == null || updatedBy == me) return null
    return shared.members.firstOrNull { it.id == updatedBy }?.username
}

/** This account's id in a space's members, found by its username, which the server keeps unique. */
fun memberId(spaces: List<Space>, username: String): Long? =
    spaces.firstNotNullOfOrNull { s -> s.shared?.members?.firstOrNull { it.username == username }?.id }
