package notes.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import notes.app.ui.LocalTokens
import notes.app.ui.Tokens
import notes.app.ui.Type
import notes.app.ui.themes.NotesTheme as ThemeDefinition
import notes.app.ui.themes.ThemeCheckMark
import notes.app.ui.themes.ThemeCorner
import notes.app.ui.themes.Themes

/** Every theme as a small page set in its own palette, fonts and style; two to a row, as on the web. */
@Composable
internal fun ThemeGallery(selected: ThemeDefinition, onPick: (ThemeDefinition) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (row in Themes.all.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (theme in row) ThemeChoice(theme, theme.id == selected.id, Modifier.weight(1f)) { onPick(theme) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ThemeChoice(theme: ThemeDefinition, picked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalTokens.current
    val page = 14.dp * theme.shape.radiusScale
    // The ring is in the app's theme, the page in the one it shows; it stays concentric with the page.
    val ring = RoundedCornerShape(page + 5.dp)
    Column(modifier.selectable(picked, role = Role.RadioButton, onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .border(2.dp, if (picked) t.tint else Color.Transparent, ring)
                .padding(5.dp)
                .clearAndSetSemantics {}
        ) {
            Preview(theme, Tokens.of(theme, t.dark), RoundedCornerShape(page))
        }
        Spacer(Modifier.height(8.dp))
        Text(theme.name, style = Type.label, color = t.ink, modifier = Modifier.padding(horizontal = 4.dp))
        Text(
            theme.mood,
            style = Type.small.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = t.ink3,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

/** The window, with a note's page set in from the top left: a title, a line with a tag, a ticked task. */
@Composable
private fun Preview(theme: ThemeDefinition, p: Tokens, shape: Shape) {
    val c = theme.colors
    val body = Type.bodyAt(theme, 11f, 15f)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(shape)
            .background(p.bg)
            .border(1.dp, p.hair, shape)
    ) {
        val sheet = RoundedCornerShape(topStart = 10.dp * theme.shape.radiusScale)
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 14.dp, top = 14.dp)
                .clip(sheet)
                .background(c.paper.resolve(p.dark))
                .border(1.dp, p.edge, sheet)
                .padding(start = 12.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Saturday", style = Type.titleAt(theme, 22f), color = p.ink, maxLines = 1, softWrap = false)
            val tagBg = c.tagBackground?.resolve(p.dark) ?: Color.Unspecified
            val tagInk = c.tagInk?.resolve(p.dark) ?: p.ink2
            Text(
                buildAnnotatedString {
                    append("Bread and ")
                    withStyle(SpanStyle(background = tagBg, color = tagInk)) { append("#plums") }
                },
                style = body,
                color = p.ink2,
                maxLines = 1,
                softWrap = false
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Check(theme, p)
                Spacer(Modifier.width(6.dp))
                Text(
                    "Call the bakery",
                    style = body.copy(
                        textDecoration = if (theme.shape.doneStrikethrough) TextDecoration.LineThrough else null
                    ),
                    color = p.ink3,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/** A ticked task's box, as the theme draws it. */
@Composable
private fun Check(theme: ThemeDefinition, p: Tokens) {
    val shape = when (val corner = theme.shape.checkCorner) {
        is ThemeCorner.Radius -> RoundedCornerShape((corner.radius * 10f / 18f).dp)
        else -> CircleShape
    }
    val ink = theme.colors.checkInk.resolve(p.dark)
    Box(Modifier.size(10.dp).clip(shape).background(p.accent), contentAlignment = Alignment.Center) {
        if (theme.shape.checkMark == ThemeCheckMark.Dot) Box(Modifier.size(4.dp).clip(CircleShape).background(ink))
    }
}
