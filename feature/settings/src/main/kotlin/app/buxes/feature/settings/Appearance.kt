package app.buxes.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.buxes.core.designsystem.component.FocusList
import app.buxes.core.designsystem.component.ListInputEffect
import app.buxes.core.designsystem.component.LocalMiniature
import app.buxes.core.designsystem.component.LocalScreenInsets
import app.buxes.core.designsystem.component.MenuPreview
import app.buxes.core.designsystem.component.MenuRow
import app.buxes.core.designsystem.shell.DeviceAppearance
import app.buxes.core.designsystem.theme.LocalBuxesType
import app.buxes.core.designsystem.theme.LyricsTheme
import app.buxes.core.designsystem.theme.BuxesTheme
import app.buxes.core.designsystem.type.DisplayFont
import app.buxes.core.designsystem.type.LyricsFont
import app.buxes.core.designsystem.type.LyricsTypeface
import app.buxes.core.designsystem.type.LocalTypographyPreset
import app.buxes.core.designsystem.type.TypographyPreset
import app.buxes.core.interaction.FocusListState
import app.buxes.core.interaction.rememberFocusListState
import app.buxes.core.interaction.rememberBuxesHaptics

/*
 * Settings ▸ Appearance (D-41, BUXES_CUSTOMIZATION.md §8): the device body (finish, grain, glitter)
 * and the virtual display (theme, font, background, contrast), each one more Buxes menu. Choices
 * are tried on the device itself as the Wheel turns; Center keeps them, Menu puts the old look back.
 */

/** Whether the display has a picture of its own to show (kept for the display pipeline). */
enum class BackgroundImageStatus { NONE, READY, UNAVAILABLE }

private enum class DisplayRow { Font, LyricsFont, LyricsTheme }

/** Settings ▸ Appearance (Buxes): one look; only the fonts and the lyrics style are chosen. */
@Composable
fun AppearanceScreen(repository: DeviceSettingsRepository, onFont: () -> Unit, onLyricsFont: () -> Unit, onLyricsTheme: () -> Unit) {
    val appearance by repository.appearance.collectAsStateWithLifecycle()
    val focus = rememberFocusListState("appearance")
    val screen = appearance.screen
    val rows = DisplayRow.entries
    val canvas = BuxesTheme.colors.canvas
    val activate: (Int) -> Unit = { index ->
        when (rows[index]) {
            DisplayRow.Font -> onFont()
            DisplayRow.LyricsFont -> onLyricsFont()
            DisplayRow.LyricsTheme -> onLyricsTheme()
        }
    }
    ListInputEffect(focus, onActivate = activate)
    FocusList(
        items = rows,
        state = focus,
        key = { it },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        preview = { MenuPreview.Swatch(canvas) },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            DisplayRow.Font -> MenuRow("Font", focused, value = screen.font.label)
            DisplayRow.LyricsFont -> MenuRow("Lyrics font", focused, value = screen.lyricsFont.label)
            DisplayRow.LyricsTheme -> MenuRow("Lyrics theme", focused, value = screen.lyricsTheme.label)
        }
    }
}

/**
 * Font picker: each face set in itself. Turning the Wheel tries it on the whole display; Center
 * keeps it, Menu puts the old one back.
 */
@Composable
fun FontScreen(repository: DeviceSettingsRepository) {
    val appearance by repository.appearance.collectAsStateWithLifecycle()
    val fonts = DisplayFont.entries
    val focus = remember { FocusListState(initialIndex = fonts.indexOf(repository.appearance.value.screen.font)) }
    PreviewWhileFocused(repository, focus) { current, index ->
        fonts.getOrNull(index)?.let { font -> if (font == current.screen.font) null else current.copy(screen = current.screen.copy(font = font)) }
    }
    val activate: (Int) -> Unit = { index ->
        val current = repository.appearance.value
        repository.setAppearance(current.copy(screen = current.screen.copy(font = fonts[index])))
        repository.setPreview(null)
    }
    ListInputEffect(focus, onActivate = activate)
    FocusList(
        items = fonts,
        state = focus,
        key = { it },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        modifier = Modifier.fillMaxSize(),
    ) { font, _, focused ->
        // The row in its own face, so the list is its own specimen.
        val preset = TypographyPreset.of(font)
        CompositionLocalProvider(LocalTypographyPreset provides preset, LocalBuxesType provides preset.type) {
            MenuRow(font.label, focused, selected = font == appearance.screen.font, showChevron = false)
        }
    }
}

/**
 * Lyrics font picker (D-50): each face set in itself, at a size where calligraphy and blackletter
 * read. Center keeps it.
 */
@Composable
fun LyricsFontScreen(repository: DeviceSettingsRepository) {
    val appearance by repository.appearance.collectAsStateWithLifecycle()
    val fonts = LyricsFont.entries
    val focus = remember { FocusListState(initialIndex = fonts.indexOf(repository.appearance.value.screen.lyricsFont)) }
    val activate: (Int) -> Unit = { index ->
        val current = repository.appearance.value
        repository.setAppearance(current.copy(screen = current.screen.copy(lyricsFont = fonts[index])))
    }
    ListInputEffect(focus, onActivate = activate)
    val display = LocalTypographyPreset.current
    FocusList(
        items = fonts,
        state = focus,
        key = { it },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        modifier = Modifier.fillMaxSize(),
    ) { font, _, focused ->
        // The row in its own face, so the list is its own specimen.
        val specimen = remember(font, display) { LyricsTypeface.of(font, display).specimen }
        CompositionLocalProvider(LocalTypographyPreset provides specimen, LocalBuxesType provides specimen.type) {
            MenuRow(font.label, focused, selected = font == appearance.screen.lyricsFont, showChevron = false)
        }
    }
}

/**
 * Lyrics theme picker (D-81): Classic (one line at a time, inverting the display) or Continuous
 * line (one ivory line writing the words by hand). Center keeps it; the lyrics screen changes the
 * next time it draws — the music, its position and the queue are untouched.
 */
@Composable
fun LyricsThemeScreen(repository: DeviceSettingsRepository) {
    val appearance by repository.appearance.collectAsStateWithLifecycle()
    val haptics = rememberBuxesHaptics()
    val themes = LyricsTheme.entries
    val focus = remember { FocusListState(initialIndex = themes.indexOf(repository.appearance.value.screen.lyricsTheme)) }
    val activate: (Int) -> Unit = { index ->
        haptics.confirm()
        val current = repository.appearance.value
        repository.setAppearance(current.copy(screen = current.screen.copy(lyricsTheme = themes[index])))
    }
    ListInputEffect(focus, onActivate = activate)
    FocusList(
        items = themes,
        state = focus,
        key = { it },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        modifier = Modifier.fillMaxSize(),
    ) { theme, _, focused ->
        MenuRow(theme.label, focused, selected = theme == appearance.screen.lyricsTheme, showChevron = false)
    }
}

/**
 * Tries the focused choice on the device while a picker is open ([choose] returns the appearance to
 * preview, or null for "as it is"); the preview ends when the picker closes.
 */
@Composable
internal fun PreviewWhileFocused(
    repository: DeviceSettingsRepository,
    focus: FocusListState,
    choose: (current: DeviceAppearance, index: Int) -> DeviceAppearance?,
) {
    if (LocalMiniature.current) return
    LaunchedEffect(focus) {
        snapshotFlow { focus.focusedIndex }.collect { index -> repository.setPreview(choose(repository.appearance.value, index)) }
    }
    DisposableEffect(Unit) { onDispose { repository.setPreview(null) } }
}

