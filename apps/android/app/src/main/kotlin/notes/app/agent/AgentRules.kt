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

/** A rule to start from. [title]: what it does, in a few words. */
data class Idea(val title: String, val prompt: String)

/**
 * A moment the agent can act on. [label]: its name, as Activity shows it. [rule]: a rule's heading, always
 * "When …". [whenText]: exactly when it happens. [idea]: one to start from, offered while the rule is empty.
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

/** A whole agent to start from: its purpose and rules, by moment. [menuLabel]: its menu rule's command name. */
data class AgentExample(
    val name: String,
    val prompt: String,
    val rules: Map<String, String>,
    val menuLabel: String = ""
)

// From the agents guide, as the web's agent-examples.ts.
val EXAMPLES: List<AgentExample> = listOf(
    AgentExample(
        "Tidy tags",
        "Everyday notes. Keep them findable with a small, consistent set of tags.",
        linkedMapOf(
            "newNoteDone" to "Add 1 to 3 tags on a line at the end, reusing the space’s tags whenever one fits. " +
                "Change nothing else.",
            "menu" to "Suggest a fix for each spelling mistake and obvious typo, for me to accept or reject. Keep " +
                "the wording, punctuation and formatting."
        ),
        menuLabel = "Fix typos"
    ),
    AgentExample(
        "Recipes",
        "Recipes I cook. I measure in grams and millilitres.",
        linkedMapOf(
            "newNoteDone" to "Turn the ingredients into a checklist, converting cups and ounces to metric. Tag the " +
                "recipe with its cuisine and main ingredient. Leave the method as written.",
            "menu" to "Rewrite the quantities for 2 servings, with the originals in brackets."
        ),
        menuLabel = "Scale to 2 servings"
    )
)

/** Nothing written yet to start from an example over: no purpose, no rules. */
fun isBlank(d: AgentDraft): Boolean = d.prompt.isBlank() && d.triggers.isEmpty()

/** The draft with the example's purpose and rules. Only offered while blank, so there's nothing of mine to keep. */
fun withExample(d: AgentDraft, ex: AgentExample): AgentDraft =
    d.copy(prompt = ex.prompt, triggers = ex.rules, menuLabel = ex.menuLabel)

/** A title as the server keeps one the agent reads: trimmed, each run of spaces one space. */
fun titleOf(t: String): String = t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
