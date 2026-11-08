package notes.core.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import notes.core.api.RemoteSpace
import notes.core.api.SpaceTrait

class SpaceStoreTest {
    @Test
    fun `creating the same id again changes nothing`() = storeTest {
        val spaces = SpaceStore(db) { now }
        spaces.create("Work", "briefcase", id = "s1")
        spaces.create("Home", "house", id = "s1")
        assertEquals(listOf(Space("s1", "Work", 0, "briefcase", createdAt = now)), spaces.list().drop(1))
    }

    @Test
    fun `an id the server would refuse is refused here`() = storeTest {
        val spaces = SpaceStore(db) { now }
        for (id in listOf("", "a b", "x".repeat(65), "ü")) {
            assertFailsWith<IllegalArgumentException>(id) { spaces.create("Work", id = id) }
        }
        assertEquals(listOf("Notes"), spaces.list().map { it.name })
    }

    @Test
    fun `traits come from the server even while a rename waits`() = storeTest {
        val spaces = SpaceStore(db) { now }
        spaces.create("Work", id = "s1")
        val shared = SpaceTrait("shared", "Shared", "With Ana", exposure = "Visible to Ana")
        spaces.replace(listOf(RemoteSpace("default", "Notes"), RemoteSpace("s1", "Office", traits = listOf(shared))))
        val work = spaces.list().single { it.id == "s1" }
        assertEquals("Work", work.name, "the rename still waits")
        assertEquals(listOf(shared), work.traits)

        spaces.replace(listOf(RemoteSpace("default", "Notes"), RemoteSpace("s1", "Office")))
        assertEquals(emptyList(), spaces.list().single { it.id == "s1" }.traits)
    }

    @Test
    fun `traits a later release wrote don't break the list`() = storeTest {
        val spaces = SpaceStore(db) { now }
        db.write { sql -> sql.exec("UPDATE spaces SET traits = ? WHERE id = ?", "{not a list", Spaces.DEFAULT) }
        assertEquals(emptyList(), spaces.list().single().traits)
    }
}
