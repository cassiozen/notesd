package notes.app

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertTrue

/**
 * Walks the screen's accessibility tree: everything tappable has something for TalkBack to say, and a target of at
 * least 48dp. Compose's semantics, and the native views under [views] (which Compose's tree doesn't hold).
 */
fun ComposeTestRule.auditAccessibility(screen: String, minControls: Int = 3, views: View? = null) {
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    val density = ApplicationProvider.getApplicationContext<NotesApp>().resources.displayMetrics.density
    val problems = mutableListOf<String>()
    var controls = 0
    fun check(name: String?, w: Float, h: Float, at: Any) {
        controls++
        if (name.isNullOrBlank()) {
            problems +=
                "unlabelled control at ${(at as? SemanticsNode)?.let { "${it.boundsInRoot} ${it.config}" } ?: at}"
        }
        // The platform extends small targets to 48dp: allow a hair under.
        if (w < 47.5f || h < 47.5f) problems += "“$name” is ${w.toInt()}×${h.toInt()}dp"
    }
    fun visit(node: SemanticsNode) {
        val config = node.config
        // Scrolled out of view: audited where a test scrolls to it.
        val onScreen = node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f
        if (onScreen && config.getOrNull(SemanticsActions.OnClick) != null) {
            val text = config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
            val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
            // Rows of text inside a larger target (a sheet item's label) report their own bounds.
            check(
                (description + text).ifBlank { null },
                node.touchBoundsInRoot.width / density,
                node.touchBoundsInRoot.height / density,
                node
            )
        }
        node.children.forEach(::visit)
    }
    // The screen, and any sheet, dialog or toast over it.
    onAllNodes(isRoot()).fetchSemanticsNodes().forEach(::visit)
    fun visit(view: View) {
        if (!view.isShown) return
        if (view.isClickable) {
            val name = view.contentDescription?.toString() ?: (view as? TextView)?.text?.toString()
            check(name, view.width / density, view.height / density, view)
        }
        (view as? ViewGroup)?.let { g -> for (i in 0 until g.childCount) visit(g.getChildAt(i)) }
    }
    views?.let(::visit)
    println("$screen: $controls controls")
    assertTrue(controls >= minControls, "$screen: the audit found only $controls controls")
    assertTrue(problems.isEmpty(), "$screen:\n" + problems.joinToString("\n"))
}
