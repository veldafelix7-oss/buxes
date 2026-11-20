package app.buxes.feature.nowplaying

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import app.buxes.core.designsystem.type.MoodFaces
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A word cut from magazines (Buxes): each letter on its own scrap of coloured paper, in its own
 * face, a little askew — the ransom-note look, for the hot and shouted words. Built once per line
 * (everything decided from the word and its place, so it never changes while it shows) and drawn
 * with plain rectangles and text: nothing heavy.
 */
internal class PaperWord private constructor(
    val letters: List<Letter>,
    val width: Float,
    val height: Float,
) {
    class Letter(
        val text: TextLayoutResult,
        val x: Float,
        val tile: Size,
        val rotation: Float,
        val paper: Color,
        val lift: Float,
    )

    fun draw(scope: DrawScope, topLeft: Offset, alpha: Float) = with(scope) {
        for (l in letters) {
            val origin = Offset(topLeft.x + l.x, topLeft.y + l.lift)
            val centre = Offset(origin.x + l.tile.width / 2f, origin.y + l.tile.height / 2f)
            rotate(l.rotation, pivot = centre) {
                // A soft shadow under the scrap, then the paper, then the letter.
                drawRect(Color.Black.copy(alpha = 0.28f * alpha), Offset(origin.x + 2f, origin.y + 3f), l.tile)
                drawRect(l.paper.copy(alpha = l.paper.alpha * alpha), origin, l.tile)
                drawText(l.text, topLeft = Offset(origin.x + (l.tile.width - l.text.size.width) / 2f, origin.y + (l.tile.height - l.text.size.height) / 2f), alpha = alpha)
            }
        }
    }

    companion object {
        private val papers = listOf(
            Color(0xFFF3E9D2) to Color(0xFF1B1B1B), // cream, black ink
            Color(0xFFE53935) to Color(0xFFFFF8E1), // red, cream ink
            Color(0xFF1E3A8A) to Color(0xFFFFFFFF), // navy, white ink
            Color(0xFFF9D342) to Color(0xFF1B1B1B), // yellow, black ink
            Color(0xFF1B1B1B) to Color(0xFFF3E9D2), // black, cream ink
            Color(0xFFFFFFFF) to Color(0xFFD32F2F), // white, red ink
            Color(0xFFF48FB1) to Color(0xFF1B1B1B), // pink, black ink
            Color(0xFF26A69A) to Color(0xFFFFFFFF), // teal, white ink
            Color(0xFFFF8F00) to Color(0xFF1B1B1B), // orange, black ink
        )

        /** Lays [word] out at [fontPx], its letters' faces and papers drawn from [seed]. */
        fun build(word: String, fontPx: Float, base: TextStyle, measurer: TextMeasurer, seed: Int): PaperWord {
            var x = 0f
            var tallest = 0f
            val gap = fontPx * 0.06f
            val padX = fontPx * 0.14f
            val padY = fontPx * 0.04f
            val letters = ArrayList<Letter>()
            var h = seed * 31 + word.hashCode()
            fun next(): Int { h = h * 1103515245 + 12345; return (h ushr 8) and 0xFFFFFF }
            word.forEachIndexed { i, ch ->
                val r1 = next(); val r2 = next(); val r3 = next(); val r4 = next()
                val face = MoodFaces.Paper[r1 % MoodFaces.Paper.size]
                val (paper, ink) = papers[r2 % papers.size]
                // Some scraps carry a lower-case letter, like a real cut-out mix.
                val glyph = if (r3 % 4 == 0) ch.lowercaseChar().toString() else ch.uppercaseChar().toString()
                val style = base.copy(
                    fontFamily = face,
                    fontWeight = FontWeight.Bold,
                    fontSize = base.fontSize * (0.9f + (r4 % 21) / 100f),
                    color = ink,
                )
                val text = measurer.measure(glyph, style, softWrap = false)
                val tile = Size(text.size.width + padX * 2, text.size.height + padY * 2)
                val rotation = ((r1 % 15) - 7).toFloat()
                val lift = ((r2 % 7) - 3) * fontPx * 0.02f
                letters += Letter(text, x, tile, rotation, paper, lift)
                // Room for the tilt, so neighbours don't overlap more than a scrap would.
                val tilted = tile.width * abs(cos(Math.toRadians(rotation.toDouble()))).toFloat() + tile.height * abs(sin(Math.toRadians(rotation.toDouble()))).toFloat()
                x += maxOf(tile.width, tilted * 0.96f) + gap
                tallest = maxOf(tallest, tile.height)
            }
            return PaperWord(letters, (x - gap).coerceAtLeast(0f), tallest)
        }
    }
}
