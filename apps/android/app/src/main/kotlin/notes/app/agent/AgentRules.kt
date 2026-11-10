package notes.app.agent

import notes.core.api.AgentConfig

/** A space's agent being edited. [triggers] has a key for each rule, an empty prompt for one being written. */
data class AgentDraft(
    val enabled: Boolean = false,
    val prompt: String = "",
    val menuLabel: String = "",
    val triggers: Map<String, String> = emptyMap(),
    val referenceNotes: List<String> = emptyList()
)

/** Notes it always reads, at most; as the server allows. */
const val MAX_REFERENCES = 5

fun draftOf(config: AgentConfig?): AgentDraft = config?.let {
    AgentDraft(it.enabled, it.prompt, it.menuLabel, it.triggers, it.referenceNotes)
} ?: AgentDraft()

/** Equal as saved: the same rules, an empty one too, in any order. */
fun sameAgent(a: AgentDraft, b: AgentDraft): Boolean = a.copy(triggers = emptyMap()) == b.copy(triggers = emptyMap()) &&
    TRIGGERS.all { a.triggers[it.key] == b.triggers[it.key] }

/** The rules a draft has, in the moments' order. */
fun rulesOf(d: AgentDraft): List<String> = TRIGGERS.map { it.key }.filter { it in d.triggers }

/** Rules with nothing to do: saving them would drop them without a word. */
fun emptyRules(d: AgentDraft): List<String> = rulesOf(d).filter { d.triggers[it].isNullOrBlank() }

enum class TriggerGroup(val title: String) {
    Writing("While you write"),
    Done("When you’re done"),
    Moved("When notes come and go")
}

/** A rule to borrow. [title]: what it does, in a few words. [reads]: notes it needs the agent to always read. */
data class Idea(
    val title: String,
    val prompt: String,
    val menuLabel: String? = null,
    val reads: List<String> = emptyList()
)

/**
 * A moment the agent can act on. [label]: its name, as Activity shows it. [rule]: a rule's heading, always
 * "When …". [whenText]: exactly when it happens. [idea]: a general one, where the examples have none.
 */
data class Trigger(
    val key: String,
    val group: TriggerGroup,
    val label: String,
    val rule: String,
    val whenText: String,
    val idea: Idea? = null
) {
    /** The rule's heading without its "When ", for the moment picker. */
    val moment: String get() = rule.removePrefix("When ")
}

// As the web's agent-rules.ts.
val TRIGGERS: List<Trigger> = listOf(
    Trigger(
        "noteCreated",
        TriggerGroup.Writing,
        "Note created",
        "When a new note is opened",
        "Before you type anything. Good for a prompt or a template to start from.",
        Idea("A question to start", "Show me a card at the top with one short question to get me started.")
    ),
    Trigger(
        "editStarted",
        TriggerGroup.Writing,
        "Started editing",
        "When you start editing a note",
        "At your first change after opening an existing note, or after 3 minutes without changes.",
        Idea("Date it", "If the note has no date, add today’s at the top.")
    ),
    Trigger(
        "menu",
        TriggerGroup.Writing,
        "Run from menu",
        "When you ask, from the note’s menu",
        "A command in the note’s ⋯ menu. Also a handy way to try a prompt."
    ),
    Trigger(
        "newNoteDone",
        TriggerGroup.Done,
        "New note done",
        "When you finish a new note",
        "When you close it, or leave it for 2 minutes.",
        Idea("Title and tags", "Give the note a short title if it has none, and add 1–3 tags at the end.")
    ),
    Trigger(
        "editDone",
        TriggerGroup.Done,
        "Note edit done",
        "When you finish editing a note",
        "When you close a note you changed, or leave it for 2 minutes.",
        Idea("Finished tasks last", "Move finished tasks to the end of their list.")
    ),
    Trigger(
        "noteDeleted",
        TriggerGroup.Moved,
        "Note deleted",
        "When a note is deleted",
        "The agent gets what the note said, and can update other notes."
    ),
    Trigger(
        "movedIn",
        TriggerGroup.Moved,
        "Note moved in",
        "When a note is moved here",
        "From another space.",
        Idea("Tag it for here", "Add the space’s tags that fit, at the end.")
    ),
    Trigger(
        "movedOut",
        TriggerGroup.Moved,
        "Note moved out",
        "When a note is moved away",
        "To another space. The agent gets it as it left, and can’t change it."
    )
)

fun trigger(key: String): Trigger? = TRIGGERS.firstOrNull { it.key == key }

/** A trigger's name, as Activity shows it. */
fun triggerLabel(key: String): String = trigger(key)?.label ?: key

/** A whole agent to learn from, or to take part or all of. [rules] by moment. */
data class AgentExample(val name: String, val blurb: String, val prompt: String, val rules: Map<String, Idea>)

private val DIGEST_TRIM = Idea(
    "Keep the Digest current",
    "Take out of the Digest anything that came only from this entry.",
    reads = listOf("Digest")
)

// From the agents guide, as the web's agent-examples.ts.
val EXAMPLES: List<AgentExample> = listOf(
    AgentExample(
        "Journal",
        "Starts each entry with a few follow-ups, and keeps a Digest of what’s on your mind.",
        "My journal. These words are mine: don’t rewrite or add to what I write unless a rule says so. Keep a note " +
            "titled Digest that sums up my recent entries.",
        linkedMapOf(
            "noteCreated" to Idea(
                "Follow-ups from the Digest",
                "Show me a card at the top with a short list of 2 to 4 follow-ups and questions, no heading. Take " +
                    "them mostly from For now in the Digest (“Cold gone yet?”, “Did the talk happen?”), with one " +
                    "from Lasting now and then. Friendly and direct, like a friend catching up: ask what happened, " +
                    "not how I felt about it. Only if there’s no Digest, ask about my day. Don’t write in the entry.",
                reads = listOf("Digest")
            ),
            "newNoteDone" to Idea(
                "Update the Digest",
                "Update the Digest with this entry, in two sections, a few short lines each. Lasting: realizations " +
                    "about myself, patterns, values, big decisions; things worth knowing about me months from now. " +
                    "Keep these unless I say otherwise. For now: things to follow up on soon (a cold, a deadline, " +
                    "plans for the weekend), each with the date I first mentioned it. Take one out once I say it’s " +
                    "over, or after 2 weeks with no mention. Merge with what’s there and rewrite the whole " +
                    "Digest. If I wrote nothing, change nothing. Create the Digest if there isn’t one.",
                reads = listOf("Digest")
            ),
            "noteDeleted" to DIGEST_TRIM,
            "movedOut" to DIGEST_TRIM
        )
    ),
    AgentExample(
        "Recipes",
        "Turns ingredients into a checklist in metric, tags each recipe, and scales it when you ask.",
        "Recipes I cook. I measure in grams and millilitres.",
        linkedMapOf(
            "newNoteDone" to Idea(
                "Metric checklist, tagged",
                "Turn the ingredients into a checklist, converting cups and ounces to metric. Tag the recipe with " +
                    "its cuisine and main ingredient. Leave the method as written."
            ),
            "menu" to Idea(
                "Scale to 2 servings",
                "Rewrite the quantities for 2 servings, with the originals in brackets.",
                menuLabel = "Scale to 2 servings"
            )
        )
    ),
    AgentExample(
        "Tidy tags",
        "Tags each new note from a small, consistent set, and suggests typo fixes when you ask.",
        "Everyday notes. Keep them findable with a small, consistent set of tags.",
        linkedMapOf(
            "newNoteDone" to Idea(
                "Tag from a small set",
                "Add 1 to 3 tags on a line at the end, reusing the space’s tags whenever one fits. Change nothing " +
                    "else."
            ),
            "menu" to Idea(
                "Suggest typo fixes",
                "Suggest a fix for each spelling mistake and obvious typo, for me to accept or reject. Keep the " +
                    "wording, punctuation and formatting.",
                menuLabel = "Fix typos"
            )
        )
    )
)

/** An idea for a rule, and the example it's from (null: a general one). */
data class IdeaFrom(val idea: Idea, val from: String?)

/** Ideas for a rule: the examples', then a general one for a moment no example covers well. */
fun ideasFor(trigger: String): List<IdeaFrom> {
    val out = ArrayList<IdeaFrom>()
    for (ex in EXAMPLES) {
        val idea = ex.rules[trigger] ?: continue
        if (out.none { it.idea.prompt == idea.prompt }) out += IdeaFrom(idea, ex.name)
    }
    trigger(trigger)?.idea?.let { out += IdeaFrom(it, null) }
    return out
}

/** The notes an example's rules need it to always read. */
fun readsOf(ex: AgentExample): List<String> = ex.rules.values.flatMap { it.reads }.distinct()

private val menuLabels = EXAMPLES.mapNotNullTo(HashSet()) { it.rules["menu"]?.menuLabel }

/** The notes added to those it reads, as many as fit: References offers the rest while a rule needs them. */
private fun reading(refs: List<String>, more: List<String>): List<String> {
    val out = refs.toMutableList()
    for (r in more) if (r !in out && out.size < MAX_REFERENCES) out += r
    return out
}

/**
 * The draft with the idea as its rule for the moment, and the notes it needs read. A command name typed
 * already stays, unless it's another idea's.
 */
fun withIdea(d: AgentDraft, trigger: String, idea: Idea): AgentDraft {
    val name = d.menuLabel.trim()
    val menuLabel = if (trigger == "menu" && idea.menuLabel != null && (name.isEmpty() || name in menuLabels)) {
        idea.menuLabel
    } else {
        d.menuLabel
    }
    return d.copy(
        triggers = d.triggers + (trigger to idea.prompt),
        menuLabel = menuLabel,
        referenceNotes = reading(d.referenceNotes, idea.reads)
    )
}

/** The draft with all of the example: its purpose, and its rules over any for the same moments. Others stay. */
fun withExample(d: AgentDraft, ex: AgentExample): AgentDraft {
    var out = d.copy(prompt = ex.prompt)
    for ((trigger, idea) in ex.rules) {
        out = withIdea(out.copy(menuLabel = if (trigger == "menu") "" else out.menuLabel), trigger, idea)
    }
    return out
}

/** Whether taking the idea would change the draft's rule: not if it has it, as [withIdea] would leave it. */
fun hasIdea(d: AgentDraft, trigger: String, idea: Idea): Boolean {
    val w = withIdea(d, trigger, idea)
    return w.triggers[trigger] == d.triggers[trigger] && w.menuLabel == d.menuLabel
}

/** Notes the draft's rules taken from examples need read, as each was taken (at any moment): an edited one may not. */
fun readsNeeded(d: AgentDraft): List<String> {
    val prompts = d.triggers.values.toSet()
    return EXAMPLES.flatMap { it.rules.values }.filter { it.prompt in prompts }.flatMap { it.reads }.distinct()
}

/** Whether a draft has every part of the example already. */
fun hasExample(d: AgentDraft, ex: AgentExample): Boolean = d.prompt == ex.prompt &&
    ex.rules.all { (t, idea) -> hasIdea(d, t, idea) } &&
    readsOf(ex).all { it in d.referenceNotes }

/** A title as the server keeps one the agent reads: trimmed, each run of spaces one space. */
fun titleOf(t: String): String = t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
