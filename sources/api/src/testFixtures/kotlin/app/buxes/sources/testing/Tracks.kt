package app.buxes.sources.testing

import app.buxes.core.model.AlbumRef
import app.buxes.core.model.ArtistCredit
import app.buxes.core.model.ArtistRole
import app.buxes.core.model.Explicitness
import app.buxes.core.model.RecordingIdentifiers
import app.buxes.core.model.SourceId
import app.buxes.core.model.SourceRef
import app.buxes.core.model.Track
import app.buxes.core.model.TrackId

/** Concise track builder for tests. */
fun track(
    title: String,
    artist: String = "Test Artist",
    source: String = "test",
    key: String = "${title.hashCode()}-${artist.hashCode()}",
    album: String? = null,
    durationSec: Double? = 200.0,
    isrc: String? = null,
    mbid: String? = null,
    explicitness: Explicitness = Explicitness.UNKNOWN,
    featured: List<String> = emptyList(),
): Track {
    val sourceId = SourceId(source)
    return Track(
        id = TrackId.of(sourceId, key),
        source = SourceRef(sourceId, key),
        title = title,
        artists = listOf(ArtistCredit(artist)) + featured.map { ArtistCredit(it, ArtistRole.FEATURED) },
        artistDisplay = artist,
        album = album?.let { AlbumRef(it) },
        durationMs = durationSec?.let { (it * 1000).toLong() },
        identifiers = RecordingIdentifiers(isrc = isrc, musicBrainzRecordingId = mbid),
        explicitness = explicitness,
    )
}
