package app.buxes.player.service

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import androidx.media3.common.Player

/**
 * Headphones back in, the music back on (Buxes). The player already pauses when the headphones
 * come out (audio becoming noisy); this is the other half: when a wired headset or a Bluetooth
 * audio device is connected again while the music stands paused by that very unplugging (within
 * [RESUME_WINDOW_MS]), playback resumes. A pause the listener chose is left alone.
 */
class HeadsetResume(context: Context, private val player: Player) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var pausedByUnplugAt = 0L

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (!addedDevices.any { it.isHeadphones() }) return
            val since = SystemClock.elapsedRealtime() - pausedByUnplugAt
            if (pausedByUnplugAt > 0L && since in 0..RESUME_WINDOW_MS && !player.playWhenReady && player.playbackState != Player.STATE_IDLE) {
                pausedByUnplugAt = 0L
                player.play()
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            pausedByUnplugAt = when {
                playWhenReady -> 0L
                // The pause the unplugging caused: the one worth undoing.
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> SystemClock.elapsedRealtime()
                // A pause the listener chose (or the system, for a call) stands.
                else -> 0L
            }
        }
    }

    init {
        audio.registerAudioDeviceCallback(callback, null)
        player.addListener(listener)
    }

    fun release() {
        audio.unregisterAudioDeviceCallback(callback)
        player.removeListener(listener)
    }

    private fun AudioDeviceInfo.isHeadphones(): Boolean = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID -> true
        else -> false
    }

    private companion object {
        const val RESUME_WINDOW_MS = 30L * 60 * 1000
    }
}
