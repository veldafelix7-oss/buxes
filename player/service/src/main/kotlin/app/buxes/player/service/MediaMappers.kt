@file:OptIn(UnstableApi::class)

package app.buxes.player.service

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import app.buxes.core.model.MediaKind
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import app.buxes.core.common.BuxesError
import app.buxes.core.model.AudioQuality
import app.buxes.core.model.Codec
import app.buxes.core.model.QueueUid
import app.buxes.core.model.SourceId
import app.buxes.player.api.QueueItem
import app.buxes.sources.api.HealthOutcome
import app.buxes.sources.api.PlayableMedia

/**
 * How the player must open a queue item's stream (D-91): a file, or a DASH or HLS manifest. The
 * player picks the kind of source when an item is added, before its stream is known, so an item whose
 * stream turns out to be a manifest is rebuilt for it ([QueueMediaItems.withKind]) before it opens.
 */
enum class StreamKind(internal val query: String?, val mimeType: String?) {
    FILE(null, null),
    DASH("dash", MimeTypes.APPLICATION_MPD),
    HLS("hls", MimeTypes.APPLICATION_M3U8);

    companion object {
        fun of(media: PlayableMedia?): StreamKind {
            val mime = media?.mimeType?.lowercase() ?: return FILE
            return when {
                mime == MimeTypes.APPLICATION_MPD -> DASH
                mime.endsWith("mpegurl") -> HLS
                else -> FILE
            }
        }
    }
}

/** The URI ExoPlayer sees for a queue item; resolved to real media just before it is opened. */
object QueueMediaItems {
    private const val SCHEME = "buxes"
    private const val HOST = "queue"
    private const val CARRY_HOST = "carry"
    private const val KIND = "as"
    private const val SOURCE = "from"

    /**
     * [kind]: the kind of stream the player must open. [servedBy]: set when an item is rebuilt for a
     * new stream from another source (D-92), so the player never mistakes it for the one it had
     * already started loading.
     */
    fun uriFor(uid: QueueUid, kind: StreamKind = StreamKind.FILE, servedBy: SourceId? = null): Uri =
        Uri.Builder().scheme(SCHEME).authority(HOST).appendPath(uid.value)
            .apply {
                kind.query?.let { appendQueryParameter(KIND, it) }
                servedBy?.let { appendQueryParameter(SOURCE, it.value) }
            }
            .build()

    fun uidOf(uri: Uri): QueueUid? =
        if (uri.scheme == SCHEME && uri.authority == HOST) uri.lastPathSegment?.let(::QueueUid) else null

    /** The source the item at [uri] was rebuilt for, if it was (D-92). */
    fun servedByOf(uri: Uri): SourceId? = uri.getQueryParameter(SOURCE)?.takeIf { it.isNotBlank() }?.let(::SourceId)

    /**
     * The stream that carries the rest of the song playing on crossfade's second player while the
     * first switches over (D-92): another copy of the same recording, from a custom source.
     */
    fun carryItem(uid: QueueUid, kind: StreamKind): MediaItem = MediaItem.Builder()
        .setMediaId(uid.value)
        .setUri(Uri.Builder().scheme(SCHEME).authority(CARRY_HOST).appendPath(uid.value).build())
        .setMimeType(kind.mimeType)
        .build()

    fun carryUidOf(uri: Uri): QueueUid? =
        if (uri.scheme == SCHEME && uri.authority == CARRY_HOST) uri.lastPathSegment?.let(::QueueUid) else null

    /** The kind of stream the item at [uri] was built to open. */
    fun kindOf(uri: Uri): StreamKind = when (uri.getQueryParameter(KIND)) {
        StreamKind.DASH.query -> StreamKind.DASH
        StreamKind.HLS.query -> StreamKind.HLS
        else -> StreamKind.FILE
    }

    /** [mediaItem] (one of the queue's, as the player holds it) rebuilt to open a [kind] stream. */
    fun withKind(mediaItem: MediaItem, kind: StreamKind, servedBy: SourceId? = null): MediaItem =
        mediaItem.buildUpon().setUri(uriFor(QueueUid(mediaItem.mediaId), kind, servedBy)).setMimeType(kind.mimeType).build()

    /** Whether the item at [uri] was built for [selection]: the same kind of stream, and the same source if it names one. */
    fun fits(uri: Uri, selection: app.buxes.sources.api.resolve.Selection): Boolean =
        kindOf(uri) == StreamKind.of(selection.media) && servedByOf(uri).let { it == null || it == selection.servedBy }

    fun toMediaItem(item: QueueItem): MediaItem {
        val track = item.track
        val extras = Bundle().apply {
            putString(BuxesExtras.TRACK_ID, track.id.value)
            putString(BuxesExtras.ORIGIN, item.origin.name)
            putString(BuxesExtras.MEDIA_KIND, track.kind.name)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artistDisplay)
            .setAlbumTitle(track.album?.title)
            .setAlbumArtist(track.album?.albumArtist)
            .setArtworkUri(track.artwork?.uri?.let(Uri::parse))
            .setDurationMs(track.durationMs)
            .setTrackNumber(track.trackNumber)
            .setDiscNumber(track.discNumber)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(if (track.kind == MediaKind.LIVE) MediaMetadata.MEDIA_TYPE_RADIO_STATION else MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        // An item already resolved to a manifest (a custom source's DASH, D-91) is built for it.
        val kind = if (track.kind == MediaKind.LIVE) StreamKind.FILE else StreamKind.of(item.selection?.media)
        return MediaItem.Builder()
            .setMediaId(item.uid.value)
            .setUri(uriFor(item.uid, kind))
            .setMediaMetadata(metadata)
            .apply {
                // A live station (D-90) is a live HLS playlist: the player must know before it loads.
                if (track.kind == MediaKind.LIVE) {
                    setMimeType(MimeTypes.APPLICATION_M3U8)
                    setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
                } else {
                    kind.mimeType?.let(::setMimeType)
                }
            }
            .build()
    }
}

/** Actual media quality, measured from the decoder's selected audio format. */
object AudioQualityMapper {

    fun fromTracks(tracks: Tracks): AudioQuality? {
        val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } ?: return null
        val index = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: return null
        return fromFormat(group.getTrackFormat(index))
    }

    fun fromFormat(format: Format): AudioQuality {
        val codec = codecOf(format)
        val bitDepth = bitDepthOf(format.pcmEncoding)
        val sampleRate = format.sampleRate.takeIf { it != Format.NO_VALUE }
        val channels = format.channelCount.takeIf { it != Format.NO_VALUE }
        val reportedBitrate = listOf(format.averageBitrate, format.bitrate, format.peakBitrate)
            .firstOrNull { it != Format.NO_VALUE }?.let { it / 1000 }
        val bitrate = reportedBitrate ?: if (codec == Codec.PCM && sampleRate != null && channels != null && bitDepth != null) {
            sampleRate * channels * bitDepth / 1000
        } else null
        return AudioQuality(
            codec = codec,
            container = format.containerMimeType,
            bitrateKbps = bitrate,
            sampleRateHz = sampleRate,
            bitDepth = bitDepth?.takeIf { codec?.isLossless == true },
            channels = channels,
        )
    }

    fun codecOf(format: Format): Codec? = when (format.sampleMimeType) {
        MimeTypes.AUDIO_FLAC -> Codec.FLAC
        MimeTypes.AUDIO_ALAC -> Codec.ALAC
        MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_WAV -> Codec.PCM
        MimeTypes.AUDIO_AAC -> Codec.AAC
        MimeTypes.AUDIO_OPUS -> Codec.OPUS
        MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2 -> Codec.MP3
        MimeTypes.AUDIO_VORBIS -> Codec.VORBIS
        MimeTypes.AUDIO_AC3 -> Codec.AC3
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> Codec.EAC3
        null -> null
        else -> Codec.OTHER
    }

    private fun bitDepthOf(pcmEncoding: Int): Int? = when (pcmEncoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
        else -> null
    }
}

/**
 * Raised by the data-source resolver when a queue item couldn't be resolved before opening: a
 * failure ([healthOutcome]) or, when that is null, a miss — why, when known ([missReason]).
 */
class BuxesResolveException(
    val healthOutcome: HealthOutcome?,
    message: String,
    val missReason: app.buxes.sources.api.MissReason? = null,
) : java.io.IOException(message)

/**
 * Raised by the data-source resolver when an item built for one kind of stream resolved to another
 * (a file's source handed a DASH manifest, D-91). Not a failure: the engine rebuilds the item for
 * [kind] and it plays. Never let a source misread a stream it can't open.
 */
class BuxesReopenException(val uid: QueueUid, val kind: StreamKind) : java.io.IOException("reopen as $kind")

/** Maps player errors to Buxes's classified errors — raw exception text never reaches the UI. */
object PlaybackErrors {
    /**
     * The server refused the stream URL (401/403/404/410) — for a signed, expiring URL that means
     * it went stale or was revoked, and the same source can be asked for a fresh one.
     */
    fun isRefusedStream(error: PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode in REFUSED_CODES
            cause = cause.cause
        }
        return false
    }

    private val REFUSED_CODES = setOf(401, 403, 404, 410)

    /**
     * The engine's classification as a stable name, for the session ([BuxesExtras.LAST_ERROR]).
     * Never the class's own name: release builds rename classes, and the UI could no longer read
     * it back (issue #5: every error then read "Something went wrong").
     */
    fun kindOf(error: BuxesError): String = when (error) {
        BuxesError.Offline -> "Offline"
        is BuxesError.Network -> "Network"
        is BuxesError.Server -> "Server"
        is BuxesError.AuthRequired -> "AuthRequired"
        is BuxesError.AuthExpired -> "AuthExpired"
        is BuxesError.RateLimited -> "RateLimited"
        is BuxesError.NotFound -> "NotFound"
        is BuxesError.InvalidMedia -> "InvalidMedia"
        is BuxesError.UnsupportedFormat -> "UnsupportedFormat"
        is BuxesError.PolicyDisabled -> "PolicyDisabled"
        is BuxesError.PermissionRequired -> "PermissionRequired"
        is BuxesError.Unexpected -> "Unexpected"
        is BuxesError.Unsupported -> "Unsupported"
        is BuxesError.NotPlayable -> "NotPlayable"
        is BuxesError.UserActionRequired -> "UserActionRequired"
    }

    /**
     * The engine's own classification (published by kind, [kindOf]) back as an error, so the UI
     * says what actually happened rather than what an error code suggests.
     */
    fun fromKind(kind: String?): BuxesError? = when (kind) {
        "Offline" -> BuxesError.Offline
        "Network" -> BuxesError.Network()
        "Server" -> BuxesError.Server()
        "AuthRequired" -> BuxesError.AuthRequired()
        "AuthExpired" -> BuxesError.AuthExpired()
        "RateLimited" -> BuxesError.RateLimited()
        "NotFound" -> BuxesError.NotFound("stream")
        "InvalidMedia" -> BuxesError.InvalidMedia()
        "UnsupportedFormat" -> BuxesError.UnsupportedFormat()
        "PolicyDisabled" -> BuxesError.PolicyDisabled()
        "NotPlayable" -> BuxesError.NotPlayable("stream")
        "Unexpected" -> BuxesError.Unexpected()
        "PermissionRequired" -> BuxesError.PermissionRequired("storage")
        "Unsupported" -> BuxesError.Unsupported("stream")
        "UserActionRequired" -> BuxesError.UserActionRequired("source")
        else -> null
    }

    /** The item that has to be rebuilt for another kind of stream, when that's all [error] is. */
    fun reopenOf(error: PlaybackException): BuxesReopenException? {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is BuxesReopenException) return cause
            cause = cause.cause
        }
        return null
    }

    fun classify(error: PlaybackException): BuxesError {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is BuxesResolveException) return cause.healthOutcome?.let(::fromHealth) ?: fromMiss(cause.missReason)
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return when (cause.responseCode) {
                    401, 403 -> BuxesError.AuthRequired()
                    404, 410 -> BuxesError.NotFound("stream")
                    429 -> BuxesError.RateLimited()
                    else -> BuxesError.Server(cause.responseCode)
                }
            }
            cause = cause.cause
        }
        return fromCode(error.errorCode)
    }

    fun fromCode(code: Int): BuxesError = when (code) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> BuxesError.Network()
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> BuxesError.NotFound("file")
        // Only reached when the engine's own classification isn't at hand: a refused stream.
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> BuxesError.NotPlayable("stream refused")
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> BuxesError.PermissionRequired("storage")
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_DECODING_FAILED -> BuxesError.InvalidMedia()
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> BuxesError.UnsupportedFormat()
        else -> BuxesError.Unexpected("code $code")
    }

    /** Which health outcome a player error means for the source that served the media. */
    fun healthOutcomeOf(error: BuxesError): HealthOutcome = when (error) {
        is BuxesError.Network, BuxesError.Offline -> HealthOutcome.NETWORK_FAILURE
        is BuxesError.AuthRequired -> HealthOutcome.AUTH_FAILURE
        is BuxesError.RateLimited -> HealthOutcome.RATE_LIMIT
        is BuxesError.Server -> HealthOutcome.SERVER_ERROR
        is BuxesError.InvalidMedia, is BuxesError.UnsupportedFormat -> HealthOutcome.INVALID_MEDIA
        is BuxesError.NotFound -> HealthOutcome.MISS
        is BuxesError.PolicyDisabled -> HealthOutcome.POLICY_DISABLED
        else -> HealthOutcome.UNKNOWN
    }

    private fun fromHealth(outcome: HealthOutcome?): BuxesError = when (outcome) {
        HealthOutcome.NETWORK_FAILURE -> BuxesError.Network()
        HealthOutcome.AUTH_FAILURE -> BuxesError.AuthRequired()
        HealthOutcome.RATE_LIMIT -> BuxesError.RateLimited()
        HealthOutcome.SERVER_ERROR -> BuxesError.Server()
        HealthOutcome.INVALID_MEDIA -> BuxesError.InvalidMedia()
        HealthOutcome.POLICY_DISABLED -> BuxesError.PolicyDisabled()
        null, HealthOutcome.MISS -> BuxesError.NotFound("stream")
        else -> BuxesError.Unexpected()
    }

    /** Why no source had a stream: refused or not allowed here, the source resting, or not there. */
    private fun fromMiss(reason: app.buxes.sources.api.MissReason?): BuxesError = when (reason) {
        app.buxes.sources.api.MissReason.NOT_STREAMABLE -> BuxesError.NotPlayable("stream refused")
        app.buxes.sources.api.MissReason.NOT_PERMITTED -> BuxesError.NotPlayable("not permitted")
        app.buxes.sources.api.MissReason.SOURCE_UNAVAILABLE -> BuxesError.Server()
        else -> BuxesError.NotFound("stream")
    }
}
