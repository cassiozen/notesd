package notes.app.editor

import android.graphics.Rect
import android.view.View
import androidx.core.widget.NestedScrollView

/**
 * Keeps [editor]'s cursor in view when the scroller gets shorter (the keyboard
 * or the toolbars coming up), or its current find match while finding.
 * NestedScrollView alone only keeps the focused view on screen, and here
 * that's the whole note.
 */
fun NestedScrollView.keepCursorInView(editor: NoteEditText) {
    addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
        if (bottom - top >= oldBottom - oldTop) return@addOnLayoutChangeListener
        if (editor.finding) {
            editor.revealMatch()
        } else if (editor.hasFocus()) {
            editor.revealCursor()
        }
    }
}

private fun NoteEditText.revealCursor() {
    val layout = layout ?: return
    val line = layout.getLineForOffset(selectionEnd)
    // A line of room below, so the cursor doesn't sit against the keyboard's toolbar.
    val below = layout.getLineBottom(line) - layout.getLineTop(line)
    val rect = Rect(0, layout.getLineTop(line), width, layout.getLineBottom(line) + below)
    rect.offset(0, totalPaddingTop)
    // Immediate: the keyboard animates in a frame at a time, and a smooth scroll would trail it.
    requestRectangleOnScreen(rect, true)
}

/**
 * Scrolls the current find match to the middle of the scroller, unless it's
 * already in view: stepping through matches on screen doesn't move the note.
 */
fun NoteEditText.revealMatch() {
    val m = currentMatch() ?: return
    val layout = layout ?: return
    // The scroller's height: the editor sits in a frame with its cards' layer.
    val viewport = generateSequence(parent) { it.parent }.filterIsInstance<NestedScrollView>().firstOrNull()?.height
        ?: return
    val top = layout.getLineTop(layout.getLineForOffset(m.start)) + totalPaddingTop
    val bottom = layout.getLineBottom(layout.getLineForOffset(m.end)) + totalPaddingTop
    val visible = Rect()
    if (getLocalVisibleRect(visible) && top >= visible.top && bottom <= visible.bottom) return
    val room = maxOf(0, (viewport - (bottom - top)) / 2)
    // Immediate, as find does in browsers: a long smooth scroll per match would lag repeated taps.
    requestRectangleOnScreen(Rect(0, top - room, width, bottom + room), true)
}
