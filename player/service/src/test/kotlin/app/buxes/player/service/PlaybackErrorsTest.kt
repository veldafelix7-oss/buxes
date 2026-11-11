package app.buxes.player.service

import androidx.media3.common.PlaybackException
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.buxes.core.common.BuxesError
import app.buxes.sources.api.HealthOutcome
import app.buxes.sources.api.MissReason
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The engine's classification reaches Now Playing by a stable name (issue #5): release builds
 * rename classes, so a class's own name read back as nothing, and every error said "Something
 * went wrong".
 */
@RunWith(AndroidJUnit4::class)
class PlaybackErrorsTest {

    private val every: List<BuxesError> = listOf(
        BuxesError.Offline, BuxesError.Network(), BuxesError.Server(500), BuxesError.AuthRequired(),
        BuxesError.AuthExpired(), BuxesError.RateLimited(), BuxesError.NotFound("x"), BuxesError.InvalidMedia(),
        BuxesError.UnsupportedFormat(), BuxesError.PolicyDisabled(), BuxesError.PermissionRequired("storage"),
        BuxesError.Unexpected(), BuxesError.Unsupported("x"), BuxesError.NotPlayable("x"), BuxesError.UserActionRequired("x"),
    )

    @Test
    fun `every error survives the trip to the UI by its own name, not its class's`() {
        for (error in every) {
            val kind = PlaybackErrors.kindOf(error)
            assertEquals(error::class, PlaybackErrors.fromKind(kind)!!::class, kind)
        }
        assertEquals(every.size, every.map(PlaybackErrors::kindOf).toSet().size, "one name each")
    }

    private fun resolveError(outcome: HealthOutcome?, reason: MissReason? = null) =
        PlaybackException("x", BuxesResolveException(outcome, "x", reason), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

    @Test
    fun `why no stream was found says what the listener can do`() {
        assertIs<BuxesError.NotPlayable>(PlaybackErrors.classify(resolveError(null, MissReason.NOT_STREAMABLE)), "refused by the source")
        assertIs<BuxesError.NotPlayable>(PlaybackErrors.classify(resolveError(null, MissReason.NOT_PERMITTED)))
        assertIs<BuxesError.Server>(PlaybackErrors.classify(resolveError(null, MissReason.SOURCE_UNAVAILABLE)), "the source is resting")
        assertIs<BuxesError.NotFound>(PlaybackErrors.classify(resolveError(null, MissReason.NOT_FOUND)))
        assertIs<BuxesError.NotFound>(PlaybackErrors.classify(resolveError(null)))
        assertIs<BuxesError.RateLimited>(PlaybackErrors.classify(resolveError(HealthOutcome.RATE_LIMIT)))
    }

    @Test
    fun `a refused stream without the engine's word is still a refusal, not something-went-wrong`() {
        assertIs<BuxesError.NotPlayable>(PlaybackErrors.fromCode(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
    }
}
