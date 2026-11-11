package app.buxes.space

import androidx.compose.ui.graphics.Color
import app.buxes.core.designsystem.theme.BuxesGlassColors

/**
 * The ink of the pages outside the device (the space panel, stickers, Help, the guide): Buxes's
 * glass — white words on translucent white panes over the blue — instead of Carbon's black card.
 */
val SpaceInk = BuxesGlassColors.copy(
    canvas = Color(0x2EFFFFFF),
    canvasRaised = Color(0x3DFFFFFF),
    separator = Color(0x59FFFFFF),
    highlightText = Color(0xFFFFFFFF),
)

/** The pages' strong key: deep blue with white words (a white key would hide white words). */
val KeyBlue = Color(0xFF1456D6)
