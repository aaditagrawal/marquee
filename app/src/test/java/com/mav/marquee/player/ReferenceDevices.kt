package com.mav.marquee.player

/** Synthetic capability inputs for unit tests. No real device dumps. */
internal object ReferenceDevices {
    /** Identity of the measured reference TV; its profile is a fixed quirk, so codec maps stay empty. */
    val BRAVIA_VU1 = DeviceCapabilities(
        manufacturer = "Sony", model = "BRAVIA VU1",
        decoderNames = listOf("OMX.realtek.video.decoder", "OMX.realtek.audio.decoder", "c2.android.aac.decoder"),
    )

    /** A generic arm64 4K TV box: HEVC Main10 L5.1, VP9 profile 2, H.264 L5.1, HDR10 + HLG panel, AC3/E-AC3. */
    val GENERIC_4K_HDR10 = DeviceCapabilities(
        manufacturer = "Example", model = "Generic 4K Box",
        decoderNames = listOf("c2.vendor.avc.decoder", "c2.vendor.hevc.decoder", "c2.vendor.vp9.decoder", "c2.android.aac.decoder"),
        video = mapOf(
            "h264" to DeviceCapabilities.VideoDecoder(4096, 2304, 60, 120_000_000,
                setOf("baseline", "constrained baseline", "main", "high"), 51, 8, hardware = true),
            "hevc" to DeviceCapabilities.VideoDecoder(4096, 2304, 60, 120_000_000,
                setOf("main", "main 10"), 153, 10, hardware = true),
            "vp9" to DeviceCapabilities.VideoDecoder(4096, 2304, 60, 120_000_000,
                setOf("profile 0", "profile 2"), 0, 10, hardware = true),
            "mpeg2video" to DeviceCapabilities.VideoDecoder(1920, 1088, 60, 40_000_000, emptySet(), 0, 8, hardware = true),
        ),
        audio = setOf("aac", "mp3", "ac3", "eac3", "opus", "vorbis", "flac", "pcm_s16le", "pcm_s24le"),
        displayHdr = setOf(DeviceCapabilities.Hdr.HDR10, DeviceCapabilities.Hdr.HLG),
    )

    /** A 1080p SDR-only device: H.264 L4.1 only, no HEVC/VP9, stereo audio decoders. */
    val SDR_1080P = DeviceCapabilities(
        manufacturer = "Example", model = "Generic 1080p Stick",
        decoderNames = listOf("OMX.vendor.avc.decoder", "c2.android.aac.decoder"),
        video = mapOf(
            "h264" to DeviceCapabilities.VideoDecoder(1920, 1088, 60, 40_000_000,
                setOf("baseline", "constrained baseline", "main", "high"), 41, 8, hardware = true),
        ),
        audio = setOf("aac", "mp3", "opus", "vorbis", "flac", "pcm_s16le", "pcm_s24le"),
    )

    /** Detection threw or returned nothing. */
    val FAILED = DeviceCapabilities.SAFE.copy(manufacturer = "Example", model = "Broken Codec List")
}
