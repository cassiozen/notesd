package notes.app.notes

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import notes.app.R
import notes.core.api.SpaceTrait
import notes.core.db.Space

/**
 * A space trait this app knows (§7.8). [label] names it while it's off; on, the server's label and summary
 * are shown. [settings] and [turnOn] are shown from Space info, and are given a way to close.
 */
class TraitEntry(
    val key: String,
    @DrawableRes val glyph: Int,
    val label: String,
    /** Opened from its Space info row while it's on; null: no settings, and no chevron. */
    val settings: (@Composable (space: Space, onClose: () -> Unit) -> Unit)? = null,
    /** Shown on Turn on; null: this app can't turn it on yet, so the row is left out while it's off. */
    val turnOn: (@Composable (space: Space, onClose: () -> Unit) -> Unit)? = null,
    /** Whether a space can have it at all; one that can't leaves the row out while it's off. */
    val offeredFor: (Space) -> Boolean = { true }
) {
    val canTurnOn: Boolean get() = turnOn != null

    /** The same trait with this app's ways to change it. */
    fun with(
        settings: (@Composable (space: Space, onClose: () -> Unit) -> Unit)?,
        turnOn: (@Composable (space: Space, onClose: () -> Unit) -> Unit)?,
        offeredFor: (Space) -> Boolean
    ) = TraitEntry(key, glyph, label, settings, turnOn, offeredFor)
}

/** Every trait this app knows, in the fixed order; a key is reserved here before its feature ships. */
object SpaceTraits {
    val entries: List<TraitEntry> = listOf(
        TraitEntry("shared", R.drawable.trait_shared, "Shared"),
        TraitEntry("published", R.drawable.trait_published, "Published"),
        TraitEntry("agent", R.drawable.trait_agent, "Agent")
    )

    private val byKey = entries.associateBy { it.key }

    /** A newer server's trait this app doesn't know. */
    @DrawableRes
    val unknownGlyph: Int = R.drawable.trait_unknown

    fun entry(key: String): TraitEntry? = byKey[key]

    @DrawableRes
    fun glyph(key: String): Int = byKey[key]?.glyph ?: unknownGlyph
}

/** What a trait is called: the server's label, else the app's, else its key. */
fun SpaceTrait.name(): String = label.ifBlank { SpaceTraits.entry(key)?.label ?: key }

/**
 * The traits of [from] that a note moved to [to] would leave: those whose exposure sentence [to] lacks (§7.8).
 * Empty: moving out needn't ask.
 */
fun lostExposures(from: Space?, to: Space): List<SpaceTrait> = from?.let { newExposures(to, it) }.orEmpty()

/** For TalkBack: each trait's name, a problem after it. */
fun describeTraits(traits: List<SpaceTrait>): String =
    traits.joinToString(", ") { t -> t.problem?.let { "${t.name()} ($it)" } ?: t.name() }

/**
 * The traits of [to] that would expose a note moved there from [from] to someone [from] doesn't reach, once
 * per sentence. Empty: the move needn't ask.
 */
fun newExposures(from: Space?, to: Space): List<SpaceTrait> {
    val reached = from?.traits.orEmpty().flatMapTo(HashSet()) { it.audience.orEmpty() }
    // A trait without an audience (one this app predates) can only be compared by its words.
    val said = from?.traits.orEmpty().mapNotNullTo(HashSet()) { it.exposure }
    return to.traits.filter { t ->
        val sentence = t.exposure ?: return@filter false
        val audience = t.audience.orEmpty()
        if (audience.isNotEmpty()) audience.any { it !in reached } else sentence !in said
    }.distinctBy { it.exposure }
}

/** A row of Space info's traits group. */
sealed interface TraitRow {
    data class On(val entry: TraitEntry, val trait: SpaceTrait) : TraitRow

    /** Off, and this app can turn it on. */
    data class Off(val entry: TraitEntry) : TraitRow

    /** On, from a newer server: shown, but only a newer app can change it. */
    data class Unknown(val trait: SpaceTrait) : TraitRow
}

/**
 * Space info's trait rows: each [registry] entry in its order, an off one only if it can be turned on (for
 * [space], when given), then the keys the registry doesn't have, as sent.
 */
fun traitRows(
    traits: List<SpaceTrait>,
    registry: List<TraitEntry> = SpaceTraits.entries,
    space: Space? = null
): List<TraitRow> {
    val known = registry.mapNotNull { entry ->
        val trait = traits.firstOrNull { it.key == entry.key }
        when {
            trait != null -> TraitRow.On(entry, trait)
            entry.canTurnOn && (space == null || entry.offeredFor(space)) -> TraitRow.Off(entry)
            else -> null
        }
    }
    val keys = registry.mapTo(HashSet()) { it.key }
    return known + traits.filter { it.key !in keys }.map { TraitRow.Unknown(it) }
}
