package app.buxes.core.designsystem.artwork

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.buxes.core.designsystem.theme.DisplayImage
import kotlin.math.roundToInt

/**
 * The playing cover as frosted glass (Buxes): the picture brought down to a few pixels a side and
 * drawn back up smoothly, which reads as a soft blur at no cost per frame — the phone blurs once per
 * song, never per frame. [cells] is how many pixels a side survive: fewer is softer.
 */
object GlassArt {
    /** The display's background: the cover still recognisable in shape, its detail gone. */
    fun display(cover: ImageBitmap): DisplayImage {
        val soft = soften(cover.asAndroidBitmap(), cells = 14)
        return DisplayImage(soft.asImageBitmap(), averageLuminance(soft))
    }

    fun soften(source: Bitmap, cells: Int): Bitmap {
        val tiny = Bitmap.createScaledBitmap(source, cells, cells, true)
        // Back up to a modest size with filtering, so the final draw starts from smooth gradients.
        return Bitmap.createScaledBitmap(tiny, cells * 8, cells * 8, true)
    }

    private fun averageLuminance(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        var sum = 0f
        for (y in 0 until 8) for (x in 0 until 8) sum += Color(small.getPixel(x, y)).luminance()
        return sum / 64f
    }
}

/**
 * The cover of [uri], softened, filling this element behind its content (the mini player, D-Buxes):
 * the song's own picture seen through the glass. Draws nothing until the cover is there.
 */
@Composable
fun Modifier.coverGlass(uri: String?, shape: Shape, alpha: Float = 0.7f, cells: Int = 6): Modifier {
    val state = rememberArtworkState(uri, 40.dp)
    val source = state.bitmap ?: state.placeholder
    val soft = remember(source, cells) { source?.let { GlassArt.soften(it.asAndroidBitmap(), cells).asImageBitmap() } }
    if (soft == null) return this
    return drawWithCache {
        val scale = maxOf(size.width / soft.width, size.height / soft.height)
        val srcW = (size.width / scale).roundToInt().coerceIn(1, soft.width)
        val srcH = (size.height / scale).roundToInt().coerceIn(1, soft.height)
        val srcOffset = IntOffset((soft.width - srcW) / 2, (soft.height - srcH) / 2)
        val dst = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
        val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        onDrawBehind {
            clipPath(outline) {
                drawImage(soft, srcOffset, IntSize(srcW, srcH), dstSize = dst, alpha = alpha, filterQuality = FilterQuality.Medium)
            }
        }
    }
}
