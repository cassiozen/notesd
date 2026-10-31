package notes.core.db

import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationsTest {
    @Test
    fun `splits statements at line-ending semicolons`() {
        val sql = """
            -- header
            CREATE TABLE a (x TEXT DEFAULT ';'); -- trailing
            INSERT INTO a VALUES ('b');
            -- footer
        """.trimIndent()
        assertEquals(
            listOf("-- header\nCREATE TABLE a (x TEXT DEFAULT ';')", "INSERT INTO a VALUES ('b')"),
            Migrations.statements(sql)
        )
    }
}
