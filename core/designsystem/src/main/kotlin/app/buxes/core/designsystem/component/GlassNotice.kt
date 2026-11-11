package app.buxes.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.buxes.core.designsystem.glass.GlassMaterial
import app.buxes.core.designsystem.glass.glass
import app.buxes.core.designsystem.theme.BuxesMotion
import app.buxes.core.designsystem.theme.BuxesTheme
import app.buxes.core.designsystem.theme.Spacing
import app.buxes.core.designsystem.tonal.LocalAlbumTone
import app.buxes.core.interaction.FocusListState
import app.buxes.core.interaction.InputTargetEffect
import app.buxes.core.interaction.BuxesInput
import app.buxes.core.interaction.WheelButton
import app.buxes.core.interaction.WheelContext
import app.buxes.core.interaction.rememberBuxesHaptics

/**
 * A notice that asks for a decision (D-99): a title, a few lines of text, an optional progress bar,
 * and the choices. Glass, in the functional layer, driven by the Wheel like [GlassMenu]: Rotate moves
 * between choices, Center chooses, Menu dismisses (when [dismissible]). Unlike a menu, choosing doesn't
 * close it: each action decides (a download keeps the notice up to show its progress).
 */
@Immutable
data class NoticeSpec(
    val title: String,
    val lines: List<String> = emptyList(),
    val actions: List<MenuAction> = emptyList(),
    /** 0..1 shows a bar (a download under way); null shows none. */
    val progress: Float? = null,
    val dismissible: Boolean = true,
)

@Composable
fun GlassNotice(
    spec: NoticeSpec,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Keeps the panel clear of the title bar; the Wheel stays live while a notice is up. */
    panelPadding: PaddingValues = PaddingValues(0.dp),
) {
    // A new set of choices starts at the first; progress alone keeps the focus where it is.
    val focus = remember(spec.actions.map { it.label }) { FocusListState() }
    val haptics = rememberBuxesHaptics()
    val reduced = BuxesTheme.motion.reduced
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, BuxesMotion.pop()) }
    val choose: (Int) -> Unit = { index ->
        val action = spec.actions.getOrNull(index)
        if (action != null && action.enabled) {
            haptics.confirm()
            action.onSelect()
        } else {
            haptics.reject()
        }
    }
    InputTargetEffect(WheelContext.OVERLAY) { input ->
        when (input) {
            is BuxesInput.Rotate -> {
                if (!focus.moveBy(input.detents)) haptics.boundary()
                true
            }
            is BuxesInput.Press -> {
                when (input.button) {
                    WheelButton.CENTER -> choose(focus.focusedIndex)
                    WheelButton.MENU -> if (spec.dismissible) onDismiss() else haptics.reject()
                    else -> return@InputTargetEffect false
                }
                true
            }
            else -> input is BuxesInput.LongPress || input is BuxesInput.Release
        }
    }
    val colors = BuxesTheme.colors
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
            .background(Color.Black.copy(alpha = scrimAlpha(colors)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (spec.dismissible) onDismiss() }
            .padding(panelPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    val s = 0.96f + 0.04f * appear.value
                    scaleX = s
                    scaleY = s
                }
                .glass(GlassMaterial.Regular, sheetShape(colors))
                // Taps inside the panel never dismiss it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(top = Spacing.m, bottom = Spacing.xs),
        ) {
            BasicText(
                spec.title,
                style = BuxesTheme.type.title.copy(color = colors.labelPrimary),
                modifier = Modifier.padding(horizontal = Spacing.l),
            )
            spec.lines.forEach { line ->
                BasicText(
                    line,
                    style = BuxesTheme.type.body.copy(color = colors.labelSecondary),
                    modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.xs),
                )
            }
            spec.progress?.let { p ->
                Box(
                    Modifier
                        .padding(horizontal = Spacing.l, vertical = Spacing.s)
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.labelTertiary.copy(alpha = 0.35f)),
                ) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(4.dp).background(if (colors.isTonal) LocalAlbumTone.current.accent else colors.labelPrimary))
                }
            }
            if (spec.actions.isNotEmpty()) {
                FocusList(
                    items = spec.actions,
                    state = focus,
                    key = { it.label },
                    contentPadding = PaddingValues(top = Spacing.s),
                    onActivate = choose,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 52.dp * spec.actions.size + Spacing.s),
                    paper = false,
                ) { action, _, focused ->
                    MenuRow(action.label, focused, showChevron = false, enabled = action.enabled)
                }
            }
        }
    }
}
