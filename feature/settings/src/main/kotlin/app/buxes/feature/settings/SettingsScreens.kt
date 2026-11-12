package app.buxes.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.buxes.core.designsystem.component.FocusList
import app.buxes.player.api.AudioEffects
import app.buxes.player.api.SleepTimer
import app.buxes.player.api.SleepTimerState
import app.buxes.player.api.SleepTimerText
import app.buxes.core.designsystem.component.KeyLayout
import app.buxes.core.designsystem.component.KeyboardAction
import app.buxes.core.designsystem.component.ListInputEffect
import app.buxes.core.designsystem.component.LocalMiniature
import app.buxes.core.designsystem.component.LocalScreenInsets
import app.buxes.core.designsystem.component.MenuPreview
import app.buxes.core.designsystem.component.MenuRow
import app.buxes.core.designsystem.component.BuxesTextField
import app.buxes.core.designsystem.shell.DeviceAppearance
import app.buxes.core.designsystem.shell.FinishPreset
import app.buxes.core.designsystem.shell.formatHex
import app.buxes.core.designsystem.shell.palette
import app.buxes.core.designsystem.shell.parseHex
import app.buxes.core.designsystem.theme.PaperColors
import app.buxes.core.designsystem.theme.CarbonColors
import app.buxes.core.designsystem.theme.DisplayBackground
import app.buxes.core.designsystem.theme.DisplayTheme
import app.buxes.core.designsystem.theme.BuxesTheme
import app.buxes.core.designsystem.theme.Spacing
import app.buxes.core.designsystem.tonal.ContinuousShape
import app.buxes.core.designsystem.type.BuxesText
import app.buxes.core.interaction.FocusListState
import app.buxes.core.interaction.HapticProfile
import app.buxes.core.interaction.InputTargetEffect
import app.buxes.core.interaction.BuxesInput
import app.buxes.core.interaction.WheelButton
import app.buxes.core.interaction.WheelContext
import app.buxes.core.interaction.rememberFocusListState
import app.buxes.core.interaction.rememberBuxesHaptics

private enum class SettingsRow { Appearance, BuxesSpace, FullScreen, TitleBarBattery, NowPlayingLayout, HideNowPlayingTray, Keyboard, MusicFolders, OnlineSources, Autoplay, Recommendations, AvoidRepeats, Crossfade, Equalizer, SleepTimer, OnlineLyrics, LyricsCredit, Haptics, Clicks, StartupSound, SoftwareUpdate }

/** Settings ▸ Software update (D-99): the version, a check now, and whether Buxes checks by itself. */
interface SoftwareUpdateSettings {
    /** "0.1.3". */
    val version: String

    /** Null when this build never checks by itself (a test build). */
    val automatic: kotlinx.coroutines.flow.StateFlow<Boolean>?

    fun setAutomatic(on: Boolean)

    fun checkNow()
}

/**
 * Settings (D-26, D-32, D-35): the device's look, which folders hold its music, which online sources it
 * uses, and how it answers touch.
 */
@Composable
fun SettingsScreen(
    repository: DeviceSettingsRepository,
    folders: MusicFolderSettings,
    onlineService: OnlineServiceSettings,
    onAppearance: () -> Unit,
    onMusicFolders: () -> Unit,
    onOnlineService: () -> Unit,
    /** Buxes's space (D-54) without the two-finger pinch: personalize, stickers, help. */
    onBuxesSpace: () -> Unit = {},
    /** Settings ▸ Equaliser (D-76). */
    onEqualizer: () -> Unit = {},
    /** The sleep timer (D-75): its row shows when the music stops; choosing opens its choices. */
    sleepTimer: SleepTimer? = null,
    onSleepTimer: () -> Unit = {},
    /** Settings ▸ Custom sources (D-91): the listener's own stream sources; null in a build without online music. */
    /** Settings ▸ Software update (D-99); null in a build that can't check. */
    softwareUpdate: SoftwareUpdateSettings? = null,
) {
    val appearance by repository.appearance.collectAsStateWithLifecycle()
    val startupSound by repository.startupSound.collectAsStateWithLifecycle()
    val hapticProfile by repository.hapticProfile.collectAsStateWithLifecycle()
    val clicksOn by repository.clicks.collectAsStateWithLifecycle()
    val autoplayOn by repository.autoplay.collectAsStateWithLifecycle()
    val recommendationsOn by repository.onlineRecommendations.collectAsStateWithLifecycle()
    val avoidRepeatsOn by repository.avoidRepeats.collectAsStateWithLifecycle()
    val onlineLyricsOn by repository.onlineLyrics.collectAsStateWithLifecycle()
    val buxesKeyboard by repository.buxesKeyboard.collectAsStateWithLifecycle()
    val hideNowPlayingTray by repository.hideNowPlayingTray.collectAsStateWithLifecycle()
    val nostalgicNowPlaying by repository.nostalgicNowPlaying.collectAsStateWithLifecycle()
    val immersive by repository.immersive.collectAsStateWithLifecycle()
    val titleBarBattery by repository.titleBarBattery.collectAsStateWithLifecycle()
    val folderList by folders.folders.collectAsStateWithLifecycle()
    val selection by folders.selection.collectAsStateWithLifecycle()
    val service by onlineService.state.collectAsStateWithLifecycle()
    val effects by repository.effects.collectAsStateWithLifecycle()
    val overlay = app.buxes.core.designsystem.component.LocalOverlayHost.current
    val sleepState by (sleepTimer?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow<SleepTimerState>(SleepTimerState.Off) }).collectAsStateWithLifecycle()
    val sleepSummary by androidx.compose.runtime.produceState(SleepTimerText.summary(sleepState, sleepTimer?.remainingMs()), sleepState) {
        while (true) {
            value = SleepTimerText.summary(sleepState, sleepTimer?.remainingMs())
            if (sleepState !is SleepTimerState.Counting) break
            kotlinx.coroutines.delay(1_000)
        }
    }
    val haptics = rememberBuxesHaptics()
    // A newly chosen haptic profile plays itself, so it can be felt straight away (D-105).
    var profileFelt by remember { mutableStateOf(hapticProfile) }
    LaunchedEffect(hapticProfile) {
        if (hapticProfile != profileFelt) {
            profileFelt = hapticProfile
            haptics.confirm()
        }
    }
    // Only a build with an online service has anything to choose there.
    val rows = SettingsRow.entries.filter {
        (it != SettingsRow.OnlineSources || service != null) &&
            (it != SettingsRow.SleepTimer || sleepTimer != null) &&
            (it != SettingsRow.SoftwareUpdate || softwareUpdate != null)
    }
    val automaticUpdates by (softwareUpdate?.automatic ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsStateWithLifecycle()
    val focus = rememberFocusListState("settings")
    val activate: (Int) -> Unit = { index ->
        when (rows[index]) {
            SettingsRow.Appearance -> onAppearance()
            SettingsRow.BuxesSpace -> onBuxesSpace()
            SettingsRow.FullScreen -> {
                haptics.toggle(on = !immersive)
                repository.setImmersive(!immersive)
            }
            SettingsRow.TitleBarBattery -> if (!immersive) haptics.reject() else {
                haptics.toggle(on = !titleBarBattery)
                repository.setTitleBarBattery(!titleBarBattery)
            }
            SettingsRow.NowPlayingLayout -> {
                haptics.toggle(on = !nostalgicNowPlaying)
                repository.setNostalgicNowPlaying(!nostalgicNowPlaying)
            }
            SettingsRow.HideNowPlayingTray -> {
                haptics.toggle(on = !hideNowPlayingTray)
                repository.setHideNowPlayingTray(!hideNowPlayingTray)
            }
            SettingsRow.Keyboard -> {
                haptics.toggle(on = !buxesKeyboard)
                repository.setBuxesKeyboard(!buxesKeyboard)
            }
            SettingsRow.MusicFolders -> onMusicFolders()
            SettingsRow.OnlineSources -> onOnlineService()
            SettingsRow.Autoplay -> {
                haptics.toggle(on = !autoplayOn)
                repository.setAutoplay(!autoplayOn)
            }
            SettingsRow.Recommendations -> if (!autoplayOn) haptics.reject() else {
                haptics.toggle(on = !recommendationsOn)
                repository.setOnlineRecommendations(!recommendationsOn)
            }
            SettingsRow.AvoidRepeats -> if (!autoplayOn) haptics.reject() else {
                haptics.toggle(on = !avoidRepeatsOn)
                repository.setAvoidRepeats(!avoidRepeatsOn)
            }
            SettingsRow.Crossfade -> overlay.show(
                app.buxes.core.designsystem.component.MenuSpec(
                    if (effects.crossfadeSeconds == 0) "Crossfade is off" else "Crossfade: ${crossfadeLabel(effects.crossfadeSeconds)}",
                    AudioEffects.CROSSFADE_CHOICES.map { seconds ->
                        app.buxes.core.designsystem.component.MenuAction(if (seconds == 0) "Off" else crossfadeLabel(seconds)) { repository.setCrossfade(seconds) }
                    },
                ),
            )
            SettingsRow.Equalizer -> onEqualizer()
            SettingsRow.SleepTimer -> onSleepTimer()
            SettingsRow.OnlineLyrics -> {
                haptics.toggle(on = !onlineLyricsOn)
                repository.setOnlineLyrics(!onlineLyricsOn)
            }
            SettingsRow.LyricsCredit -> haptics.reject()
            // A profile (D-105); choosing one plays it, so it can be felt before going on. A phone without
            // full control of its motor plays its own haptics, as Buxes always did: just on or off.
            SettingsRow.Haptics -> if (haptics.fullControl) {
                overlay.show(
                    app.buxes.core.designsystem.component.MenuSpec(
                        "Haptics: ${hapticProfile.label}",
                        HapticProfile.entries.map { profile ->
                            app.buxes.core.designsystem.component.MenuAction(profile.label) { repository.setHapticProfile(profile) }
                        },
                    ),
                )
            } else {
                repository.setHapticProfile(if (hapticProfile == HapticProfile.OFF) HapticProfile.BALANCED else HapticProfile.OFF)
            }
            SettingsRow.Clicks -> {
                haptics.toggle(on = !clicksOn)
                repository.setClicks(!clicksOn)
            }
            SettingsRow.StartupSound -> {
                haptics.toggle(on = !startupSound)
                repository.setStartupSound(!startupSound)
            }
            SettingsRow.SoftwareUpdate -> softwareUpdate?.let { updates ->
                overlay.show(
                    app.buxes.core.designsystem.component.MenuSpec(
                        "Buxes ${updates.version}",
                        listOfNotNull(
                            app.buxes.core.designsystem.component.MenuAction("Check now") { updates.checkNow() },
                            updates.automatic?.let {
                                app.buxes.core.designsystem.component.MenuAction(if (automaticUpdates) "Automatic checks: On" else "Automatic checks: Off") {
                                    updates.setAutomatic(!automaticUpdates)
                                }
                            },
                        ),
                    ),
                )
            }
        }
    }
    ListInputEffect(focus, onActivate = activate)
    FocusList(
        items = rows,
        state = focus,
        key = { it },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        preview = { row ->
            when (row) {
                SettingsRow.Appearance -> appearance.baseArgb?.let { MenuPreview.Swatch(Color(it)) } ?: MenuPreview.Instrument
                else -> MenuPreview.Instrument
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            SettingsRow.Appearance -> MenuRow("Appearance", focused, value = appearance.display.label)
            SettingsRow.BuxesSpace -> MenuRow("Buxes body", focused, value = "Stickers, help, turn off")
            // Typing: the Wheel becomes Buxes's keyboard, or the phone's keyboard appears (D-45).
            // Full screen (D-85): the phone's bars hidden; the title bar shows the time and the battery.
            SettingsRow.FullScreen -> MenuRow("Full screen", focused, value = if (immersive) "On" else "Off", showChevron = false)
            SettingsRow.TitleBarBattery -> MenuRow(
                "Battery in title bar",
                focused,
                value = if (!immersive) "Full screen only" else if (titleBarBattery) "Shown" else "Hidden",
                enabled = immersive,
                showChevron = false,
            )
            // Nostalgic (D-109): the original's own Now Playing, with no playback keys on the display.
            SettingsRow.NowPlayingLayout -> MenuRow(
                "Now Playing",
                focused,
                value = if (nostalgicNowPlaying) "Nostalgic" else "New",
                showChevron = false,
            )
            // The tray slides away on Now Playing when nothing has been touched (D-108); the Wheel's
            // next move only brings it back.
            SettingsRow.HideNowPlayingTray -> MenuRow(
                "Now Playing's tray",
                focused,
                value = if (hideNowPlayingTray) "Hides when idle" else "Always shown",
                showChevron = false,
            )
            SettingsRow.Keyboard -> MenuRow("Keyboard", focused, value = if (buxesKeyboard) "Buxes" else "Phone", showChevron = false)
            SettingsRow.MusicFolders -> MenuRow(
                "Music folders",
                focused,
                value = when {
                    folderList == null -> null
                    selection == app.buxes.sources.api.FolderSelection.Everything -> "All"
                    else -> "${songsIncluded(folderList.orEmpty(), selection)} songs"
                },
            )
            SettingsRow.OnlineSources -> MenuRow(service?.name ?: "Online music", focused, value = onlineServiceSummary(service))
            SettingsRow.Autoplay -> MenuRow("Autoplay", focused, value = if (autoplayOn) "On" else "Off", showChevron = false)
            SettingsRow.Recommendations -> MenuRow("Online recommendations", focused, value = if (recommendationsOn) "On" else "Off", enabled = autoplayOn, showChevron = false)
            SettingsRow.AvoidRepeats -> MenuRow("Avoid repeats", focused, value = if (avoidRepeatsOn) "On" else "Off", enabled = autoplayOn, showChevron = false)
            // Buxes's own player (D-75 – D-77).
            SettingsRow.Crossfade -> MenuRow("Crossfade", focused, value = if (effects.crossfadeSeconds == 0) "Off" else "${effects.crossfadeSeconds} s", showChevron = false)
            SettingsRow.Equalizer -> MenuRow("Equaliser", focused, value = equalizerSummary(effects))
            SettingsRow.SleepTimer -> MenuRow("Sleep timer", focused, value = sleepSummary, showChevron = false)
            SettingsRow.OnlineLyrics -> MenuRow("Online lyrics", focused, value = if (onlineLyricsOn) "On" else "Off", showChevron = false)
            // The lyrics service's credit (LYRICS_ARCHITECTURE.md §9): its community wrote the words.
            SettingsRow.LyricsCredit -> MenuRow("Lyrics from LRCLIB, written by its community", focused, enabled = false, showChevron = false)
            SettingsRow.Haptics -> MenuRow(
                "Haptics",
                focused,
                value = if (haptics.fullControl) hapticProfile.label else if (hapticProfile == HapticProfile.OFF) "Off" else "On",
                showChevron = false,
            )
            SettingsRow.Clicks -> MenuRow("Click sound", focused, value = if (clicksOn) "On" else "Off", showChevron = false)
            SettingsRow.StartupSound -> MenuRow("Startup sound", focused, value = if (startupSound) "On" else "Off", showChevron = false)
            SettingsRow.SoftwareUpdate -> MenuRow("Software update", focused, value = softwareUpdate?.version, showChevron = false)
        }
    }
}

/** "2 seconds", "12 seconds". */
internal fun crossfadeLabel(seconds: Int): String = if (seconds == 1) "1 second" else "$seconds seconds"

@Composable
internal fun Swatch(color: Color?) {
    val colors = BuxesTheme.colors
    val shape = CircleShape
    Box(
        Modifier
            .size(22.dp)
            .then(
                if (color != null) {
                    Modifier.background(color, shape)
                } else {
                    // Glass: a clear disc with a rim.
                    Modifier.background(colors.labelPrimary.copy(alpha = 0.08f), shape)
                },
            )
            .border(1.dp, colors.labelPrimary.copy(alpha = 0.25f), shape),
    )
}

/** The Equaliser row's value: the preset, the effect, both, or off. */
internal fun equalizerSummary(effects: AudioEffects): String {
    val eq = effects.equalizer.takeIf { it.enabled }?.preset?.label
    val fx = effects.sound.takeIf { it != app.buxes.player.api.SoundEffect.NONE }?.label
    return when {
        eq != null && fx != null -> "$eq, $fx"
        else -> eq ?: fx ?: "Off"
    }
}
