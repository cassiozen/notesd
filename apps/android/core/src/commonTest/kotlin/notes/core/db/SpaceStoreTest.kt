package notes.core.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpaceStoreTest {
    @Test
    fun `creating the same id again changes nothing`() = storeTest {
        val spaces = SpaceStore(db) { now }
        spaces.create("Work", "briefcase", id = "s1")
        spaces.create("Home", "house", id = "s1")
        assertEquals(listOf(Space("s1", "Work", 0, "briefcase")), spaces.list().drop(1))
    }

    @Test
    fun `an id the server would refuse is refused here`() = storeTest {
        val spaces = SpaceStore(db) { now }
        for (id in listOf("", "a b", "x".repeat(65), "ü")) {
            assertFailsWith<IllegalArgumentException>(id) { spaces.create("Work", id = id) }
        }
        assertEquals(listOf("Notes"), spaces.list().map { it.name })
    }
}
