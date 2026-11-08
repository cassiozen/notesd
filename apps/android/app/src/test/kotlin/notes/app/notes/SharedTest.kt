package notes.app.notes

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import notes.app.sharing.failure
import notes.app.sharing.leaveText
import notes.app.sharing.lockedMessage
import notes.core.api.ApiResult
import notes.core.api.Member
import notes.core.api.SharedSpace
import notes.core.api.SpaceTrait
import notes.core.db.Space
import notes.core.db.SpaceNews

/** The words shared spaces add (§7.9): who changed what, and when, and why something couldn't be done. */
class SharedTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val now = LocalDateTime.of(2026, 11, 8, 15, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val ana = Member(2, "ana")
    private val ben = Member(3, "ben")
    private val me = Member(1, "me")
    private val family = Space(
        "f",
        "Family",
        2,
        traits = listOf(SpaceTrait("shared", "Shared", "With ana and ben", exposure = "Visible to ana and ben")),
        shared = SharedSpace("owner", listOf(me, ana, ben))
    )
    private val notes = Space("default", "Notes", 3)

    @Test
    fun `people are named as trait summaries name them`() {
        assertEquals("", names(emptyList()))
        assertEquals("Ana", names(listOf("Ana")))
        assertEquals("Ben and Ana", names(listOf("Ben", "Ana")))
        assertEquals("Ana, Ben and Cy", names(listOf("Ana", "Ben", "Cy")))
        assertEquals("Ana, Ben and 2 others", names(listOf("Ana", "Ben", "Cy", "Di")))
    }

    @Test
    fun `how long ago fits beside a name`() {
        fun ago(ms: Long) = ago(now - ms, now, ZoneOffset.UTC, Locale.US)
        assertEquals("now", ago(20_000))
        assertEquals("5m", ago(5 * minute))
        assertEquals("2h", ago(2 * hour))
        assertEquals("3d", ago(3 * 24 * hour))
        assertEquals("Oct 1", ago(now - LocalDateTime.of(2026, 10, 1, 9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()))
        assertEquals(
            "3/4/25",
            ago(now - LocalDateTime.of(2025, 3, 4, 9, 0).toInstant(ZoneOffset.UTC).toEpochMilli())
        )
        // A clock a little ahead elsewhere: not "in the future".
        assertEquals("now", ago(-5_000))
    }

    @Test
    fun `the switcher says who changed how many notes, and when`() {
        assertEquals(
            "Ben and Ana edited a note · 5m",
            changeLine(SpaceNews(1, listOf("Ben", "Ana"), now - 5 * minute), now)
        )
        assertEquals("Ben edited 3 notes · 2h", changeLine(SpaceNews(3, listOf("Ben"), now - 2 * hour), now))
        // Whoever made them has left the space since.
        assertEquals("Someone edited 2 notes · now", changeLine(SpaceNews(2, emptyList(), now), now))
    }

    @Test
    fun `a note row names another member's change`() {
        assertEquals("Ana · 2h", byLine("Ana", now - 2 * hour, now))
    }

    @Test
    fun `only another member's change is named`() {
        assertEquals("ana", editedBy(family, 2, me = 1))
        // Your own, or not known to be someone else's.
        assertNull(editedBy(family, 1, me = 1))
        assertNull(editedBy(family, null, me = 1))
        assertNull(editedBy(family, 2, me = null))
        // Someone who has left: nothing.
        assertNull(editedBy(family, 9, me = 1))
        // A space of your own.
        assertNull(editedBy(notes, 2, me = 1))
    }

    @Test
    fun `an account from before the app kept its id is found among a space's members`() {
        assertEquals(1L, memberId(listOf(notes, family), "me"))
        assertNull(memberId(listOf(notes, family), "cy"))
    }

    @Test
    fun `moving out of a shared space loses its exposure, moving in gains it`() {
        assertEquals(listOf("Visible to ana and ben"), lostExposures(family, notes).map { it.exposure })
        assertEquals(emptyList(), newExposures(family, notes))
        assertEquals(emptyList(), lostExposures(notes, family))
        assertEquals(emptyList(), lostExposures(family, family.copy(id = "g")))
    }

    @Test
    fun `leaving warns about changes that haven't synced`() {
        assertEquals(
            "Its notes leave this phone; to see them again, you'd need a new invitation.",
            leaveText("Family", 0)
        )
        assertEquals(
            "Its notes leave this phone; to see them again, you'd need a new invitation. 2 notes in Family have " +
                "changes that haven't synced. They'll be kept as copies in your own notes.",
            leaveText("Family", 2)
        )
    }

    @Test
    fun `membership refusals are said in words`() {
        assertEquals("Unlock its 3 locked notes to share it.", lockedMessage(3))
        assertEquals("Unlock its locked note to share it.", lockedMessage(1))
        assertEquals(
            "This needs a connection. Try again when you're online.",
            failure(ApiResult.Unreachable(RuntimeException()))
        )
        assertEquals(
            "Only the space's owner can do that.",
            failure(ApiResult.Rejected(403, "not_space_owner", "not the owner", null))
        )
        assertEquals(
            "As its owner, make someone else the owner first, or stop sharing it.",
            failure(ApiResult.Conflict("owner_cannot_leave", "", buildJsonObject { }))
        )
        assertEquals("Already invited.", failure(ApiResult.Conflict("already_invited", "already invited", null)))
    }
}
