package app.buxes.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.buxes.core.designsystem.artwork.ArtworkImage
import app.buxes.core.designsystem.artwork.coverGlass
import app.buxes.core.designsystem.glass.GlassMaterial
import app.buxes.core.designsystem.glass.glass
import app.buxes.core.designsystem.theme.BuxesTheme
import app.buxes.core.designsystem.type.BuxesText
import app.buxes.core.interaction.rememberBuxesHaptics
import kotlin.math.abs
import kotlin.math.sin

/** The Island's height, for the screens that leave room under it. */
val IslandHeight = 40.dp

/**
 * The Island (Buxes): a dark glass capsule at the top of the display with the cover, the title and
 * three bars that move with the music. A tap opens Now Playing; a swipe to the left skips ahead, to
 * the right goes back. Its bars only move while [isPlaying], and not at all when motion is reduced.
 */
@Composable
fun Island(
    title: String,
    subtitle: String,
    artworkUri: String?,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BuxesTheme.colors
    val type = BuxesTheme.type
    val motion = BuxesTheme.motion
    val haptics = rememberBuxesHaptics()
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .height(IslandHeight)
            .widthIn(min = 180.dp, max = 300.dp)
            .pressFeedback(0.97f)
            .glass(GlassMaterial.Floating, shape)
            .then(if (colors.isIndustrial || colors.isTonal) Modifier else Modifier.coverGlass(artworkUri, shape))
            .pointerInput(onNext, onPrevious) {
                var travel = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travel = 0f },
                    onHorizontalDrag = { _, dx -> travel += dx },
                    onDragEnd = {
                        val edge = 48.dp.toPx()
                        if (abs(travel) > edge) {
                            haptics.skip(forward = travel < 0)
                            if (travel < 0) onNext() else onPrevious()
                        }
                    },
                )
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                haptics.navigate(forward = true)
                onOpen()
            }
            .semantics { contentDescription = "Island: $title, $subtitle. Opens Now Playing; swipe to skip" },
    ) {
        Row(Modifier.fillMaxSize().padding(start = 5.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtworkImage(artworkUri, 30.dp, fallbackText = title, corner = 15.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.Center) {
                BuxesText(title, type.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), colors.labelPrimary, maxLines = 1)
                BuxesText(subtitle, type.caption, colors.labelSecondary, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            IslandBars(isPlaying && !motion.reduced, colors.labelPrimary)
        }
    }
}

/** Three thin bars that rise and fall out of step, like a level meter; still when not [live]. */
@Composable
fun IslandBars(live: Boolean, color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "island bars")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Canvas(modifier.size(18.dp, 16.dp)) {
        val barW = size.width / 5f
        for (i in 0 until 3) {
            val level = if (live) 0.3f + 0.7f * (0.5f + 0.5f * sin((phase + i * 0.33f) * 2f * Math.PI.toFloat() * (1f + i * 0.2f))) else 0.3f
            val h = size.height * level
            drawRoundRect(color, Offset(i * barW * 2f, size.height - h), Size(barW, h), CornerRadius(barW / 2f))
        }
    }
}
