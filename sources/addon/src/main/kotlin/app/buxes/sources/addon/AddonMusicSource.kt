package app.buxes.sources.addon

import app.buxes.core.common.Outcome
import app.buxes.core.common.BuxesError
import app.buxes.core.model.AlbumId
import app.buxes.core.model.AlbumRef
import app.buxes.core.model.ArtistCredit
import app.buxes.core.model.ArtistId
import app.buxes.core.model.Explicitness
import app.buxes.core.model.MediaKind
import app.buxes.core.model.PlaybackRoute
import app.buxes.core.model.RecordingIdentifiers
import app.buxes.core.model.SourceId
import app.buxes.core.model.SourceRef
import app.buxes.core.model.Track
import app.buxes.core.model.TrackId
import app.buxes.sources.api.AlbumDetail
import app.buxes.sources.api.ArtistDetail
import app.buxes.sources.api.Basis
import app.buxes.sources.api.Capability
import app.buxes.sources.api.CatalogFacet
import app.buxes.sources.api.FacetResolution
import app.buxes.sources.api.HealthOutcome
import app.buxes.sources.api.MissReason
import app.buxes.sources.api.MusicEnvironment
import app.buxes.sources.api.MusicSource
import app.buxes.sources.api.NetworkPolicy
import app.buxes.sources.api.PlayableMedia
import app.buxes.sources.api.PlaybackFacet
import app.buxes.sources.api.PlaybackTarget
import app.buxes.sources.api.Purpose
import app.buxes.sources.api.QualityRequest
import app.buxes.sources.api.SearchKind
import app.buxes.sources.api.SearchQuery
import app.buxes.sources.api.SearchResults
import app.buxes.sources.api.SourceCapabilities
import app.buxes.sources.api.SourceDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.security.MessageDigest

/** One addon the listener added: where it is, what it said about itself, and their settings for it. */
data class AddonConfig(
    val sourceId: SourceId,
    /**
     * The addon's address, normalized ([Addons.address]), with the query it was given (a token, say)
     * if any. Can carry the listener's token: never shown or logged.
     */
    val base: String,
    val manifest: AddonManifest,
    val settings: Map<String, String> = emptyMap(),
)

/**
 * A custom source (D-91): an addon the listener added for its streams, speaking the BitChord addon
 * protocol ([AddonClient]). It isn't a catalogue Buxes browses — songs still come from the online
 * catalogue — and it never enters the source registry. The resolver asks it, before the song's own
 * source, for an EXACT copy of the same edition ([app.buxes.sources.api.resolve.StreamProviders]).
 *
 * - Search maps the addon's songs to [Track]s of this source (`"<sourceId>|<addon's id>"`).
 * - Resolve asks `/stream/{id}` and hands the player the address, telling it when it's DASH or HLS.
 *   Protected (DRM) streams are a miss: Buxes plays open media only.
 * - The quality the addon names is kept as its claim; Buxes's labels come from the decoder.
 */
class AddonMusicSource internal constructor(
    val config: AddonConfig,
    private val now: () -> Long,
    transport: AddonTransport,
) : MusicSource {

    constructor(config: AddonConfig, now: () -> Long = System::currentTimeMillis) : this(config, now, UrlConnectionAddonTransport())

    private val client = AddonClient(config.base, transport, now)
    private val settings: Map<String, String> = config.manifest.settle(config.settings)
    private val settingsKey = settings.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }

    override val descriptor = SourceDescriptor(
        id = config.sourceId,
        displayName = config.manifest.name,
        providerName = config.manifest.name,
        basis = Basis.USER_SERVER,
        environment = MusicEnvironment.ONLINE,
    )

    private val caps = MutableStateFlow(
        SourceCapabilities.available(
            *listOfNotNull(
                Capability.SEARCH.takeIf { config.manifest.canSearch },
                Capability.DIRECT_STREAM.takeIf { config.manifest.canStream },
            ).toTypedArray(),
        ),
    )
    override val capabilities: StateFlow<SourceCapabilities> = caps.asStateFlow()

    override val catalog: CatalogFacet? = if (config.manifest.canSearch) Catalog() else null
    override val playback: PlaybackFacet? = if (config.manifest.canStream) Playback() else null

    /** Asks the addon for its manifest again: is it there, and is it still the same addon? */
    suspend fun check(): Outcome<AddonManifest> = client.manifest()

    private inner class Catalog : CatalogFacet {
        override suspend fun search(query: SearchQuery): Outcome<SearchResults> {
            if (SearchKind.TRACKS !in query.kinds || query.text.isBlank()) return Outcome.Success(SearchResults.Empty)
            return when (val r = client.search(query.text, settings)) {
                is Outcome.Success -> Outcome.Success(
                    SearchResults(tracks = r.value.asSequence().drop(query.offset).take(query.limit).map { it.toTrack(descriptor.id) }.toList()),
                )
                is Outcome.Failure -> r
            }
        }

        override suspend fun track(ref: SourceRef): Outcome<Track> = Outcome.Failure(BuxesError.Unsupported("track lookup"))

        override suspend fun album(id: AlbumId): Outcome<AlbumDetail> = Outcome.Failure(BuxesError.Unsupported("albums"))

        override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> = Outcome.Failure(BuxesError.Unsupported("artists"))
    }

    private inner class Playback : PlaybackFacet {
        override val routes: Set<PlaybackRoute> = setOf(PlaybackRoute.DIRECT)

        override val resolveTimeoutMillis: Long = RESOLVE_TIMEOUT_MS

        override suspend fun resolve(track: Track, quality: QualityRequest, purpose: Purpose): FacetResolution {
            if (track.source.sourceId != descriptor.id) return FacetResolution.Miss(MissReason.NOT_FOUND)
            if (purpose == Purpose.DOWNLOAD) return FacetResolution.Miss(MissReason.NOT_PERMITTED)
            return when (val r = client.stream(track.source.providerKey, settings)) {
                is Outcome.Success -> media(track, r.value)
                is Outcome.Failure -> failure(r.error)
            }
        }

        private fun media(track: Track, stream: AddonStream): FacetResolution {
            // Buxes plays open media only; it never works around protection.
            if (stream.encrypted) return FacetResolution.Miss(MissReason.NOT_STREAMABLE)
            if (!UrlConnectionAddonTransport.allowed(stream.url)) {
                return FacetResolution.Failed(HealthOutcome.INVALID_MEDIA, "stream address refused")
            }
            val at = now()
            val expires = stream.expiresAtMillis?.takeIf { it > at + PlayableMedia.EXPIRY_MARGIN_MILLIS * 2 } ?: (at + STREAM_TTL_MS)
            val media = PlayableMedia(
                uri = stream.url,
                mimeType = StreamFacts.mimeType(stream),
                claimedQuality = StreamFacts.claimedQuality(stream),
                durationMs = track.durationMs,
                expiresAtMillis = expires,
                headers = stream.headers,
                sourceId = descriptor.id,
                cacheKey = "${track.id.value}|$settingsKey",
            )
            return FacetResolution.Resolved(PlaybackTarget.DirectStream(track.id, media))
        }

        private fun failure(error: BuxesError): FacetResolution = when (error) {
            is BuxesError.NotFound -> FacetResolution.Miss(MissReason.NOT_FOUND)
            is BuxesError.RateLimited -> FacetResolution.Failed(HealthOutcome.RATE_LIMIT, "rate limited", error.retryAfterMillis)
            is BuxesError.Network -> FacetResolution.Failed(HealthOutcome.NETWORK_FAILURE, "network")
            // The addon has no sign-in to fix; a refusal is the addon's problem, not the listener's.
            is BuxesError.AuthRequired -> FacetResolution.Failed(HealthOutcome.SERVER_ERROR, "refused")
            is BuxesError.Server -> FacetResolution.Failed(HealthOutcome.SERVER_ERROR, "HTTP ${error.httpStatus}")
            else -> FacetResolution.Failed(HealthOutcome.UNKNOWN, "unreadable answer")
        }
    }

    companion object {
        /** Finding a song's stream is a lookup on the addon's side, but some addons are slow to answer. */
        const val RESOLVE_TIMEOUT_MS = 15_000L

        /** How long an addon's stream address is used when the addon doesn't say. */
        const val STREAM_TTL_MS = 15 * 60_000L
    }
}

internal fun AddonTrack.toTrack(sourceId: SourceId): Track = Track(
    id = TrackId.of(sourceId, id),
    source = SourceRef(sourceId, id),
    title = title,
    artists = artist?.let { listOf(ArtistCredit(it)) }.orEmpty(),
    artistDisplay = artist.orEmpty(),
    album = album?.let { AlbumRef(it) },
    durationMs = durationMs,
    identifiers = RecordingIdentifiers(isrc = isrc),
    explicitness = when (explicit) {
        true -> Explicitness.EXPLICIT
        false -> Explicitness.NOT_EXPLICIT
        null -> Explicitness.UNKNOWN
    },
    routes = setOf(PlaybackRoute.DIRECT),
    kind = MediaKind.SONG,
)

/** Adding an addon: reading the address, fetching the manifest, naming the source. */
object Addons {
    /**
     * The address the listener typed or pasted, normalized: https anywhere, or http to a server on
     * their own network once they agree (D-09).
     *
     * - An address that ends in a `.json` file (`/manifest.json`, or any other the addon names itself
     *   by) is the folder it's in (D-98).
     * - A query (`?token=…`) is kept as typed and sent with every request: manifest, search and
     *   stream ([AddonClient]). A fragment is dropped.
     */
    fun address(typed: String): NetworkPolicy.Verdict {
        val t = typed.trim().substringBefore('#')
        val query = t.substringAfter('?', "")
        var path = t.substringBefore('?').trimEnd('/')
        val afterScheme = path.substringAfter("://")
        if ('/' in afterScheme && afterScheme.substringAfterLast('/').endsWith(".json", ignoreCase = true)) {
            path = path.substringBeforeLast('/').trimEnd('/')
        }
        return when (val verdict = NetworkPolicy.check(path)) {
            is NetworkPolicy.Verdict.Secure -> if (query.isEmpty()) verdict else NetworkPolicy.Verdict.Secure("${verdict.base}?$query")
            is NetworkPolicy.Verdict.LocalCleartext -> if (query.isEmpty()) verdict else NetworkPolicy.Verdict.LocalCleartext("${verdict.base}?$query")
            is NetworkPolicy.Verdict.Refused -> verdict
        }
    }

    /** A stable id for the addon at [base] that doesn't reveal the address. */
    fun sourceIdFor(base: String): SourceId {
        val digest = MessageDigest.getInstance("SHA-256").digest(base.toByteArray())
        return SourceId("custom-" + digest.take(5).joinToString("") { "%02x".format(it) })
    }

    /** What the listener sees of the address: the host alone (the rest can carry a token). */
    fun host(base: String): String = runCatching { URI(base).host }.getOrNull() ?: "unknown address"

    /** Fetches the addon's manifest from [base] (an address from [address]). */
    suspend fun manifest(base: String): Outcome<AddonManifest> = manifest(base, UrlConnectionAddonTransport())

    internal suspend fun manifest(base: String, transport: AddonTransport): Outcome<AddonManifest> = AddonClient(base, transport).manifest()

    /** [manifest] as manifest JSON, for keeping it on the phone; [readManifest] reads it back. */
    fun writeManifest(manifest: AddonManifest): String = AddonProtocol.write(manifest)

    fun readManifest(text: String): AddonManifest? = AddonProtocol.manifest(text.toByteArray())
}
