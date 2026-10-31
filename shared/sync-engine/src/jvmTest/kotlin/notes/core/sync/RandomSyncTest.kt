package notes.core.sync

import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import notes.core.markdown.Markdown

/**
 * N devices doing random edits, deletes, syncs, offline spells, lost
 * responses and restarts; then everyone syncs twice. Every device must equal
 * the server, and every text a device had changed locally must still exist
 * somewhere (in its note or in a copy).
 */
class RandomSyncTest {
    private val server = TestServer()
    private val clients = ArrayList<TestClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        server.close()
    }

    @Test
    fun `random histories converge without losing text`() = runBlocking {
        // Seeds share one server, so later runs start from a larger history. 60 seeds passed when this was written.
        val seeds =
            System.getProperty("notes.seeds")?.split(",")?.filter { it.isNotBlank() }?.map { it.trim().toLong() }
                ?: (1L..6L).toList()
        for (seed in seeds) run(seed)
    }

    private suspend fun run(seed: Long) {
        val r = Random(seed)
        val devices = List(3) { TestClient(server, pageSize = 1 + r.nextInt(6)).also { clients += it }.signIn() }
        var step = 0
        repeat(120) {
            step++
            val d = devices[r.nextInt(devices.size)]
            // Sorted so a seed always picks the same notes (copies get random ids).
            val live = d.live().sortedWith(compareBy({ it.title }, { it.content }, { it.conflictOf })).toList()
            when (r.nextInt(20)) {
                in 0..5 -> if (live.isNotEmpty()) {
                    val n = live[r.nextInt(live.size)]
                    val title = if (r.nextBoolean()) "t$seed-$step" else n.title
                    val content = if (r.nextInt(3) > 0) text(r, seed, step) else n.content
                    d.save(n.id, title, content)
                }

                in 6..8 -> d.save("s$seed-$step", "t$seed-$step", text(r, seed, step))

                9 -> if (live.isNotEmpty()) d.notes.delete(live[r.nextInt(live.size)].id)

                10 -> d.offline = !d.offline

                11 -> d.loseNextResponse { it.method != "GET" }

                12 -> d.restart()

                else -> d.sync()
            }
        }

        // Text a device changed and never sent: nobody else can have seen it, so it must survive. (Text in a
        // request whose answer was lost may have reached the server and been edited over since, legitimately.)
        val mustSurvive = HashSet<String>()
        for (d in devices) {
            d.offline = false
            d.clearFaults()
            val inFlight = d.notes.outbox().filter { it.sent }.associateBy { it.noteId }
            for (v in d.live()) {
                val n = d.note(v.id)!!
                val changed = n.dirty && (n.baseContent == null || !sameMarkdown(n.baseContent, n.content))
                val sentAlready =
                    inFlight[n.id]?.payloadJson?.let {
                        Json.parseToJsonElement(it).jsonObject["content"]?.jsonPrimitive?.content
                    } ==
                        n.content
                if (changed && !sentAlready) mustSurvive += canonical(n.content)
            }
        }

        repeat(2) { for (d in devices) assertEquals(SyncOutcome.Done, d.sync(), "seed $seed") }

        val fresh = TestClient(server).also { clients += it }.signIn()
        assertEquals(SyncOutcome.Done, fresh.sync())
        val truth = fresh.live()
        for ((i, d) in devices.withIndex()) assertEquals(truth, d.live(), "seed $seed, device $i")
        // Tags are derived on each device from what sync stored, so they converge with the notes.
        val tags = fresh.notes.tags()
        for ((i, d) in devices.withIndex()) assertEquals(tags, d.notes.tags(), "seed $seed, device $i tags")
        val contents = truth.map { canonical(it.content) }.toSet()
        for (text in mustSurvive) assertTrue(text in contents, "seed $seed lost: $text")
        devices.forEach { assertEquals(0, it.notes.pendingNotes(), "seed $seed: nothing left to send") }
    }

    private fun text(r: Random, seed: Long, step: Int) = when (r.nextInt(3)) {
        0 -> "* item $seed-$step\n* more\n"
        1 -> "**bold** $seed-$step\n"
        else -> "plain $seed-$step #area/t${r.nextInt(3)}\n"
    }

    private fun canonical(s: String) = Markdown.canonical(s)

    private fun sameMarkdown(a: String, b: String) = canonical(a) == canonical(b)
}
