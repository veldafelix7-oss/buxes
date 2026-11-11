@file:OptIn(UnstableApi::class)

package app.buxes.player.service

import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.buxes.core.model.QueueUid
import app.buxes.core.model.TrackId
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Background playback (ADR-003): a MediaLibraryService hosting the engine's player. System UI,
 * Bluetooth, the lock screen and Buxes's own UI all control playback through this session.
 */
class PlaybackService : MediaLibraryService() {

    private lateinit var engine: BuxesPlaybackEngine
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val deps = (application as PlaybackDependencies.Provider).playbackDependencies
        engine = BuxesPlaybackEngine(this, deps)
        val sessionPlayer = QueueRoutingPlayer(engine, scope)
        val builder = MediaLibrarySession.Builder(this, sessionPlayer, SessionCallback())
            .setBitmapLoader(CacheBitmapLoader(BuxesBitmapLoader(this, deps.artwork)))
        deps.sessionActivity(this)?.let(builder::setSessionActivity)
        val built = builder.build()
        session = built
        engine.onExtrasChanged = { extras -> built.setSessionExtras(extras) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** Swiping the app away keeps music playing; if nothing is playing, the service goes away. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        engine.release()
        super.onDestroy()
    }

    private inner class SessionCallback : MediaLibrarySession.Callback {

        /**
         * Explicit grants (Media3 1.11 defaults untrusted controllers to read-only; risk R-07).
         * Buxes's own UI gets queue commands; trusted system controllers (notification, lock
         * screen, Bluetooth) get standard transport; others stay read-only.
         * Returns an immediate future: completing it via the main thread could deadlock.
         */
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> {
            val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            if (controller.packageName == packageName) {
                builder.setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .addSessionCommands(BuxesSessionCommands.all)
                        .build(),
                )
            }
            return Futures.immediateFuture(builder.build())
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = scope.future {
            val ok = when (customCommand.customAction) {
                BuxesSessionCommands.SET_CONTEXT -> engine.playContext(
                    trackIds = args.getStringArray(BuxesSessionCommands.ARG_TRACK_IDS).orEmpty().map(::TrackId),
                    startIndex = args.getInt(BuxesSessionCommands.ARG_START_INDEX),
                    label = args.getString(BuxesSessionCommands.ARG_CONTEXT_LABEL),
                    shuffle = args.getBoolean(BuxesSessionCommands.ARG_SHUFFLE),
                    radio = args.getBoolean(BuxesSessionCommands.ARG_RADIO),
                )
                BuxesSessionCommands.PLAY_NEXT ->
                    engine.playNext(args.getStringArray(BuxesSessionCommands.ARG_TRACK_IDS).orEmpty().map(::TrackId))
                BuxesSessionCommands.ADD_TO_QUEUE ->
                    engine.addToQueue(args.getStringArray(BuxesSessionCommands.ARG_TRACK_IDS).orEmpty().map(::TrackId))
                BuxesSessionCommands.MOVE -> {
                    engine.move(QueueUid(args.getString(BuxesSessionCommands.ARG_UID).orEmpty()), args.getInt(BuxesSessionCommands.ARG_TO_INDEX))
                    true
                }
                BuxesSessionCommands.REMOVE -> {
                    engine.remove(args.getStringArray(BuxesSessionCommands.ARG_UIDS).orEmpty().map(::QueueUid).toSet())
                    true
                }
                BuxesSessionCommands.SKIP_TO -> {
                    engine.skipTo(QueueUid(args.getString(BuxesSessionCommands.ARG_UID).orEmpty()))
                    true
                }
                BuxesSessionCommands.CLEAR_UPCOMING -> {
                    engine.clearUpcoming()
                    true
                }
                else -> false
            }
            if (ok) SessionResult(SessionResult.RESULT_SUCCESS) else SessionResult(SessionError.ERROR_BAD_VALUE)
        }
    }
}

/**
 * The player the session exposes. Shuffle and repeat requests from any controller (Buxes's UI,
 * Bluetooth, the system) are routed into the QueueManager, so shuffle is a visible reorder of the
 * queue rather than ExoPlayer's hidden shuffle order (ADR-006).
 */
@UnstableApi
internal class QueueRoutingPlayer(
    private val engine: BuxesPlaybackEngine,
    scope: CoroutineScope,
) : ForwardingPlayer(engine.player) {

    private val listeners = CopyOnWriteArraySet<Player.Listener>()

    init {
        // ExoPlayer's own shuffle stays off, so it never reports a change: report the queue's.
        // Without this, the session (and every controller) keeps the shuffle state it saw at connect.
        scope.launch {
            engine.queue.state.map { it.shuffleEnabled }.distinctUntilChanged().drop(1).collect { enabled ->
                val events = Player.Events(FlagSet.Builder().add(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED).build())
                listeners.forEach {
                    it.onShuffleModeEnabledChanged(enabled)
                    it.onEvents(this@QueueRoutingPlayer, events)
                }
            }
        }
    }

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners += listener
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners -= listener
    }

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = engine.setShuffle(shuffleModeEnabled)

    override fun getShuffleModeEnabled(): Boolean = engine.queue.state.value.shuffleEnabled

    override fun setRepeatMode(repeatMode: Int) = engine.setRepeat(
        when (repeatMode) {
            Player.REPEAT_MODE_ONE -> app.buxes.player.api.RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> app.buxes.player.api.RepeatMode.ALL
            else -> app.buxes.player.api.RepeatMode.OFF
        },
    )
}
