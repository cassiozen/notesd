package notes.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import notes.app.R

// --- brand -------------------------------------------------------------------

/** The app icon's tile: a curling sheet on amber (web: .mark). */
@Composable
fun BrandMark(size: Dp = 22.dp) {
    Image(painterResource(R.drawable.brand_mark), contentDescription = null, modifier = Modifier.size(size))
}

@Composable
fun Brand(large: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BrandMark(if (large) 26.dp else 22.dp)
        Text("notesd", style = Type.brand.copy(fontSize = if (large) 24.sp else 20.sp), color = LocalTokens.current.ink)
    }
}

/** A small quiet label above a heading or a group (web: Kicker). */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = LocalTokens.current.ink3) {
    Text(text, style = Type.kicker, color = color, modifier = modifier)
}

@Composable
fun Heading(text: String, modifier: Modifier = Modifier, style: TextStyle = Type.heading) {
    Text(text, style = style, color = LocalTokens.current.ink, modifier = modifier.semantics { heading() })
}

// --- buttons -----------------------------------------------------------------

enum class ButtonKind {
    Solid,
    Accent,
    Chip,
    Ghost,
    Danger,
    DangerText,

    /** Filled: the go-ahead inside a confirmation that's already on the danger tint. */
    DangerSolid
}

/** The web's buttons: 50dp tall, rounded 16, pressed ones sink. */
@Composable
fun NotesButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Solid,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: ImageVector? = null,
    small: Boolean = false
) {
    val t = LocalTokens.current
    val (bg, fg) = when (kind) {
        ButtonKind.Solid -> t.solid to t.onSolid
        ButtonKind.Accent -> t.accent to t.onAccent
        ButtonKind.Chip -> t.chip to t.ink
        ButtonKind.Ghost -> Color.Transparent to t.ink2
        ButtonKind.Danger -> t.dangerBg to t.danger
        ButtonKind.DangerText -> Color.Transparent to t.danger
        ButtonKind.DangerSolid -> t.danger to t.bg
    }
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(if (small) 12.dp else 16.dp)
    val alpha by animateFloatAsState(if (enabled || busy) 1f else 0.5f, label = "enabled")
    Box(
        modifier
            .heightIn(min = if (small) 40.dp else 50.dp)
            .pressScale(interaction)
            .clip(shape)
            .background(bg.copy(alpha = bg.alpha * alpha))
            .then(if (kind == ButtonKind.Chip) Modifier.border(1.dp, t.edge, shape) else Modifier)
            .then(
                if (kind == ButtonKind.Accent) {
                    Modifier.drawBehind {
                        drawRect(t.accentDeep, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
                    }
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = fg),
                enabled = enabled && !busy,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = if (small) 14.dp else 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), color = fg, strokeWidth = 2.dp)
            } else if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp), tint = fg.copy(alpha = fg.alpha * alpha))
            }
            Text(
                label,
                style = if (small) Type.button.copy(fontSize = 14.sp) else Type.button,
                fontWeight = if (kind == ButtonKind.Ghost || kind == ButtonKind.Chip) FontWeight.Medium else null,
                color = fg.copy(alpha = fg.alpha * alpha),
                maxLines = 1
            )
        }
    }
}

/** A 48dp round target with a line icon, neutral unless [tint] says otherwise. */
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalTokens.current.ink2,
    enabled: Boolean = true,
    iconSize: Dp = 22.dp
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(48.dp)
            .pressScale(interaction, 0.9f)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                enabled = enabled,
                role = Role.Button,
                onClickLabel = null,
                onClick = onClick
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        val c by animateColorAsState(
            if (enabled) tint else LocalTokens.current.ink3.copy(alpha = 0.45f),
            label = "tint"
        )
        Icon(icon, null, Modifier.size(iconSize), tint = c)
    }
}

// --- fields ------------------------------------------------------------------

/**
 * The web's field: a label above a 52dp rounded input, a neutral ring when
 * focused, the error or hint below. Secret fields get a Show/Hide toggle.
 */
@Composable
fun NotesField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    hint: String? = null,
    secret: Boolean = false,
    mono: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    contentType: ContentType? = null,
    /** False: grows line by line from [minLines], for a prompt or anything else written at length. */
    singleLine: Boolean = true,
    minLines: Int = 1
) {
    val t = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var revealed by rememberSaveable { mutableStateOf(false) }
    val border by animateColorAsState(
        when {
            error != null -> t.danger
            focused -> t.ink3
            else -> t.hair
        },
        label = "border"
    )
    val ring by animateFloatAsState(if (focused) 1f else 0f, label = "ring")
    val shape = RoundedCornerShape(14.dp)
    val textStyle = (if (mono) Type.mono.copy(fontSize = 15.sp) else Type.body.copy(fontSize = 16.sp))
        .copy(color = if (enabled) t.ink else t.ink2)
    // Label, input and message are all drawn by the text field itself, so they're one node: TalkBack reads
    // "Password, edit box" and a tap on the label focuses the input.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = textStyle,
        cursorBrush = SolidColor(t.tint),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = interaction,
        visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                if (contentType != null) this.contentType = contentType
                if (error != null) error(error)
            },
        decorationBox = { inner ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = Type.label, color = t.ink2)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .drawBehind {
                            if (ring > 0f) {
                                val w = 3.dp.toPx() * ring
                                drawRoundRect(
                                    t.focus.copy(alpha = t.focus.alpha * ring),
                                    topLeft = Offset(-w, -w),
                                    size = Size(size.width + 2 * w, size.height + 2 * w),
                                    cornerRadius = CornerRadius(14.dp.toPx() + w)
                                )
                            }
                        }
                        .clip(shape)
                        .background(t.bg)
                        .background(t.field)
                        .border(BorderStroke(1.dp, border), shape)
                        .padding(start = 16.dp, end = if (secret) 4.dp else 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                placeholder,
                                style = textStyle,
                                color = t.ink3,
                                maxLines = if (singleLine) 1 else minLines
                            )
                        }
                        inner()
                    }
                    if (secret) {
                        Text(
                            if (revealed) "Hide" else "Show",
                            style = Type.label,
                            color = t.link,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(role = Role.Button) { revealed = !revealed }
                                .semantics {
                                    contentDescription = if (revealed) "Hide password" else "Show password"
                                }
                                .padding(horizontal = 12.dp, vertical = 15.dp)
                        )
                    }
                }
                AnimatedVisibility(error != null || hint != null, enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        error ?: hint.orEmpty(),
                        style = Type.small,
                        color = if (error != null) t.danger else t.ink3,
                        modifier = Modifier.semantics { if (error != null) liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        }
    )
}

// --- banners -----------------------------------------------------------------

enum class BannerKind { Warn, Error, Info }

/** A rounded, tinted notice with an optional action (web: Banner). */
@Composable
fun Banner(
    text: String,
    kind: BannerKind,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    /** Before [action], the lesser one: Dismiss beside Open. */
    secondary: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    val t = LocalTokens.current
    val (bg, edge, ink) = when (kind) {
        BannerKind.Warn -> Triple(t.warnBg, t.warnEdge, t.warnInk)
        BannerKind.Error -> Triple(t.errBg, t.errEdge, t.errInk)
        BannerKind.Info -> Triple(t.chip, t.edge, t.ink2)
    }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, edge, shape)
            .padding(start = 16.dp, end = if (action != null) 6.dp else 16.dp)
            .heightIn(min = 52.dp)
            .semantics(mergeDescendants = action == null) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = Type.small.copy(fontSize = 14.sp),
            color = ink,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp)
        )
        if (secondary != null && onSecondary != null) {
            Text(
                secondary,
                style = Type.label.copy(fontSize = 14.sp),
                color = ink,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onSecondary)
                    .padding(horizontal = 12.dp, vertical = 15.dp)
            )
        }
        if (action != null && onAction != null) {
            Text(
                action,
                style = Type.label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = ink,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 15.dp)
            )
        }
    }
}

/** Shows [content] with a soft expand, hides it with a collapse. */
@Composable
fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(200)) + expandVertically(springPop()),
        exit = fadeOut(tween(150)) + shrinkVertically(springSlide(reduce = false))
    ) { content() }
}

// --- onboarding bits ---------------------------------------------------------

/** Progress pills: done ones solid, the current one wider (web: Steps). */
@Composable
fun Steps(count: Int, current: Int, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Row(
        modifier.clearAndSetSemantics { stateDescription = "Step ${current + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until count) {
            val width by animateFloatAsState(if (i == current) 28f else 12f, tween(300, easing = EaseOut), label = "w")
            val color by animateColorAsState(if (i <= current) t.solid else t.hairStrong, tween(300), label = "c")
            Box(Modifier.height(4.dp).width(width.dp).clip(CircleShape).background(color))
        }
    }
}

private val strengthWords = listOf("Empty", "Weak", "Fair", "Good", "Strong")

/** Rough 0–4 strength score; guidance only, the server just enforces length (web: passwordScore). */
fun passwordScore(pw: String): Int {
    if (pw.isEmpty()) return 0
    return listOf(
        pw.length >= 8,
        pw.length >= 12,
        pw.any { it.isUpperCase() } && pw.any { it.isLowerCase() },
        pw.any { it.isDigit() || !it.isLetterOrDigit() }
    ).count { it }
}

@Composable
fun PasswordMeter(password: String, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val score = passwordScore(password)
    val fraction by animateFloatAsState(score / 4f, tween(250), label = "meter")
    val color by animateColorAsState(
        when {
            score < 2 -> t.meterWeak
            score < 3 -> t.meterFair
            else -> t.meterStrong
        },
        label = "meterColor"
    )
    Row(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = "Password strength"
            stateDescription = strengthWords[score]
            progressBarRangeInfo = ProgressBarRangeInfo(score.toFloat(), 0f..4f, 4)
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(t.hair)) {
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(CircleShape).background(color))
        }
        Text(
            if (score ==
                0
            ) {
                ""
            } else {
                strengthWords[score]
            },
            style = Type.small,
            color = t.ink3,
            modifier = Modifier.widthIn(min = 44.dp)
        )
    }
}

// --- backdrop ----------------------------------------------------------------

/**
 * The web's faint warm wash behind the window. A radial gradient rather than
 * a blurred circle: the same look without a blur pass on every frame.
 */
@Composable
fun Backdrop(modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.1f, -size.height * 0.02f)
            val radius = size.width
            drawCircle(
                Brush.radialGradient(
                    0f to t.blob1.copy(alpha = t.blobOpacity),
                    0.45f to t.blob1.copy(alpha = t.blobOpacity * 0.55f),
                    1f to t.blob1.copy(alpha = 0f),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }
    }
}

/** The tinted "‹ Label" back link at the top of a screen, named after where it goes; just "‹" without a [label]. */
@Composable
fun BackLink(label: String?, onClick: () -> Unit, modifier: Modifier = Modifier, description: String = "Back") {
    val t = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .heightIn(min = 48.dp)
            .pressScale(interaction, 0.94f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(interaction, ripple(), role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(start = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Back, null, Modifier.size(22.dp), tint = t.ink2)
        if (label != null) Text(label, style = Type.body.copy(fontSize = 16.sp), color = t.ink2)
    }
}
