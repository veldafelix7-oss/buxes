package app.buxes.feature.nowplaying

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.buxes.core.designsystem.artwork.GlassArt
import app.buxes.core.designsystem.artwork.rememberArtworkState
import app.buxes.core.designsystem.theme.BuxesTheme
import app.buxes.player.api.PlaybackController
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The pictures behind the lyrics (Buxes): the playing song's cover and the covers of the next songs
 * in the queue — on YouTube Music, songs the catalogue put beside it — each softened to frosted
 * glass once, and turned with the lines: a new line, the next picture, in one crossfade with the
 * words. Nothing is shown before it has loaded, so the display's own cover glass (always beneath)
 * is all that ever shows through — never the bare blue.
 */
@Composable
internal fun LyricPictures(controller: PlaybackController, line: Int, modifier: Modifier = Modifier) {
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val queue by controller.queue.collectAsStateWithLifecycle()
    val current = snapshot.item?.artworkUri
    // The current cover first, then the next distinct covers in the queue, at most four in all.
    val uris = remember(current, queue.entries, queue.currentIndex) {
        buildList {
            current?.let { add(it) }
            val entries = queue.entries
            if (entries.isNotEmpty()) {
                val start = (queue.currentIndex + 1).coerceAtLeast(0)
                for (i in 0 until entries.size) {
                    val uri = entries[(start + i) % entries.size].artworkUri ?: continue
                    if (uri !in this) add(uri)
                    if (size >= MAX_PICTURES) break
                }
            }
        }
    }
    if (uris.isEmpty()) return
    // Each picture softened once, kept while the song plays.
    val soft = uris.map { uri -> softened(uri) }
    val ready = soft.mapIndexedNotNull { i, b -> b?.let { i to it } }
    if (ready.isEmpty()) return
    // The line picks among the pictures that are there; a picture still loading is skipped.
    val pick = ready[((line.coerceAtLeast(0)) % ready.size)]
    val reduced = BuxesTheme.motion.reduced
    Crossfade(targetState = pick.first, animationSpec = tween(if (reduced) 0 else TURN_MS), label = "lyricPicture", modifier = modifier.fillMaxSize()) { which ->
        val bitmap = soft.getOrNull(which) ?: return@Crossfade
        Image(
            painter = BitmapPainter(bitmap, filterQuality = FilterQuality.Medium),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = PICTURE_ALPHA,
        )
    }
}

@Composable
private fun softened(uri: String): ImageBitmap? {
    val state = rememberArtworkState(uri, 96.dp)
    var soft by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(state.bitmap) {
        val source = state.bitmap ?: return@LaunchedEffect
        soft = GlassArt.soften(source.asAndroidBitmap(), cells = 14).asImageBitmap()
    }
    return soft
}

private const val MAX_PICTURES = 4
private const val TURN_MS = 650
private const val PICTURE_ALPHA = 0.9f
