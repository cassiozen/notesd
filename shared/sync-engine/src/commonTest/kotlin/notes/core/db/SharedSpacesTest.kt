package notes.core.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import notes.core.api.Member
import notes.core.api.RemoteInvite
import notes.core.api.RemoteNote
import notes.core.api.RemoteSpace
import notes.core.api.SharedSpace
import notes.core.api.SyncPage

/** Changes queued for notes in and out of shared spaces (§7.9), and what the feeds bring. */
class SharedSpacesTest {
    private val family = RemoteSpace(
        "fam",
        "Family",
        shared = SharedSpace(
            "owner",
            listOf(Member(1, "owner"), Member(2, "kid", displayName = "Kiddo", avatar = "v1"))
        )
    )

    /** The list with "fam" shared, and "a" synced, clean, in the default space. */
    private suspend fun StoreTest.setUp(): SyncStore {
        val spaces = SpaceStore(db) { now }
        spaces.replace(listOf(RemoteSpace("default", "Notes"), family))
        val sync = SyncStore(db) { now }
        sync.syncFeeds()
        store.save("a", "Groceries", "Milk\n")
        db.write { sql -> sql.exec("DELETE FROM sync_outbox") }
        db.write { sql -> sql.exec("UPDATE notes SET server_revision = 1, dirty = 0 WHERE id = 'a'") }
        return sync
    }

    @Test
    fun `moving into a shared space is one update naming it, edits coalescing into it`() = storeTest {
        setUp()
        store.move("a", "fam")
        store.save("a", "Groceries", "Milk, eggs\n")
        val ops = store.outbox()
        assertEquals(listOf("update"), ops.map { it.type })
        assertEquals(true, ops[0].payloadJson.contains("\"spaceId\":\"fam\""))
        assertEquals(true, ops[0].payloadJson.contains("eggs"))
    }

    @Test
    fun `a delete after a move is queued like any other`() = storeTest {
        setUp()
        store.move("a", "fam")
        store.delete("a")
        assertEquals(listOf("delete"), store.outbox().map { it.type })
    }

    @Test
    fun `a note moved where this device follows keeps its text in the new space`() = storeTest {
        val sync = setUp()
        sync.applyPage(
            SyncPage(
                listOf(RemoteNote("a", revision = 2, deletedAt = 5, spaceId = "default", movedTo = "fam")),
                2,
                false,
                "g"
            )
        )
        val a = store.get("a")!!
        assertEquals("fam" to "Groceries", a.spaceId to a.title)
        assertNull(a.deletedAt)
        // The space's feed brings it at the revision it kept, then another member's edit.
        sync.applyPage(
            SyncPage(listOf(RemoteNote("a", "Groceries", "Milk\n", revision = 1, spaceId = "fam")), 1, false, "h"),
            "fam"
        )
        sync.applyPage(
            SyncPage(
                listOf(RemoteNote("a", "Groceries", "Milk, eggs\n", revision = 2, spaceId = "fam", updatedBy = 2)),
                2,
                false,
                "h"
            ),
            "fam"
        )
        assertEquals("Milk, eggs\n" to 2L, store.get("a")!!.let { it.content to it.updatedBy })
    }

    @Test
    fun `a note moved out of reach is gone, like a deletion`() = storeTest {
        val sync = setUp()
        sync.applyPage(
            SyncPage(
                listOf(RemoteNote("a", revision = 2, deletedAt = 5, spaceId = "default", movedTo = "elsewhere")),
                2,
                false,
                "g"
            )
        )
        assertEquals(5L, store.get("a")!!.deletedAt)
    }

    @Test
    fun `another member's move to their own default space is a deletion here, not a move to ours`() = storeTest {
        val sync = setUp()
        sync.setUserId(1)
        sync.applyPage(
            SyncPage(listOf(RemoteNote("s", "Shared", "", revision = 1, spaceId = "fam")), 1, false, "h"),
            "fam"
        )
        sync.applyPage(
            SyncPage(
                listOf(
                    RemoteNote("s", revision = 2, deletedAt = 5, spaceId = "fam", movedTo = "default", updatedBy = 2)
                ),
                2,
                false,
                "h"
            ),
            "fam"
        )
        assertEquals(5L, store.get("s")!!.deletedAt)
    }

    @Test
    fun `this account's own move to its default space is followed`() = storeTest {
        val sync = setUp()
        sync.setUserId(1)
        sync.applyPage(
            SyncPage(listOf(RemoteNote("s", "Shared", "", revision = 1, spaceId = "fam")), 1, false, "h"),
            "fam"
        )
        sync.applyPage(
            SyncPage(
                listOf(
                    RemoteNote("s", revision = 2, deletedAt = 5, spaceId = "fam", movedTo = "default", updatedBy = 1)
                ),
                2,
                false,
                "h"
            ),
            "fam"
        )
        assertEquals(Spaces.DEFAULT to null, store.get("s")!!.let { it.spaceId to it.deletedAt })
    }

    @Test
    fun `leaving a shared space takes its notes off the device`() = storeTest {
        val sync = setUp()
        sync.applyPage(
            SyncPage(listOf(RemoteNote("s", "Shared", "", revision = 1, spaceId = "fam")), 1, false, "h"),
            "fam"
        )
        assertEquals(listOf("fam"), sync.feeds())
        SpaceStore(db) { now }.replace(listOf(RemoteSpace("default", "Notes")))
        assertEquals(emptyList(), sync.syncFeeds())
        assertEquals(emptyList(), sync.feeds())
        assertNull(store.get("s"))
    }

    @Test
    fun `leaving with a move out not yet sent keeps the note, sent again as new where it is`() = storeTest {
        val sync = setUp()
        sync.applyPage(
            SyncPage(listOf(RemoteNote("s", "Shared", "Text\n", revision = 3, spaceId = "fam")), 1, false, "h"),
            "fam"
        )
        store.move("s", Spaces.DEFAULT)
        assertEquals(listOf("update"), store.outbox().filter { it.noteId == "s" }.map { it.type })
        SpaceStore(db) { now }.replace(listOf(RemoteSpace("default", "Notes")))
        sync.syncFeeds()
        val s = store.get("s")!!
        assertEquals(Triple(Spaces.DEFAULT, null, "Text\n"), Triple(s.spaceId, s.serverRevision, s.content))
        assertEquals(listOf("create"), store.outbox().filter { it.noteId == "s" }.map { it.type })
    }

    @Test
    fun `leaving with a move in not yet sent puts the note back where the server has it`() = storeTest {
        val sync = setUp()
        store.move("a", "fam")
        store.save("a", "Groceries", "Milk, eggs\n")
        SpaceStore(db) { now }.replace(listOf(RemoteSpace("default", "Notes")))
        sync.syncFeeds()
        val a = store.get("a")!!
        assertEquals(Spaces.DEFAULT to "Milk, eggs\n", a.spaceId to a.content)
        val ops = store.outbox().filter { it.noteId == "a" }
        assertEquals(listOf("update"), ops.map { it.type })
        assertEquals(true, ops[0].payloadJson.contains("eggs"))
        assertEquals(true, ops[0].payloadJson.contains("\"spaceId\":\"default\""))
    }

    @Test
    fun `leaving with a delete not yet sent, of a note moved in here, keeps the delete`() = storeTest {
        val sync = setUp()
        store.move("a", "fam")
        store.delete("a")
        SpaceStore(db) { now }.replace(listOf(RemoteSpace("default", "Notes")))
        sync.syncFeeds()
        assertEquals(Spaces.DEFAULT, store.get("a")!!.spaceId)
        assertEquals("delete", store.outbox().last { it.noteId == "a" }.type)
    }

    @Test
    fun `a move to a space of this account's, by an unknown member, is a deletion here`() = storeTest {
        val sync = setUp()
        sync.applyPage(
            SyncPage(listOf(RemoteNote("s", "Shared", "", revision = 1, spaceId = "fam")), 1, false, "h"),
            "fam"
        )
        sync.applyPage(
            SyncPage(
                listOf(RemoteNote("s", revision = 2, deletedAt = 5, spaceId = "fam", movedTo = "default")),
                2,
                false,
                "h"
            ),
            "fam"
        )
        assertEquals(5L, store.get("s")!!.deletedAt)
    }

    @Test
    fun `a space shared again counts only what's new since`() = storeTest {
        val sync = setUp()
        val spaces = SpaceStore(db) { now }
        db.write { it.exec("UPDATE spaces SET visited_at = 1 WHERE id = 'fam'") }
        spaces.replace(listOf(RemoteSpace("default", "Notes"), family.copy(shared = null)))
        now = 9_000
        spaces.replace(listOf(RemoteSpace("default", "Notes"), family))
        sync.syncFeeds()
        assertEquals(9_000L, db.read { it.long("SELECT visited_at FROM spaces WHERE id = 'fam'") })
    }

    @Test
    fun `a space gone with an edit not yet synced keeps the edit as a copy in the default space`() = storeTest {
        val sync = setUp()
        sync.applyPage(
            SyncPage(listOf(RemoteNote("s", "Plans", "Beach\n", revision = 1, spaceId = "fam")), 1, false, "h"),
            "fam"
        )
        store.save("s", "Plans", "Beach, then hike\n")
        // Removed by the owner, or the space deleted: nothing warned this device.
        SpaceStore(db) { now }.replace(listOf(RemoteSpace("default", "Notes")))
        val events = sync.syncFeeds()
        assertNull(store.get("s"))
        val copy = events.single() as SyncEvent.RecoveredCopy
        val kept = store.get(copy.copyId)!!
        assertEquals("Plans (Recovered copy)", kept.title)
        assertEquals("Beach, then hike\n" to Spaces.DEFAULT, kept.content to kept.spaceId)
        assertEquals(listOf("create"), store.outbox().map { it.type })
    }

    @Test
    fun `a locked edit refused by a space exposed since becomes a locked copy, the note back as the server has it`() =
        storeTest {
            val sync = setUp()
            sync.applyPage(
                SyncPage(listOf(RemoteNote("s", "Diary", "Plain\n", revision = 1, spaceId = "fam")), 1, false, "h"),
                "fam"
            )
            val locked = "notesd:locked:v1:" + "A".repeat(40)
            store.save("s", "Diary", locked)
            val op = sync.nextOperation()!!
            val event = sync.lockedOut(op) as SyncEvent.RecoveredCopy
            val note = store.get("s")!!
            assertEquals(Triple("Plain\n", false, "fam"), Triple(note.content, note.dirty, note.spaceId))
            val copy = store.get(event.copyId)!!
            assertEquals(locked to Spaces.DEFAULT, copy.content to copy.spaceId)
            assertEquals(listOf("create" to event.copyId), store.outbox().map { it.type to it.noteId })
        }

    @Test
    fun `an invitation keeps who sent it, by the name and photo they chose`() = storeTest {
        val spaces = SpaceStore(db) { now }
        val invite = RemoteInvite("i1", "club", "Book club", "open-book", Member(3, "ben", "Ben B", "v9"), 5)
        spaces.replaceInvites(listOf(invite))
        assertEquals(listOf(invite), spaces.invites())
    }

    @Test
    fun `what others changed since the space was last shown`() = storeTest {
        val sync = setUp()
        sync.setUserId(1)
        val spaces = SpaceStore(db) { now }
        now = 2_000
        sync.applyPage(
            SyncPage(
                listOf(RemoteNote("s", "Shared", "", revision = 1, updatedAt = 3_000, spaceId = "fam", updatedBy = 2)),
                1,
                false,
                "h"
            ),
            "fam"
        )
        // By the name they chose, kept with the space's members.
        assertEquals(SpaceNews(1, listOf("Kiddo"), 3_000), spaces.news()["fam"])
        assertEquals(family.shared, spaces.list().single { it.id == "fam" }.shared)
        now = 4_000
        spaces.visit("fam")
        assertEquals(emptyMap(), spaces.news())
        // A visit timed earlier, landing late, doesn't bring it back.
        spaces.visit("fam", at = 2_500)
        assertEquals(emptyMap(), spaces.news())
    }
}
