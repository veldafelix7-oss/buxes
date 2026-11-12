package app.buxes.feature.nowplaying

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.buxes.core.designsystem.artwork.GlassArt
import app.buxes.core.designsystem.artwork.rememberArtworkState
import app.buxes.core.designsystem.theme.BuxesTheme
import app.buxes.player.api.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pictures behind the lyrics (Buxes): photos of the artist found on Wikipedia
 * ([ArtistPictures]), each softened a little once, turned with the lines — a new line, the next
 * photo, in one quick crossfade with the words. Until the photos are there (or when there are
 * none: a local song, offline), the song's cover stands in; the display's cover glass is always
 * beneath, so the bare blue never shows.
 */
@Composable
internal fun LyricPictures(controller: PlaybackController, line: Int, modifier: Modifier = Modifier) {
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val item = snapshot.item
    val context = LocalContext.current
    val artist = item?.artistDisplay
    val title = item?.title
    // The files arrive as the sources answer; each new one is softened once and joins the turn.
    val files by remember(artist) { ArtistPictures.pictures(context, artist, title) }.collectAsStateWithLifecycle()
    var photos by remember(artist) { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    LaunchedEffect(files) {
        if (files.size <= photos.size) return@LaunchedEffect
        val fresh = files.drop(photos.size)
        val more = withContext(Dispatchers.Default) {
            fresh.mapNotNull { f -> ArtistPictures.decode(f, DECODE_PX)?.let { GlassArt.soften(it, cells = PHOTO_CELLS).asImageBitmap() } }
        }
        photos = photos + more
    }
    // The cover, softened the same way, while the photos come (and when there are none).
    val cover = rememberArtworkState(item?.artworkUri, 160.dp)
    val coverSoft = remember(cover.bitmap) { cover.bitmap?.let { GlassArt.soften(it.asAndroidBitmap(), cells = PHOTO_CELLS).asImageBitmap() } }
    val pictures = if (photos.isNotEmpty()) photos else listOfNotNull(coverSoft)
    if (pictures.isEmpty()) return
    // The order: a shuffled round through all the pictures, reshuffled when the round is done, and
    // never the same picture twice in a row — so 1-2-3-4 is followed by 3-1-4-2, not 1-2-3-4 again.
    val order = remember(artist) { PictureOrder() }
    val pick = order.at(line.coerceAtLeast(0), pictures.size)
    val reduced = BuxesTheme.motion.reduced
    Crossfade(targetState = pick, animationSpec = tween(if (reduced) 0 else TURN_MS), label = "lyricPicture", modifier = modifier.fillMaxSize()) { which ->
        val bitmap = pictures.getOrNull(which) ?: pictures.last()
        Image(
            painter = BitmapPainter(bitmap, filterQuality = FilterQuality.Medium),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

/** Remembers which picture each line showed, dealing a fresh shuffled round as lines go on. */
private class PictureOrder {
    private val dealt = ArrayList<Int>()
    private val random = java.util.Random()

    fun at(line: Int, count: Int): Int {
        if (count <= 1) return 0
        while (dealt.size <= line) {
            val round = (0 until count).shuffled(random).toMutableList()
            // Not the same picture as the last of the previous round.
            val last = dealt.lastOrNull()
            if (last != null && round.first() == last) java.util.Collections.swap(round, 0, round.size - 1)
            dealt += round
        }
        // More pictures arrived since: lines already dealt keep their picture if it still exists.
        return dealt[line].coerceAtMost(count - 1)
    }
}

/** How much of the photo survives: enough to see a face, soft enough for white words to sit on. */
private const val PHOTO_CELLS = 56
private const val DECODE_PX = 320
private const val TURN_MS = 220
