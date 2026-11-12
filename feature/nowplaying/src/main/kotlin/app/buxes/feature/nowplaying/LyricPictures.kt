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
    var photos by remember(artist) { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    LaunchedEffect(artist, title) {
        if (artist.isNullOrBlank()) return@LaunchedEffect
        val files = ArtistPictures.pictures(context, artist, title)
        photos = withContext(Dispatchers.Default) {
            files.mapNotNull { f -> ArtistPictures.decode(f, DECODE_PX)?.let { GlassArt.soften(it, cells = PHOTO_CELLS).asImageBitmap() } }
        }
    }
    // The cover, softened the same way, while the photos come (and when there are none).
    val cover = rememberArtworkState(item?.artworkUri, 160.dp)
    val coverSoft = remember(cover.bitmap) { cover.bitmap?.let { GlassArt.soften(it.asAndroidBitmap(), cells = PHOTO_CELLS).asImageBitmap() } }
    val pictures = if (photos.isNotEmpty()) photos else listOfNotNull(coverSoft)
    if (pictures.isEmpty()) return
    val pick = (line.coerceAtLeast(0)) % pictures.size
    val reduced = BuxesTheme.motion.reduced
    Crossfade(targetState = pick to pictures.size, animationSpec = tween(if (reduced) 0 else TURN_MS), label = "lyricPicture", modifier = modifier.fillMaxSize()) { (which, _) ->
        val bitmap = pictures.getOrNull(which) ?: return@Crossfade
        Image(
            painter = BitmapPainter(bitmap, filterQuality = FilterQuality.Medium),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

/** How much of the photo survives: enough to see a face, soft enough for white words to sit on. */
private const val PHOTO_CELLS = 56
private const val DECODE_PX = 320
private const val TURN_MS = 220
