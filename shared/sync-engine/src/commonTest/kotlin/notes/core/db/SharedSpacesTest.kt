package notes.core.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import notes.core.api.Member
import notes.core.api.RemoteNote
import notes.core.api.RemoteSpace
import notes.core.api.SharedSpace
import notes.core.api.SyncPage

/** Changes queued for notes in and out of shared spaces (§7.9), and what the feeds bring. */
class SharedSpacesTest {
    private val family = RemoteSpace(
        "fam",
        "Family",
        shared = SharedSpace("owner", listOf(Member(1, "owner"), Member(2, "kid")))
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

    private suspend fun StoreTest.queued() = store.outbox().map { Triple(it.type, it.feed, it.payloadJson) }

    @Test
    fun `moving into a shared space queues a move where the server has it, edits after it in the space`() = storeTest {
        setUp()
        store.move("a", "fam")
        store.save("a", "Groceries", "Milk, eggs\n")
        val ops = store.outbox()
        assertEquals(listOf("update" to "", "move" to "", "update" to "fam"), ops.map { it.type to it.feed })
        assertEquals(true, ops[1].payloadJson.contains("\"spaceId\":\"fam\""))
        assertEquals(true, ops[2].payloadJson.contains("eggs"))
    }

    @Test
    fun `moved back before the move went, no move`() = storeTest {
        setUp()
        store.move("a", "fam")
        store.move("a", Spaces.DEFAULT)
        assertEquals(listOf("update" to ""), store.outbox().map { it.type to it.feed })
    }

    @Test
    fun `a note the server never had is just created in the shared space`() = storeTest {
        setUp()
        store.save("b", "New", "")
        store.move("b", "fam")
        assertEquals(listOf("create" to "fam"), store.outbox().filter { it.noteId == "b" }.map { it.type to it.feed })
    }

    @Test
    fun `a delete goes where the server has the note`() = storeTest {
        setUp()
        store.move("a", "fam")
        store.delete("a")
        assertEquals(listOf("delete" to ""), queued().map { it.first to it.second })
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
        sync.syncFeeds()
        assertEquals(emptyList(), sync.feeds())
        assertNull(store.get("s"))
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
        assertEquals(SpaceNews(1, listOf("kid"), 3_000), spaces.news()["fam"])
        now = 4_000
        spaces.visit("fam")
        assertEquals(emptyMap(), spaces.news())
    }
}
