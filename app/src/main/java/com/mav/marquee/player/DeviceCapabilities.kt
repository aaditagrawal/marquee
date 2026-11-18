package com.mav.marquee.player

/**
 * What this device reports it can decode and display, as plain data with no Android types, so the
 * Jellyfin profile builder ([DeviceProfile]) and stream limits can be unit-tested with fake devices.
 * Produced once per process by [PlaybackCapabilities] (see docs/architecture/TV_CAPABILITIES.md).
 *
 * Codec keys use Jellyfin's names: h264, hevc, vp9, av1, vp8, mpeg2video, mpeg4.
 * Audio keys use Jellyfin's names: aac, mp3, ac3, eac3, dts, opus, vorbis, flac, ...
 */
internal data class DeviceCapabilities(
    val manufacturer: String,
    val model: String,
    /** Non-secure, non-alias decoder names, as reported by MediaCodecList. Empty if not listed. */
    val decoderNames: List<String> = emptyList(),
    val video: Map<String, VideoDecoder> = emptyMap(),
    val audio: Set<String> = emptySet(),
    /** HDR formats the active display accepts. */
    val displayHdr: Set<Hdr> = emptySet(),
    /** A platform `video/dolby-vision` decoder exists. */
    val dolbyVisionDecoder: Boolean = false,
    /** True when capability probing failed or returned nothing usable; [DeviceProfile] then uses [SAFE]. */
    val probeFailed: Boolean = false,
) {
    enum class Hdr { HDR10, HLG, HDR10_PLUS, DOLBY_VISION }

    data class VideoDecoder(
        val maxWidth: Int,
        val maxHeight: Int,
        /** Frame-rate ceiling at the largest standard size (2160p or 1080p) this decoder accepts. */
        val maxFrameRate: Int,
        val maxBitrate: Int,
        /** Jellyfin VideoProfile strings, e.g. "high", "main 10", "profile 2". */
        val profiles: Set<String>,
        /** Jellyfin VideoLevel number (H.264 41 = 4.1, HEVC 153 = 5.1). 0 = not reported. */
        val maxLevel: Int,
        val maxBitDepth: Int,
        val hardware: Boolean,
    )

    fun decodes(codec: String) = video.containsKey(codec)

    companion object {
        /**
         * Conservative fallback used when detection is unavailable or throws: 8-bit H.264 up to
         * 1080p30 level 4.1 in SDR, AAC/MP3 stereo. The server transcodes everything else.
         */
        val SAFE = DeviceCapabilities(
            manufacturer = "", model = "",
            video = mapOf("h264" to VideoDecoder(1920, 1080, 30, 20_000_000,
                setOf("high", "main", "baseline", "constrained baseline"), 41, 8, hardware = true)),
            audio = setOf("aac", "mp3"),
            probeFailed = true,
        )
    }
}

/**
 * Narrow device-specific gates. Each workaround applies only when its gate matches; everything else
 * follows [DeviceCapabilities]. The measured reference device is a Sony BRAVIA VU1 (Realtek RTD287O,
 * Android 11); see docs/architecture/TV_CAPABILITIES.md for the measurements behind each value.
 */
internal object DeviceQuirks {
    private const val REALTEK_PREFIX = "OMX.realtek."

    /**
     * The reference Sony BRAVIA VU1 / Realtek platform. Matches the exact manufacturer and model, and
     * additionally requires a Realtek decoder whenever the decoder list could be read, so a different
     * SoC sold under the same model string falls back to capability detection.
     */
    fun isBraviaVu1(caps: DeviceCapabilities): Boolean =
        caps.manufacturer.equals("Sony", ignoreCase = true) && caps.model == "BRAVIA VU1" &&
            (caps.decoderNames.isEmpty() || caps.decoderNames.any { it.startsWith(REALTEK_PREFIX) })

    /**
     * Realtek's audio decoder advertises its 2-channel OUTPUT limit as an input limit for AC3/E-AC3/DTS,
     * so Media3 would reject 5.1 input it can actually decode and downmix.
     */
    fun realtekStereoInputLimit(decoderName: String?): Boolean =
        decoderName?.startsWith(REALTEK_PREFIX + "audio") == true
}
