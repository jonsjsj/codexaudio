package no.bellaybestia.audex.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf

/**
 * Lets a screen take over the app's palette while it's open.
 *
 * The theme normally tints from what you're playing (or last had open), but the
 * mockup tints the *detail* page from the book you're actually looking at — so
 * browsing a book re-colours the app around it. A screen calls [TintFromCover]
 * and the whole shell (including the bottom bar) follows; leaving restores the
 * playing book.
 *
 * The holder is created above the theme and provided to children, so a write
 * from deep in the tree recomposes the theme itself.
 */
val LocalThemeCover = compositionLocalOf<MutableState<String?>> {
    // No provider (e.g. a preview): swallow overrides rather than crash.
    mutableStateOf(null)
}

/** Tint the app from [coverUrl] for as long as this composable is in the tree. */
@Composable
fun TintFromCover(coverUrl: String?) {
    val holder = LocalThemeCover.current
    DisposableEffect(coverUrl) {
        if (coverUrl != null) holder.value = coverUrl
        onDispose { if (holder.value == coverUrl) holder.value = null }
    }
}
