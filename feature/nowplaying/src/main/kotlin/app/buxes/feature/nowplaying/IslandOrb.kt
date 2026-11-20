package app.buxes.feature.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import app.buxes.core.designsystem.orb.LiveOrb
import app.buxes.core.designsystem.orb.OrbFace
import app.buxes.core.designsystem.orb.OrbFrame
import app.buxes.core.designsystem.theme.BuxesTheme
import app.buxes.player.api.AudioPulse
import app.buxes.player.api.PlaybackController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The live orb on its own (Buxes), for the Island: the same creature as Now Playing's play key —
 * [OrbDrive] smooths the music, [OrbMind] turns it into a face, [LiveOrb] draws it — only smaller,
 * with nothing to look at and nothing to press. Frames are asked for only while it moves.
 */
@Composable
fun IslandOrb(playing: Boolean, controller: PlaybackController, pulse: AudioPulse, diameter: Dp, modifier: Modifier = Modifier) {
    val reduced = BuxesTheme.motion.reduced
    val face = remember { OrbFace() }
    val mind = remember { OrbMind() }
    val drive = remember { OrbDrive() }
    val frame = remember { OrbFrame() }
    val levels = remember { FloatArray(3) }
    LaunchedEffect(playing, reduced, pulse) {
        var last = -1L
        while (isActive) {
            val nanos = withFrameNanos { it }
            val dt = if (last < 0L) 0f else (nanos - last) / 1e6f
            last = nanos
            val measured = playing && pulse.levelsAt(controller.positionMs() + PulseFollower.LEAD_MS, levels)
            drive.update(if (measured) levels else null, dt, playing, frame)
            mind.update(dt, playing, frame.energy, frame.low, frame.high, frame.glint, null, false, reduced, face)
            if (mind.atRest) {
                delay(mind.millisUntilMove().coerceIn(16L, 400L))
            } else {
                val pace = mind.pacingMs()
                if (pace > 0L) delay(pace)
            }
        }
    }
    LiveOrb(diameter = diameter, settled = !playing, face = face, pressed = { false }, modifier = modifier)
}
