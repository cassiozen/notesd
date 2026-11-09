package notes.app.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import notes.app.R
import notes.app.sharing.Publishing
import notes.core.api.SpaceTrait
import notes.core.db.Space

class SpaceTraitsTest {
    private val shared = SpaceTrait("shared", "Shared", "With Ana and Ben", exposure = "Visible to Ana and Ben")
    private val published =
        SpaceTrait("published", "Published", "family.example", exposure = "Public at family.example")
    private val agent = SpaceTrait("agent", "Agent", "Local model")

    private fun space(vararg traits: SpaceTrait) = Space("s", "Family", 0, traits = traits.toList())

    @Test
    fun `the registry keeps the fixed order`() {
        assertEquals(listOf("shared", "published", "agent"), SpaceTraits.entries.map { it.key })
    }

    @Test
    fun `on its own the registry turns nothing on and has no sections`() {
        assertTrue(SpaceTraits.entries.none { it.canTurnOn(space()) || it.section != null })
    }

    @Test
    fun `an unknown key gets the generic glyph and the server's label`() {
        val later = SpaceTrait("archived", "Archived", "Read only")
        assertEquals(R.drawable.trait_unknown, SpaceTraits.glyph(later.key))
        assertEquals(R.drawable.trait_shared, SpaceTraits.glyph("shared"))
        assertEquals("Archived", later.name())
        assertEquals("archived", SpaceTrait("archived").name())
        assertEquals("Shared", SpaceTrait("shared").name())
    }

    @Test
    fun `the description joins labels and says what's wrong`() {
        assertEquals("Shared, Published", describeTraits(listOf(shared, published)))
        assertEquals(
            "Shared, Published (Publishing failed)",
            describeTraits(listOf(shared, published.copy(problem = "Publishing failed")))
        )
    }

    @Test
    fun `a move asks with the exposures the current space lacks`() {
        assertEquals(listOf(shared, published), newExposures(space(), space(shared, published, agent)))
        assertEquals(listOf(published), newExposures(space(shared), space(shared, published)))
        // Shared with more people is a new exposure.
        val wider = shared.copy(exposure = "Visible to Ana, Ben and Cy")
        assertEquals(listOf(wider), newExposures(space(shared), space(wider)))
    }

    @Test
    fun `a move to a space that exposes the same or less doesn't ask`() {
        assertEquals(emptyList(), newExposures(space(shared), space(shared)))
        assertEquals(emptyList(), newExposures(space(shared, published), space(shared)))
        assertEquals(emptyList(), newExposures(space(), space(agent)), "no exposure: kept to the account")
        assertEquals(emptyList(), newExposures(space(), space()))
    }

    @Test
    fun `a move compares who a trait reaches when the server says, not how it words it`() {
        val anaBen = shared.copy(exposure = "Visible to Ana and Ben", audience = listOf("user:2", "user:3"))
        val benAna = shared.copy(exposure = "Visible to Ben and Ana", audience = listOf("user:3", "user:2"))
        val ana = shared.copy(exposure = "Visible to Ana", audience = listOf("user:2"))
        val anaCy = shared.copy(exposure = "Visible to Ana and Cy", audience = listOf("user:2", "user:4"))
        assertEquals(emptyList(), newExposures(space(anaBen), space(benAna)))
        assertEquals(emptyList(), newExposures(space(anaBen), space(ana)))
        assertEquals(listOf(anaCy), newExposures(space(anaBen), space(anaCy)))
    }

    @Test
    fun `an unknown trait's exposure still asks`() {
        val later = SpaceTrait("mirror", "Mirrored", "To a printer", exposure = "Printed at the office")
        assertEquals(listOf(later), newExposures(null, space(later)))
    }

    @Test
    fun `space info lists known traits in order, off ones only when they can be turned on, unknown last`() {
        val later = SpaceTrait("archived", "Archived", "Read only")
        // Sent out of order: the registry's order wins for known keys.
        assertEquals(
            listOf(
                TraitRow.On(SpaceTraits.entries[0], shared),
                TraitRow.On(SpaceTraits.entries[2], agent),
                TraitRow.Unknown(later)
            ),
            traitRows(space(later, agent, shared))
        )
        assertEquals(emptyList(), traitRows(space()))

        val canPublish = TraitEntry("published", R.drawable.trait_published, "Published", canTurnOn = { true })
        val registry = listOf(SpaceTraits.entries[0], canPublish)
        assertEquals(listOf(TraitRow.Off(canPublish)), traitRows(space(), registry))
    }

    @Test
    fun `an off trait a space can't have isn't offered there`() {
        val canShare = SpaceTraits.entries[0].with({}) { it.id != "default" }
        assertEquals(listOf(TraitRow.Off(canShare)), traitRows(space().copy(id = "w"), listOf(canShare)))
        assertEquals(emptyList(), traitRows(space().copy(id = "default"), listOf(canShare)))
    }

    @Test
    fun `locked notes can't be where a trait lets others read, said by the first such trait`() {
        assertEquals(null, exposingTrait(space()))
        assertEquals(null, exposingTrait(space(agent)))
        assertEquals(published, exposingTrait(space(agent, published, shared)))
        assertEquals("Locked notes can't be in a shared space.", lockedRefusal(shared))
        assertEquals("Locked notes can't be in a published space.", lockedRefusal(published))
        // Being turned off on this phone: gone from view, still refusing locked notes until the server confirms.
        assertEquals(published, exposingTrait(space().copy(leaving = listOf(published))))
        assertEquals("Locked notes can't be in a shared space.", lockedRefusal(shared.copy(label = "")))
        assertEquals(
            "Only you can read a locked note, and Family is visible to Ana and Ben. Move it to a space of your own to lock it.",
            lockedReason(shared, "Family", moving = false)
        )
        assertEquals(
            "Only you can read a locked note, and Family is visible to Ana and Ben. Remove this note's lock to move it there.",
            lockedReason(shared, "Family", moving = true)
        )
    }

    @Test
    fun `publishing shows at once, in the server's order, and turning off keeps the lock rules`() {
        val on = Publishing.shown(space(shared, agent), true)
        assertEquals(listOf("shared", "published", "agent"), on.traits.map { it.key })
        assertEquals("Publishing…", on.traits[1].summary)
        // Shared exposes it first either way; alone, the expected trait refuses locked notes at once.
        assertEquals(Publishing.PUBLISHING, exposingTrait(Publishing.shown(space(), true)))
        val off = Publishing.shown(space(shared, published), false)
        assertEquals(listOf(shared), off.traits)
        assertEquals(listOf(published), off.leaving)
        assertEquals(published, exposingTrait(Publishing.shown(space(published), false)))
        // What the list says already wins.
        assertEquals(space(published), Publishing.shown(space(published), true))
        assertEquals(space(), Publishing.shown(space(), false))
    }
}
