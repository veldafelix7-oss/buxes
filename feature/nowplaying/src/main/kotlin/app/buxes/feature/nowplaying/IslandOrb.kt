package app.buxes.feature.nowplaying

import app.buxes.core.designsystem.orb.OrbFace
import app.buxes.core.designsystem.orb.OrbFrame
import app.buxes.player.api.AudioPulse
import app.buxes.player.api.PlaybackController

/**
 * The live orb's mind for the Island (Buxes), outside Compose: the same [OrbDrive] that smooths the
 * music and the same [OrbMind] that turns it into a face as Now Playing's play key, driven by whoever
 * draws it, once per frame. The caller draws the [face] itself.
 */
class IslandOrbDriver(private val controller: PlaybackController, private val pulse: AudioPulse) {
    val face = OrbFace()
    private val mind = OrbMind()
    private val drive = OrbDrive()
    private val frame = OrbFrame()
    private val levels = FloatArray(3)

    /** Moves the face on by [dtMs] of time; true when it is still moving (worth another frame soon). */
    fun tick(dtMs: Float, playing: Boolean): Boolean {
        val measured = playing && pulse.levelsAt(controller.positionMs() + PulseFollower.LEAD_MS, levels)
        drive.update(if (measured) levels else null, dtMs, playing, frame)
        mind.update(dtMs, playing, frame.energy, frame.low, frame.high, frame.glint, null, false, false, face)
        return !mind.atRest
    }
}
