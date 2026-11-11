package app.buxes

import android.content.Intent
import app.buxes.core.model.SourceId
import app.buxes.core.model.TrackId
import app.buxes.player.api.PlayIntent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug builds only: drive the player from adb, so device tests can use the generated test tones
 * as their own queue without touching the listener's library. For example:
 * `adb shell am start -n app.buxes.debug/app.buxes.MainActivity --es buxes.debug play-test-tones --ei buxes.index 2 --ez buxes.paused true`
 */
/**
 * Window-level debug commands: `keep-screen-on` holds the display on while Buxes is in front (for
 * unattended device testing, without touching the phone's own settings); `allow-screen-off` undoes it.
 */
internal fun applyDebugWindowCommand(intent: Intent?, activity: android.app.Activity) {
    val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    when (intent?.getStringExtra("buxes.debug")) {
        "keep-screen-on" -> activity.window.addFlags(flag)
        "allow-screen-off" -> activity.window.clearFlags(flag)
    }
}

/**
 * Debug builds: how each online song's stream was found, in logcat under `BuxesStreams` (D-98) —
 * per app asked, its HTTP code, playability status and the check's codes. Never an address, a cookie
 * or a visitor id.
 */
internal fun installDebugDiagnostics() {
    app.buxes.sources.youtubemusic.StreamWalkDebug.trace = { android.util.Log.i("BuxesStreams", it) }
}

internal fun handleDebugIntent(intent: Intent?, graph: AppGraph) {
    when (intent?.getStringExtra("buxes.debug")) {
        "play-test-tones" -> graph.appScope.launch {
            val tracks = graph.registry.get(SourceId("test"))?.library?.tracks()?.first().orEmpty()
            if (tracks.isEmpty()) return@launch
            graph.catalog.remember(tracks)
            val start = intent.getIntExtra("buxes.index", 0).coerceIn(0, tracks.lastIndex)
            val controller = graph.playbackController
            controller.playContext(tracks.map { it.id }, start, "Test tones")
            if (intent.getBooleanExtra("buxes.paused", false)) {
                // Pause as soon as the new item is current — before any audio is buffered.
                withTimeoutOrNull(5_000) { controller.snapshot.first { it.item?.trackId == tracks[start].id } }
                controller.pause()
            }
        }
        // Show only the test tones in the library (in memory; a restart or library-all restores it).
        "library-test-only" -> graph.libraryScope.value = SourceId("test")
        "library-all" -> graph.libraryScope.value = null
        // ONLINE switched off and on again (local music unaffected).
        "online-off" -> graph.sourceSettings.setEnabled(graph.youtubeMusic.descriptor.id, false)
        "online-on" -> graph.sourceSettings.setEnabled(graph.youtubeMusic.descriptor.id, true)
        // What the app that plays online songs exposes through its media session (device acceptance,
        // YOUTUBE_MUSIC_DEVICE_ACCEPTANCE.md U1–U6). Logs facts only: no titles beyond the current one.
        "remote-probe" -> {
            graph.remotePlayback.refresh()
            val s = graph.remotePlayback.state.value
            val owner = (graph.playbackController as? app.buxes.player.api.OwnerAwarePlaybackController)?.owner?.value
            android.util.Log.i(
                "BuxesDebug",
                "remote access=${graph.remotePlayback.access.value} connected=${s.connected} playing=${s.playing} " +
                    "buffering=${s.buffering} position=${graph.remotePlayback.positionMs()} duration=${s.durationMs} " +
                    "mediaIdShape=${s.mediaId?.let { if (Regex("[A-Za-z0-9_-]{11}").matches(it)) "video-id" else "other(${it.length})" }} " +
                    "actions=${s.actions} queue=${s.queue?.size ?: "not exposed"} activeQueueItem=${s.activeQueueItemId} owner=$owner",
            )
        }
        // Hand one online song to the app: --es buxes.track ytmusic|<id>
        "remote-play" -> intent.getStringExtra("buxes.track")?.takeIf { '|' in it }?.let { id ->
            graph.playbackController.playContext(listOf(TrackId(id)), 0, "Debug")
        }
        "remote-pause" -> graph.playbackController.pause()
        "back-to-local" -> graph.playbackController.resumeLocal()
        // Provenance of what's playing (D-36: kept for diagnostics, never shown on Now Playing).
        "now-playing-source" -> graph.playbackController.snapshot.value.item.let { item ->
            android.util.Log.i(
                "BuxesDebug",
                if (item == null) "Nothing playing"
                else "Now playing ${item.trackId}: served by ${item.servedByDisplayName} (${item.servedBy}) via ${item.resolutionPath}",
            )
        }
        // "Not the same song" for two copies (the infrastructure a future confirmation UI will use):
        // --es buxes.a <trackId> --es buxes.b <trackId>
        "not-same" -> {
            val a = intent.getStringExtra("buxes.a")
            val b = intent.getStringExtra("buxes.b")
            if (a != null && b != null && '|' in a && '|' in b) graph.equivalence.reject(TrackId(a), TrackId(b))
        }
        // Make a sticker from a picture in the app's own files (device tests, no photo picker):
        // --es buxes.file sticker-test.png
        "sticker-make" -> intent.getStringExtra("buxes.file")?.let { name ->
            graph.debugStickerSource.value = android.net.Uri.fromFile(java.io.File(graph.context.filesDir, name))
        }
        // The hands-on guide, as on a first launch.
        "guide" -> {
            graph.guide.forget()
            graph.debugGuide.value = true
        }
        "guide-done" -> graph.guide.markSeen()
        // Arrange stickers straight away (device tests).
        "stickers-arrange" -> graph.debugArrange.value = true
        // Buxes's space without the pinch (adb can't pinch): --es buxes.debug space-enter / space-leave
        "space-enter" -> graph.debugSpace.value = true
        "space-leave" -> graph.debugSpace.value = false
        // Invented synced lyrics for the generated test tones only (device tests of the lyric screen):
        // lines through each tone with a long instrumental gap (about a third of the tone);
        // --es buxes.kind instrumental | notfound answers that instead; intro starts the words late. "lyrics-demo-off" goes back
        // to the service.
        "lyrics-demo" -> {
            val kind = intent.getStringExtra("buxes.kind")
            val credit = app.buxes.core.lyrics.LyricsAttribution("Buxes test lyrics", "https://example.invalid")
            graph.debugLyrics.value = { request ->
                if (!request.key.startsWith("test|")) null else when (kind) {
                    "instrumental" -> app.buxes.core.lyrics.LyricsResult.Found(app.buxes.core.lyrics.Lyrics.Instrumental, credit)
                    "notfound" -> app.buxes.core.lyrics.LyricsResult.NotFound
                    else -> {
                        val length = request.durationMs ?: 30_000L
                        val lines = DEMO_LINES.mapIndexed { i, text ->
                            // Half the lines, a long gap (the orb, or the line drawing on its own), then the
                            // rest; "intro": a long introduction first, then the lines close together.
                            val share = if (kind == "intro") 0.40 + i * 0.1 else if (i < 3) 0.06 + i * 0.12 else 0.75 + (i - 3) * 0.08
                            app.buxes.core.lyrics.LyricsLine((length * share).toLong(), text)
                        }
                        app.buxes.core.lyrics.LyricsResult.Found(app.buxes.core.lyrics.Lyrics.Synced(lines), credit)
                    }
                }
            }
        }
        "lyrics-demo-off" -> graph.debugLyrics.value = null
        // Open a screen without the Wheel: --es buxes.place lyrics | now-playing | home
        "open" -> graph.debugOpen.value = intent.getStringExtra("buxes.place")
        // The finish and display theme, as Settings sets them (device tests of each look's cost):
        // --es buxes.finish GLASS | CARBON | ... --es buxes.display GLASS | CARBON | CUSTOM
        "appearance" -> {
            val current = graph.deviceSettings.appearance.value
            val finish = app.buxes.core.designsystem.shell.FinishPreset.entries.firstOrNull { it.name == intent.getStringExtra("buxes.finish") } ?: current.preset
            val display = app.buxes.core.designsystem.theme.DisplayTheme.entries.firstOrNull { it.name == intent.getStringExtra("buxes.display") } ?: current.display
            graph.deviceSettings.setAppearance(current.copy(preset = finish, display = display))
        }
        // The lyrics theme, as Settings sets it: --es buxes.theme CLASSIC | CONTINUOUS_LINE
        "lyrics-theme" -> app.buxes.core.designsystem.theme.LyricsTheme.entries.firstOrNull { it.name == intent.getStringExtra("buxes.theme") }?.let { theme ->
            val current = graph.deviceSettings.appearance.value
            graph.deviceSettings.setAppearance(current.copy(screen = current.screen.copy(lyricsTheme = theme)))
        }
        // Apps the online stream walk passes over (D-98), to prove the others serve songs:
        // --es buxes.apps VISIONOS,ANDROID_VR (none: every app again). In memory until a restart.
        "stream-skip" -> app.buxes.sources.youtubemusic.StreamWalkDebug.skipped =
            intent.getStringExtra("buxes.apps").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        // Give a PoToken to apps not known to need one (D-98), to see if it helps:
        // --es buxes.apps ANDROID_VR,IOS (none: back to those that need one).
        "stream-pot" -> app.buxes.sources.youtubemusic.StreamWalkDebug.potFor =
            intent.getStringExtra("buxes.apps").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        // Where the backup apps' player script runs (D-98): --es buxes.engine webview | sandbox.
        "script-engine" -> {
            ScriptEngineDebug.forceWebView = intent.getStringExtra("buxes.engine") == "webview"
            graph.scriptEngine.reset()
        }
        // Buxes's update notice (D-99) on demand: --es buxes.as 0.1.0 checks as if this were 0.1.0;
        // --ez buxes.install true offers the install here too (a debug build is another package,
        // so Android refuses the release's app: the download and its checks are what's tested).
        "update-check" -> graph.updates.checkNow(
            pretend = intent.getStringExtra("buxes.as")?.let(app.buxes.update.Version::of),
            forceInstall = intent.getBooleanExtra("buxes.install", false),
        )
        // The install path from an APK already in the app's files (a debuggable test copy, via run-as):
        // --es buxes.file update-test.apk --es buxes.version 0.1.4
        "update-file" -> intent.getStringExtra("buxes.file")?.let { name ->
            val version = app.buxes.update.Version.of(intent.getStringExtra("buxes.version") ?: "0.0.0") ?: return@let
            graph.updates.installFile(
                java.io.File(graph.context.filesDir, name),
                app.buxes.update.Release(version, "v$version", "", app.buxes.update.UpdateCenter.RELEASES_PAGE, null),
            )
        }
        // Classic lyrics' paper, as Settings sets it (D-100): --es buxes.background AUTOMATIC | BLACK | WHITE
        "lyrics-background" -> app.buxes.core.designsystem.theme.LyricsBackground.entries.firstOrNull { it.name == intent.getStringExtra("buxes.background") }?.let { choice ->
            val current = graph.deviceSettings.appearance.value
            graph.deviceSettings.setAppearance(current.copy(screen = current.screen.copy(lyricsBackground = choice)))
        }
        "pause" -> graph.playbackController.pause()
        "play" -> if (graph.playbackController.snapshot.value.intent != PlayIntent.PLAY) graph.playbackController.play()
        "next" -> graph.playbackController.next()
        "previous" -> graph.playbackController.previous()
        // Full screen, as Settings sets it (D-85): --ez buxes.on true | false.
        "full-screen" -> graph.deviceSettings.setImmersive(intent.getBooleanExtra("buxes.on", true))
        // Music folders, as Settings sets them (D-32): --es buxes.include Music/ reads only that
        // folder; no extra reads everything again.
        "music-folders" -> graph.musicFolders.select(
            app.buxes.sources.api.FolderSelection(included = setOfNotNull(intent.getStringExtra("buxes.include"))),
        )
    }
}

/** Invented words for the test tones (no song's lyrics are ever bundled). */
private val DEMO_LINES = listOf(
    "Quiet lights along the water",
    "every window keeps a song",
    "carry me across the evening",
    "Slow and steady goes the night",
    "hold the line and let it run",
    "till the morning finds us home",
)
