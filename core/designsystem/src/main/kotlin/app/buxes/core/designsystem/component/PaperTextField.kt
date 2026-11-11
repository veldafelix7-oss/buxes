package app.buxes.core.designsystem.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import app.buxes.core.designsystem.theme.BuxesTheme
import app.buxes.core.designsystem.theme.Spacing
import app.buxes.core.designsystem.tonal.CarbonRadius
import app.buxes.core.designsystem.tonal.ContinuousShape
import app.buxes.core.designsystem.tonal.SurfaceLevel
import app.buxes.core.designsystem.tonal.tonalSurface
import app.buxes.core.designsystem.type.BuxesText

/**
 * A text field on the paper (a search, a playlist's name): an outline on the lit displays, a solid
 * field set a step into Carbon's display (D-101). The phone's keyboard or Buxes's types into it.
 */
@Composable
fun PaperTextField(
    value: String,
    onChange: (String) -> Unit,
    focus: FocusRequester,
    onDone: () -> Unit,
    placeholder: String,
    description: String,
    action: KeyboardAction = KeyboardAction.DONE,
) {
    val colors = BuxesTheme.colors
    val type = BuxesTheme.type
    val shape = ContinuousShape(if (colors.isIndustrial) 2.dp else if (colors.isTonal) CarbonRadius.key else 12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalRowPadding.current, vertical = Spacing.s)
            .then(if (colors.isTonal) Modifier.tonalSurface(shape, SurfaceLevel.Low) else Modifier.border(1.dp, colors.labelTertiary, shape))
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
    ) {
        if (value.isEmpty()) BuxesText(placeholder, type.row, colors.labelTertiary)
        BuxesTextField(
            value = value,
            onValueChange = onChange,
            textStyle = type.row.copy(color = colors.labelPrimary),
            description = description,
            focusRequester = focus,
            action = action,
            onAction = onDone,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Leaving a screen with a text field puts the keyboard away, so it never covers the next screen's Wheel. */
@Composable
fun KeyboardAwayOnLeave() {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    DisposableEffect(Unit) {
        onDispose {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
}
