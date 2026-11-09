package notes.app.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.account.Account
import notes.app.account.Avatars
import notes.app.account.Profile
import notes.app.images.ImageException
import notes.app.images.preparePhoto
import notes.app.ui.ButtonKind
import notes.app.ui.Icons
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.NotesDialog
import notes.app.ui.NotesField
import notes.app.ui.Type
import notes.core.api.ApiResult
import notes.core.api.Person

/**
 * Someone's photo, or the first letter of [name] without one, inside [modifier]'s circle. A photo that can't be
 * had (offline, never downloaded) shows the letter. [preview]: a photo not yet saved.
 */
@Composable
internal fun Avatar(
    avatars: Avatars,
    userId: Long,
    version: String,
    name: String,
    modifier: Modifier,
    textStyle: TextStyle,
    textColor: Color,
    preview: ImageBitmap? = null
) {
    val photo = rememberPhoto(avatars, userId, version)
    Box(modifier, contentAlignment = Alignment.Center) {
        val shown = preview ?: photo
        if (shown != null) {
            Image(shown, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(initial(name), style = textStyle, color = textColor)
        }
    }
}

/** [userId]'s photo at [version]: at once if in memory, else once read or downloaded; null without one. */
@Composable
internal fun rememberPhoto(avatars: Avatars, userId: Long, version: String): ImageBitmap? {
    val photo by produceState(avatars.cached(userId, version), userId, version) {
        value = avatars.load(userId, version)
    }
    return photo
}

/** An avatar's circle: [fill] behind the letter, and an [edge] if given. */
internal fun Modifier.clipCircle(fill: Color, edge: Color? = null): Modifier = clip(CircleShape)
    .background(fill)
    .then(if (edge != null) Modifier.border(1.dp, edge, CircleShape) else Modifier)

internal fun initial(name: String): String =
    name.trim().let { if (it.isEmpty()) "?" else it.substring(0, it.offsetByCodePoints(0, 1)).uppercase() }

/** Name and photo, a line of Server: optional, and never asked for anywhere else once signed in. */
@Composable
internal fun ProfileLine(graph: AppGraph, account: Account.SignedIn) {
    val t = LocalTokens.current
    var editing by remember { mutableStateOf(false) }
    val profile = account.profile
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClickLabel = "Edit name and photo") { editing = true }
            .semantics(mergeDescendants = true) {}
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Name and photo", style = Type.rowTitle, color = t.ink)
            Text(
                if (profile.displayName.isNotEmpty()) {
                    "Shown as ${profile.displayName}"
                } else {
                    "Optional. Without a name, you're shown as ${account.username}."
                },
                style = Type.rowMeta,
                color = t.ink2
            )
        }
        Icon(Icons.Chevron, null, Modifier.size(16.dp), tint = t.ink3)
    }
    if (editing) ProfileDialog(graph, account) { editing = false }
}

/** A photo chosen here, not yet saved; or the saved one to be removed. */
private sealed interface Photo {
    class Chosen(val bytes: ByteArray, val bitmap: ImageBitmap) : Photo

    data object Remove : Photo
}

@Composable
private fun ProfileDialog(graph: AppGraph, account: Account.SignedIn, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val profile = account.profile
    // The name as this dialog last saw it saved: a refresh while open mustn't turn an untouched name into a change.
    var savedName by remember { mutableStateOf(profile.displayName) }
    var name by remember { mutableStateOf(savedName) }
    var photo by remember { mutableStateOf<Photo?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var photoError by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        photoError = null
        preparing = true
        scope.launch {
            try {
                val bytes = preparePhoto(context.contentResolver, uri)
                photo = Photo.Chosen(bytes, BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap())
            } catch (e: ImageException) {
                photoError = e.message
            } finally {
                preparing = false
            }
        }
    }

    fun save() {
        if (busy || preparing) return
        busy = true
        nameError = null
        photoError = null
        formError = null
        scope.launch {
            val api = graph.api()
            // What was saved before a later step failed still shows.
            var saved: Person? = null
            var failed: ApiResult<*>? = null
            suspend fun step(call: suspend () -> ApiResult<Person>?): Boolean {
                when (val r = call() ?: ApiResult.Unauthenticated) {
                    is ApiResult.Ok -> saved = r.value
                    else -> failed = r
                }
                return failed == null
            }
            if (name.trim() != savedName && step { api?.setDisplayName(name.trim()) }) {
                saved?.let { savedName = it.displayName }
            }
            when (val p = photo) {
                is Photo.Chosen -> if (failed == null && step { api?.setAvatar(p.bytes) }) {
                    saved?.let { graph.avatars.put(it.id, it.avatar, p.bytes) }
                }

                Photo.Remove -> if (failed == null && step { api?.removeAvatar() }) graph.avatars.forget(profile.userId)

                null -> Unit
            }
            saved?.let {
                val now = Profile(it.id, it.displayName, it.avatar)
                graph.accounts.profileChanged(account.server, account.username, now)
            }
            busy = false
            val r = failed ?: return@launch onDismiss()
            when {
                r is ApiResult.Rejected && r.field == "displayName" ->
                    nameError = "Use at most 64 characters, on one line."

                r is ApiResult.Rejected && (r.field == "avatar" || r.status == 413 || r.status == 415) ->
                    photoError = "The server didn't take that photo. Try another."

                r is ApiResult.Unreachable -> formError = "Can't reach the server."

                else -> formError = "Something went wrong. Try again."
            }
        }
    }

    val hasPhoto = photo is Photo.Chosen || (photo == null && profile.avatar.isNotEmpty())
    NotesDialog({ if (!busy) onDismiss() }, "Name and photo") {
        ScrollingBody {
            Text(
                "Both are optional. Everyone on this server sees them.",
                style = Type.body,
                color = t.ink2
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(
                    graph.avatars,
                    profile.userId,
                    if (photo == Photo.Remove) "" else profile.avatar,
                    name.trim().ifEmpty { account.username },
                    Modifier.size(72.dp).clipCircle(t.hover, t.edge),
                    Type.title.copy(fontSize = 32.sp),
                    t.ink,
                    preview = (photo as? Photo.Chosen)?.bitmap
                )
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NotesButton(
                        if (hasPhoto) "Change photo…" else "Choose photo…",
                        { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        kind = ButtonKind.Chip,
                        enabled = !busy && !preparing,
                        small = true
                    )
                    if (hasPhoto) {
                        NotesButton(
                            "Remove photo",
                            { photo = if (profile.avatar.isNotEmpty()) Photo.Remove else null },
                            kind = ButtonKind.Chip,
                            enabled = !busy,
                            small = true
                        )
                    }
                }
            }
            photoError?.let { FormError(it) }
            Spacer(Modifier.height(16.dp))
            NotesField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = null
                },
                label = "Name",
                error = nameError,
                hint = "Shown instead of your username, ${account.username}, which you still sign in with.",
                enabled = !busy
            )
            formError?.let { FormError(it) }
            DialogActions(onDismiss, if (busy) "Saving…" else "Save", ::save, busy, enabled = !preparing)
        }
    }
}
