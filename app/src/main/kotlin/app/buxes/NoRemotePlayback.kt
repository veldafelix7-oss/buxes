package app.buxes

import app.buxes.sources.api.PlaybackTarget
import app.buxes.player.api.RemoteAccess
import app.buxes.player.api.RemotePlayback
import app.buxes.player.api.RemoteSessionState
import app.buxes.player.api.RemoteStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Buxes plays everything itself: there is no handing songs to another app, so remote playback is
 * permanently absent — no app, no session, every start refused. The owner-aware controller then
 * only ever uses the local player.
 */
object NoRemotePlayback : RemotePlayback {
    override val access: StateFlow<RemoteAccess> = MutableStateFlow(RemoteAccess.NO_APP)
    override val state: StateFlow<RemoteSessionState> = MutableStateFlow(RemoteSessionState())
    override suspend fun start(target: PlaybackTarget.RemoteProvider): RemoteStart = RemoteStart.Failed(RemoteAccess.NO_APP)
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun next() = Unit
    override fun previous() = Unit
    override fun skipToQueueItem(id: Long) = Unit
    override fun openApp() = Unit
    override fun refresh() = Unit
    override fun positionMs(): Long = 0L
}
