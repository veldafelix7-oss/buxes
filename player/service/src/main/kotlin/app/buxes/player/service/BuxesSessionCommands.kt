package app.buxes.player.service

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * Custom session commands for queue operations (ADR-006). Every queue mutation travels to the
 * service and is executed by the QueueManager — the single writer.
 */
object BuxesSessionCommands {
    const val SET_CONTEXT = "app.buxes.queue.SET_CONTEXT"
    const val PLAY_NEXT = "app.buxes.queue.PLAY_NEXT"
    const val ADD_TO_QUEUE = "app.buxes.queue.ADD_TO_QUEUE"
    const val MOVE = "app.buxes.queue.MOVE"
    const val REMOVE = "app.buxes.queue.REMOVE"
    const val SKIP_TO = "app.buxes.queue.SKIP_TO"
    const val CLEAR_UPCOMING = "app.buxes.queue.CLEAR_UPCOMING"

    const val ARG_TRACK_IDS = "trackIds"
    const val ARG_START_INDEX = "startIndex"
    const val ARG_CONTEXT_LABEL = "contextLabel"
    const val ARG_SHUFFLE = "shuffle"
    const val ARG_RADIO = "radio"
    const val ARG_UID = "uid"
    const val ARG_UIDS = "uids"
    const val ARG_TO_INDEX = "toIndex"

    val all: List<SessionCommand> =
        listOf(SET_CONTEXT, PLAY_NEXT, ADD_TO_QUEUE, MOVE, REMOVE, SKIP_TO, CLEAR_UPCOMING)
            .map { SessionCommand(it, Bundle.EMPTY) }

    fun command(action: String) = SessionCommand(action, Bundle.EMPTY)
}

/** Keys used in MediaItem extras and session extras. Provider-neutral display data only. */
object BuxesExtras {
    const val TRACK_ID = "buxes.trackId"
    const val ORIGIN = "buxes.origin"

    /** The item's [app.buxes.core.model.MediaKind] name (a song, or audio from a video). */
    const val MEDIA_KIND = "buxes.kind"

    // Session extras describing the current item's resolution (who is serving it, and how).
    const val CURRENT_UID = "buxes.current.uid"
    const val SERVED_BY = "buxes.current.servedBy"
    const val SERVED_BY_ID = "buxes.current.servedById"
    const val RESOLUTION_PATH = "buxes.current.path"
    const val CLAIMED_CODEC = "buxes.current.claimed.codec"
    const val CLAIMED_BITRATE = "buxes.current.claimed.kbps"
    const val CLAIMED_SAMPLE_RATE = "buxes.current.claimed.hz"
    const val CLAIMED_BIT_DEPTH = "buxes.current.claimed.bits"
    /**
     * What is heard while crossfade's second player carries the song (D-92): measured from that
     * player's decoder, so the label follows the audio, not the silent first player.
     */
    const val HEARD_CODEC = "buxes.current.heard.codec"
    const val HEARD_BITRATE = "buxes.current.heard.kbps"
    const val HEARD_SAMPLE_RATE = "buxes.current.heard.hz"
    const val HEARD_BIT_DEPTH = "buxes.current.heard.bits"
    const val HEARD_CHANNELS = "buxes.current.heard.channels"
    const val HEARD_CONTAINER = "buxes.current.heard.container"
    const val CONTEXT_LABEL = "buxes.queue.context"
    const val LAST_ERROR = "buxes.error.kind"

    /** A [app.buxes.player.api.Recovery] name while the engine is dealing with the current song's problem. */
    const val RECOVERY = "buxes.error.recovery"
}
