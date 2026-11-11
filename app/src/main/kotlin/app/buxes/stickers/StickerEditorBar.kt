package app.buxes.stickers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.buxes.core.designsystem.theme.CarbonColors
import app.buxes.core.designsystem.theme.BuxesTheme
import app.buxes.core.designsystem.theme.Spacing
import app.buxes.core.designsystem.tonal.ContinuousShape
import app.buxes.core.designsystem.type.BuxesText
import app.buxes.space.PanelButton
import app.buxes.space.BuxesSpaceState
import app.buxes.space.SpaceType

/**
 * Under the Buxes while stickers are arranged: what a touch does, take the chosen one off, Done.
 * Comes in as the Buxes comes square on.
 */
@Composable
fun BoxScope.StickerEditorBar(space: BuxesSpaceState, editor: StickerEditorState, store: StickerStore) = SpaceType {
    val ink = CarbonColors
    val chosen = editor.selected
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(Spacing.l)
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .graphicsLayer {
                val shown = space.edit.value
                alpha = shown
                translationY = (1f - shown) * 32.dp.toPx()
            }
            .background(ink.canvasRaised, Shape)
            .border(1.dp, ink.separator, Shape)
            .padding(Spacing.m),
    ) {
        BuxesText(
            if (chosen == null) "Touch a sticker to choose it." else "Drag it to move it. Two fingers resize and turn it.",
            BuxesTheme.type.caption,
            ink.labelSecondary,
            maxLines = 2,
        )
        Spacer(Modifier.height(Spacing.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            PanelButton(
                "Take it off",
                {
                    chosen?.let(store::removePlacement)
                    editor.selected = null
                },
                Modifier.weight(1f),
                emphasized = false,
                color = if (chosen == null) ink.labelTertiary else ink.critical,
            )
            PanelButton(
                "Done",
                {
                    editor.selected = null
                    space.stopEditing()
                },
                Modifier.weight(1f),
            )
        }
    }
}

private val Shape = ContinuousShape(18.dp)
