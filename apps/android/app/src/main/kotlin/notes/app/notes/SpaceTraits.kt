package notes.app.notes

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import notes.app.R
import notes.core.api.SpaceTrait
import notes.core.db.Space

/**
 * A space trait this app knows (§7.8). [label] names it while it's off; on, the server's label and summary
 * are shown.
 */
class TraitEntry(
    val key: String,
    @DrawableRes val glyph: Int,
    val label: String,
    /**
     * Its row in Space info: how it's set now, in a line. Null: Space info shows the server's label and summary,
     * and there's nothing to change.
     */
    val row: (@Composable (space: Space) -> TraitLine)? = null,
    /**
     * Its page, opened from its row: everything it changes, turning it on included, in a [notes.app.settings.TraitPage]
     * frame. [back] goes back to Space info, as the back gesture does.
     */
    val page: (@Composable (space: Space, back: () -> Unit) -> Unit)? = null,
    /** Off, whether Space info offers it for [Space]. Set only with a [page]: that page is what turns it on. */
    val canTurnOn: (Space) -> Boolean = { false }
) {
    /** The same trait with this app's way to change it. */
    fun with(
        row: @Composable (space: Space) -> TraitLine,
        page: @Composable (space: Space, back: () -> Unit) -> Unit,
        canTurnOn: (Space) -> Boolean
    ) = TraitEntry(key, glyph, label, row, page, canTurnOn)
}

/**
 * A trait's row in Space info. [label] is this app's name for it; [status], how it's set or its problem. Without
 * [opens], it's only said: there's nothing for this account to change ([TraitEntry.page] isn't offered).
 */
class TraitLine(
    val label: String,
    val status: String,
    val on: Boolean,
    val problem: Boolean = false,
    /** Reachable by anyone now (a site that's up): a dot before the status. */
    val live: Boolean = false,
    val opens: Boolean = true,
    /** Before the chevron: who's in it, say. */
    val end: (@Composable () -> Unit)? = null
)

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

/** The first trait that lets others read [space]'s notes: a locked note, only yours to read, can't be there (§7.8). */
fun exposingTrait(space: Space?): SpaceTrait? =
    space?.traits?.firstOrNull { it.exposure != null } ?: space?.leaving?.firstOrNull { it.exposure != null }

/** "Locked notes can't be in a shared space." */
fun lockedRefusal(trait: SpaceTrait): String = "Locked notes can't be in a ${trait.name().lowercase()} space."

/** Why, and what to do instead: lock it elsewhere, or unlock it to move it into [space]. */
fun lockedReason(trait: SpaceTrait, space: String, moving: Boolean): String {
    val exposure = trait.exposure.orEmpty().replaceFirstChar { it.lowercase() }
    val next = if (moving) "Remove this note's lock to move it there." else "Move it to a space of your own to lock it."
    return "Only you can read a locked note, and $space is $exposure. $next"
}

/** A trait's place in Space info: its section, or a row. */
sealed interface TraitRow {
    data class On(val entry: TraitEntry, val trait: SpaceTrait) : TraitRow

    /** Off, and this app can turn it on. */
    data class Off(val entry: TraitEntry) : TraitRow

    /** On, from a newer server: shown, but only a newer app can change it. */
    data class Unknown(val trait: SpaceTrait) : TraitRow
}

/**
 * Space info's traits for [space]: each [registry] entry in its order, an off one only if it can be turned on
 * there, then the keys the registry doesn't have, as sent.
 */
fun traitRows(space: Space, registry: List<TraitEntry> = SpaceTraits.entries): List<TraitRow> {
    val known = registry.mapNotNull { entry ->
        val trait = space.traits.firstOrNull { it.key == entry.key }
        when {
            trait != null -> TraitRow.On(entry, trait)
            entry.canTurnOn(space) -> TraitRow.Off(entry)
            else -> null
        }
    }
    val keys = registry.mapTo(HashSet()) { it.key }
    return known + space.traits.filter { it.key !in keys }.map { TraitRow.Unknown(it) }
}
