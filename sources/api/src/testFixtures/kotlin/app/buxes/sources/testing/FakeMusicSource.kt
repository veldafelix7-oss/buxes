package app.buxes.sources.testing

import app.buxes.core.common.Outcome
import app.buxes.core.common.BuxesError
import app.buxes.core.model.AlbumId
import app.buxes.core.model.ArtistId
import app.buxes.core.model.PlaybackRoute
import app.buxes.core.model.SourceId
import app.buxes.core.model.SourceRef
import app.buxes.core.model.Track
import app.buxes.core.model.TrackId
import app.buxes.sources.api.AlbumDetail
import app.buxes.sources.api.ArtistDetail
import app.buxes.sources.api.Basis
import app.buxes.sources.api.Capability
import app.buxes.sources.api.CapabilityState
import app.buxes.sources.api.CatalogFacet
import app.buxes.sources.api.FacetResolution
import app.buxes.sources.api.MusicSource
import app.buxes.sources.api.PlayableMedia
import app.buxes.sources.api.PlaybackFacet
import app.buxes.sources.api.PlaybackTarget
import app.buxes.sources.api.Purpose
import app.buxes.sources.api.QualityRequest
import app.buxes.sources.api.SearchQuery
import app.buxes.sources.api.SearchResults
import app.buxes.sources.api.SourceCapabilities
import app.buxes.sources.api.SourceDescriptor
import app.buxes.sources.api.matching.TrackNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A scriptable in-memory source for tests. Resolution outcomes can be overridden per track; every
 * call is counted so tests can assert "resolved once", "never asked", etc.
 */
class FakeMusicSource(
    id: String,
    tracks: List<Track> = emptyList(),
    basis: Basis = Basis.LOCAL_DEVICE,
    capabilities: SourceCapabilities = SourceCapabilities.available(
        Capability.SEARCH, Capability.BROWSE, Capability.LIBRARY, Capability.DIRECT_STREAM, Capability.ARTWORK,
    ),
    private val expiresAfterMillis: Long? = null,
    private val clock: () -> Long = { 0L },
) : MusicSource {

    val sourceId = SourceId(id)
    private val catalogTracks = tracks.associateBy { it.id }.toMutableMap()
    val outcomes = mutableMapOf<TrackId, FacetResolution>()
    var defaultOutcome: FacetResolution? = null
    var searchFailure: BuxesError? = null

    /** Makes resolve suspend, so concurrency (e.g. request de-duplication) can be exercised. */
    var resolveDelayMillis: Long = 0

    var resolveCalls = 0
        private set
    var searchCalls = 0
        private set
    val resolvedIds = mutableListOf<TrackId>()

    override val descriptor = SourceDescriptor(sourceId, displayName = "Fake $id", providerName = "Fake", basis = basis)

    private val _capabilities = MutableStateFlow(capabilities)
    override val capabilities: StateFlow<SourceCapabilities> = _capabilities

    fun setCapability(capability: Capability, state: CapabilityState) {
        _capabilities.value = SourceCapabilities(_capabilities.value.states + (capability to state))
    }

    fun add(track: Track) {
        catalogTracks[track.id] = track
    }

    override val catalog: CatalogFacet = object : CatalogFacet {
        override suspend fun search(query: SearchQuery): Outcome<SearchResults> {
            searchCalls++
            searchFailure?.let { return Outcome.Failure(it) }
            val words = TrackNormalizer.identityKey(query.text).split(' ').filter { it.isNotEmpty() }
            val hits = catalogTracks.values.filter { t ->
                val hay = TrackNormalizer.identityKey("${t.title} ${t.artistDisplay}")
                words.all { it in hay.split(' ') }
            }
            return Outcome.Success(SearchResults(tracks = hits.take(query.limit)))
        }

        override suspend fun track(ref: SourceRef): Outcome<Track> =
            catalogTracks[TrackId.of(ref.sourceId, ref.providerKey)]?.let { Outcome.Success(it) }
                ?: Outcome.Failure(BuxesError.NotFound(ref.providerKey))

        override suspend fun album(id: AlbumId): Outcome<AlbumDetail> = Outcome.Failure(BuxesError.NotFound("album"))
        override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> = Outcome.Failure(BuxesError.NotFound("artist"))
    }

    override val playback: PlaybackFacet = object : PlaybackFacet {
        override val routes = setOf(PlaybackRoute.DIRECT)

        override suspend fun resolve(track: Track, quality: QualityRequest, purpose: Purpose): FacetResolution {
            resolveCalls++
            resolvedIds += track.id
            if (resolveDelayMillis > 0) kotlinx.coroutines.delay(resolveDelayMillis)
            outcomes[track.id]?.let { return it }
            defaultOutcome?.let { return it }
            if (track.id !in catalogTracks) return FacetResolution.Miss(app.buxes.sources.api.MissReason.NOT_FOUND)
            return FacetResolution.Resolved(
                PlaybackTarget.DirectStream(
                    track.id,
                    PlayableMedia(
                        uri = "fake://${sourceId.value}/${track.id.providerKey}",
                        sourceId = sourceId,
                        cacheKey = "${track.id.value}|max",
                        expiresAtMillis = expiresAfterMillis?.let { clock() + it },
                        durationMs = track.durationMs,
                    ),
                ),
            )
        }
    }
}
