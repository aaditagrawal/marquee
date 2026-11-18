package com.mav.marquee.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities

/** Connected HDMI is insufficient: require the actual playing AudioTrack's route. */
@UnstableApi
internal class AudioRoutePolicy(private val context: Context) {
    @Volatile var track: AudioTrack? = null
    private val manager = context.getSystemService(AudioManager::class.java)
    val route: String get() = runCatching { track?.routedDevice?.let { "${it.type}:${it.id}" } }.getOrNull() ?: "unknown (PCM)"

    fun allows(format: Format): Boolean {
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) return true
        if (!PlayerPrefs(context).passthrough) return false
        val active = runCatching { track?.routedDevice }.getOrNull() ?: return false
        val arc = active.type == AudioDeviceInfo.TYPE_HDMI_ARC ||
            (Build.VERSION.SDK_INT >= 31 && active.type == AudioDeviceInfo.TYPE_HDMI_EARC)
        if (!arc || manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none { it.id == active.id }) return false
        val encoding = MimeTypes.getEncoding(format.sampleMimeType ?: return false, format.codecs)
        return encoding in active.encodings && AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, active)
            .isPassthroughPlaybackSupported(format, AudioAttributes.DEFAULT)
    }
}
