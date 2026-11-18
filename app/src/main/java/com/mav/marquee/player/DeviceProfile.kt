package com.mav.marquee.player

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Jellyfin 10.11 DeviceProfile built from what this device reports ([DeviceCapabilities]).
 *
 * - The measured reference Sony BRAVIA VU1 gets its fixed, pinned profile ([BraviaVu1Profile]).
 * - Any other device gets a profile derived from its decoders and display ([generic]).
 * - If detection failed, [DeviceCapabilities.SAFE] (H.264 1080p SDR, AAC/MP3 stereo) is used and the
 *   server transcodes the rest.
 *
 * The 4-step fallback ladder ([Mode]) behaves the same on every device. Rationale per field:
 * docs/architecture/PLAYBACK_DECISIONS.md.
 */
internal object DeviceProfile {

    /**
     * HEVC range types the reference TV shows as HDR10/HLG/SDR. DV P8.x and P7 (BL compat 6) carry an
     * HDR10 / HLG / SDR base layer; Media3 maps P8 to the HEVC decoder itself and
     * [MarqueeRenderersFactory] maps P7 when no Dolby Vision decoder exists. HDR10+ dynamic SEI is
     * ignored by the decoder (plays as HDR10). `DOVI` (P5, no compatible base layer) is absent ->
     * server tone-map transcode.
     */
    const val HEVC_RANGES =
        "SDR|HDR10|HLG|HDR10Plus|DOVIWithHDR10|DOVIWithHLG|DOVIWithSDR|" +
            "DOVIWithHDR10Plus|DOVIWithEL|DOVIWithELHDR10Plus"

    /** Reference remux output targets the panel's base layers; Jellyfin strips P7 DV metadata without encoding. */
    const val HEVC_REMUX_RANGES = "SDR,HDR10,HLG"

    // Jellyfin defaults an absent/null profile limit to 8 Mbps. Explicit API ceiling means Auto.
    const val UNLIMITED_BITRATE = Int.MAX_VALUE

    enum class Mode {
        /** Normal: direct play whatever the device decodes; transcode the rest. */
        Direct,
        /** Fallback 1: no direct play; HLS remux with the video stream copied, audio re-encoded. */
        Remux,
        /** Fallback 2: HEVC transcode within the decoder's ceiling (H.264 if the device has no HEVC). */
        Hevc,
        /** Final fallback: full H.264 <=1080p SDR transcode. */
        H264,
    }

    fun build(
        maxBitrate: Int = 0,
        mode: Mode = Mode.Direct,
        videoCodec: String = "hevc",
        ac3Fallback: Boolean = false,
        aacAudio: Boolean = false,
        caps: DeviceCapabilities = PlaybackCapabilities.get(),
    ): JsonObject = when {
        DeviceQuirks.isBraviaVu1(caps) -> BraviaVu1Profile.build(maxBitrate, mode, videoCodec, ac3Fallback, aacAudio)
        caps.probeFailed -> generic(maxBitrate, mode, videoCodec, ac3Fallback, aacAudio,
            DeviceCapabilities.SAFE.copy(manufacturer = caps.manufacturer, model = caps.model))
        else -> generic(maxBitrate, mode, videoCodec, ac3Fallback, aacAudio, caps)
    }

    /** `hevc-rangetype` for HEVC remux/transcode URLs: only the HDR base layers this display shows. */
    fun remuxRanges(caps: DeviceCapabilities = PlaybackCapabilities.get()): String =
        if (DeviceQuirks.isBraviaVu1(caps)) HEVC_REMUX_RANGES
        else buildList {
            add("SDR")
            if (DeviceCapabilities.Hdr.HDR10 in caps.displayHdr) add("HDR10")
            if (DeviceCapabilities.Hdr.HLG in caps.displayHdr) add("HLG")
        }.joinToString(",")

    // ------------------------------------------------------------------------------------------ generic

    internal fun generic(
        maxBitrate: Int, mode: Mode, videoCodec: String, ac3Fallback: Boolean, aacAudio: Boolean,
        caps: DeviceCapabilities,
    ): JsonObject {
        val limits = StreamLimits.of(caps)
        val audio = caps.audio
        fun audioList(vararg candidates: String) = candidates.filter { it in audio }.joinToString(",")
        fun videoList(vararg candidates: String) = candidates.filter { caps.decodes(it) }.joinToString(",")
        val mkvAudio = audioList("aac", "ac3", "eac3", "dts", "mp3", "mp2", "opus", "vorbis", "flac", "pcm_s16le", "pcm_s24le")
        val mp4Audio = audioList("aac", "ac3", "eac3", "dts", "mp3", "opus", "flac")
        val tsAudio = audioList("aac", "ac3", "eac3", "dts", "mp3", "mp2")
        // Negotiate a single transcode codec (Jellyfin 10.11 merges unqualified Width/Height across codecs).
        val transcodeVideo = if (mode != Mode.H264 && videoCodec == "hevc" && limits.hevc) "hevc" else "h264"
        val multichannel = "eac3" in audio || "ac3" in audio
        val transcodeAudio = when {
            aacAudio -> "aac"
            ac3Fallback -> if ("ac3" in audio) "ac3,aac" else "aac"
            "eac3" in audio -> "eac3"
            "ac3" in audio -> "ac3"
            else -> "aac"
        }

        return buildJsonObject {
            put("Name", "Marquee")
            put("MaxStreamingBitrate", if (maxBitrate > 0) maxBitrate else UNLIMITED_BITRATE)
            put("MaxStaticBitrate", if (maxBitrate > 0) maxBitrate else UNLIMITED_BITRATE)
            put("MusicStreamingTranscodingBitrate", 320_000)
            put("DirectPlayProfiles", buildJsonArray {
                videoList("hevc", "h264", "vp9", "av1", "vp8", "mpeg2video", "mpeg4").takeIf { it.isNotEmpty() }
                    ?.let { add(dp("mkv,webm", "Video", it, mkvAudio)) }
                videoList("hevc", "h264", "vp9", "av1", "mpeg4").takeIf { it.isNotEmpty() }
                    ?.let { add(dp("mp4,m4v,mov", "Video", it, mp4Audio)) }
                videoList("hevc", "h264", "mpeg2video").takeIf { it.isNotEmpty() }
                    ?.let { add(dp("ts,mpegts,m2ts", "Video", it, tsAudio)) }
                add(dp("mp3", "Audio", null, "mp3"))
                if ("flac" in audio) add(dp("flac", "Audio", null, "flac"))
                add(dp("m4a,mp4,m4b", "Audio", null, audioList("aac", "flac")))
                audioList("opus", "vorbis", "flac").takeIf { it.isNotEmpty() }?.let { add(dp("ogg,oga,opus,webm", "Audio", null, it)) }
                if ("pcm_s16le" in audio) add(dp("wav", "Audio", null, audioList("pcm_s16le", "pcm_s24le")))
                add(dp("mka,mkv", "Audio", null, audioList("aac", "ac3", "eac3", "mp3", "opus", "vorbis", "flac")))
            })
            put("TranscodingProfiles", buildJsonArray {
                add(buildJsonObject {
                    put("Container", "mp4")
                    put("Type", "Video")
                    put("VideoCodec", transcodeVideo)
                    if (transcodeVideo == "h264") put("Conditions", buildJsonArray {
                        add(cond("LessThanEqual", "Width", limits.h264TranscodeWidth.toString()))
                        add(cond("LessThanEqual", "Height", limits.h264TranscodeHeight.toString()))
                    })
                    put("AudioCodec", transcodeAudio)
                    put("Protocol", "hls")
                    put("Context", "Streaming")
                    put("MaxAudioChannels", if (multichannel) "6" else "2")
                    put("MinSegments", 1)
                    put("SegmentLength", 4)
                    put("BreakOnNonKeyFrames", true)
                    put("CopyTimestamps", false)
                    put("EnableSubtitlesInManifest", false)
                    put("EnableAudioVbrEncoding", true)
                })
                add(buildJsonObject {
                    put("Container", "mp4"); put("Type", "Audio"); put("AudioCodec", "aac")
                    put("Protocol", "hls"); put("Context", "Streaming"); put("MaxAudioChannels", "2")
                    put("MinSegments", 1); put("BreakOnNonKeyFrames", true)
                })
                add(buildJsonObject {
                    put("Container", "mp3"); put("Type", "Audio"); put("AudioCodec", "mp3")
                    put("Protocol", "http"); put("Context", "Streaming"); put("MaxAudioChannels", "2")
                })
            })
            put("ContainerProfiles", JsonArray(emptyList()))
            put("CodecProfiles", buildJsonArray {
                // Conservative software-decoding budget for FLAC, independent of the device.
                if ("flac" in audio) for (type in listOf("VideoAudio", "Audio")) {
                    add(buildJsonObject {
                        put("Type", type); put("Codec", "flac")
                        put("Conditions", buildJsonArray {
                            add(audioCondition("LessThanEqual", "AudioChannels", "2"))
                            add(audioCondition("LessThanEqual", "AudioSampleRate", "48000"))
                            add(audioCondition("LessThanEqual", "AudioBitDepth", "24"))
                        })
                    })
                }
                // Platform DTS decoders are DTS-core; DTS-HD/X are converted by the server.
                if ("dts" in audio) add(buildJsonObject {
                    put("Type", "VideoAudio"); put("Codec", "dts")
                    put("Conditions", buildJsonArray { add(audioCondition("EqualsAny", "AudioProfile", "DTS")) })
                })
                caps.video["h264"]?.let { d ->
                    add(codec("h264", buildList {
                        add(cond("NotEquals", "IsAnamorphic", "true"))
                        add(cond("EqualsAny", "VideoProfile", d.profiles.sortedBy(::profileOrder).joinToString("|")))
                        add(cond("LessThanEqual", "VideoBitDepth", d.maxBitDepth.toString()))
                        if (d.maxLevel > 0) add(cond("LessThanEqual", "VideoLevel", d.maxLevel.toString()))
                        addAll(sizeAndRate(d))
                        add(cond("EqualsAny", "VideoRangeType", "SDR"))
                        add(cond("NotEquals", "IsInterlaced", "true"))
                    }))
                }
                caps.video["hevc"]?.let { d ->
                    add(codec("hevc", buildList {
                        add(cond("EqualsAny", "VideoProfile", d.profiles.sortedBy(::profileOrder).joinToString("|")))
                        add(cond("LessThanEqual", "VideoBitDepth", d.maxBitDepth.toString()))
                        if (d.maxLevel > 0) add(cond("LessThanEqual", "VideoLevel", d.maxLevel.toString()))
                        addAll(sizeAndRate(d))
                        add(cond("EqualsAny", "VideoRangeType", if (mode == Mode.H264) "SDR" else hevcRanges(caps, d)))
                    }))
                }
                caps.video["vp9"]?.let { d ->
                    add(codec("vp9", buildList {
                        add(cond("EqualsAny", "VideoProfile", d.profiles.sorted().joinToString("|")))
                        add(cond("LessThanEqual", "VideoBitDepth", d.maxBitDepth.toString()))
                        addAll(sizeAndRate(d))
                        add(cond("EqualsAny", "VideoRangeType", hdrBaseRanges(caps, d.maxBitDepth)))
                    }))
                }
                caps.video["av1"]?.let { d ->
                    add(codec("av1", buildList {
                        add(cond("EqualsAny", "VideoProfile", "main"))
                        add(cond("LessThanEqual", "VideoBitDepth", d.maxBitDepth.toString()))
                        addAll(sizeAndRate(d))
                        add(cond("EqualsAny", "VideoRangeType", hdrBaseRanges(caps, d.maxBitDepth)))
                    }))
                }
                for (legacy in listOf("mpeg4", "vp8", "mpeg2video")) caps.video[legacy]?.let { d ->
                    add(codec(legacy, buildList {
                        add(cond("LessThanEqual", "Width", d.maxWidth.toString()))
                        add(cond("LessThanEqual", "Height", d.maxHeight.toString()))
                        if (legacy != "mpeg2video") add(cond("LessThanEqual", "VideoBitDepth", "8"))
                    }))
                }
            })
            put("SubtitleProfiles", subtitleProfiles())
        }
    }

    /** SDR plus the HDR base layers the display accepts (10-bit decoders only). */
    private fun hdrBaseRanges(caps: DeviceCapabilities, bitDepth: Int): String = buildList {
        add("SDR")
        if (bitDepth >= 10 && DeviceCapabilities.Hdr.HDR10 in caps.displayHdr) add("HDR10")
        if (bitDepth >= 10 && DeviceCapabilities.Hdr.HLG in caps.displayHdr) add("HLG")
    }.joinToString("|")

    /**
     * HEVC adds HDR10+ (plays as HDR10 when the display lacks HDR10+ metadata support) and Dolby Vision
     * streams whose base layer the display can show. Without a DV decoder, Media3 maps P8 and our renderer
     * maps P7 to the HEVC decoder; with one, DV is only advertised when the display accepts DV.
     */
    private fun hevcRanges(caps: DeviceCapabilities, d: DeviceCapabilities.VideoDecoder): String {
        val hdr10 = d.maxBitDepth >= 10 && DeviceCapabilities.Hdr.HDR10 in caps.displayHdr
        val hlg = d.maxBitDepth >= 10 && DeviceCapabilities.Hdr.HLG in caps.displayHdr
        val dvDisplay = DeviceCapabilities.Hdr.DOLBY_VISION in caps.displayHdr
        val baseLayerDv = !caps.dolbyVisionDecoder || dvDisplay
        return buildList {
            add("SDR")
            if (hdr10) addAll(listOf("HDR10", "HDR10Plus"))
            if (hlg) add("HLG")
            if (caps.dolbyVisionDecoder && dvDisplay) add("DOVI")
            if (baseLayerDv) {
                if (hdr10) add("DOVIWithHDR10")
                if (hlg) add("DOVIWithHLG")
                add("DOVIWithSDR")
                if (hdr10) addAll(listOf("DOVIWithHDR10Plus", "DOVIWithEL", "DOVIWithELHDR10Plus"))
            }
        }.joinToString("|")
    }

    private fun sizeAndRate(d: DeviceCapabilities.VideoDecoder) = buildList {
        add(cond("LessThanEqual", "Width", d.maxWidth.toString()))
        add(cond("LessThanEqual", "Height", d.maxHeight.toString()))
        if (d.maxBitrate > 0) add(cond("LessThanEqual", "VideoBitrate", d.maxBitrate.toString()))
        add(cond("LessThanEqual", "VideoFramerate", d.maxFrameRate.toString()))
    }

    private val PROFILE_ORDER = listOf("high", "main", "baseline", "constrained baseline", "constrained high", "high 10", "main 10")
    private fun profileOrder(p: String) = PROFILE_ORDER.indexOf(p).let { if (it < 0) PROFILE_ORDER.size else it }

    /** Media3 renders these subtitle formats itself on every device. */
    internal fun subtitleProfiles() = buildJsonArray {
        for (f in listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "ttml")) add(sub(f, "External"))
        for (f in listOf("srt", "subrip", "ass", "ssa")) add(sub(f, "Embed"))
        for (f in listOf("pgssub", "pgs", "dvdsub")) add(sub(f, "Embed", "mkv"))
        add(sub("dvbsub", "Embed", "mkv,ts,mpegts"))
        // Image subtitles in an HLS transcode: burn in only that stream.
        for (f in listOf("pgssub", "dvdsub", "dvbsub")) add(sub(f, "Encode"))
    }

    private fun dp(container: String, type: String, video: String?, audio: String) = buildJsonObject {
        put("Container", container)
        put("Type", type)
        if (video != null) put("VideoCodec", video)
        put("AudioCodec", audio)
    }

    private fun cond(c: String, p: String, v: String) = buildJsonObject {
        put("Condition", c); put("Property", p); put("Value", v); put("IsRequired", false)
    }

    private fun audioCondition(c: String, p: String, v: String) = buildJsonObject {
        put("Condition", c); put("Property", p); put("Value", v); put("IsRequired", true)
    }

    private fun codec(name: String, conditions: List<JsonObject>) = buildJsonObject {
        put("Type", "Video")
        put("Codec", name)
        put("Conditions", JsonArray(conditions))
    }

    private fun sub(format: String, method: String, container: String? = null) = buildJsonObject {
        put("Format", format); put("Method", method)
        if (container != null) put("Container", container)
    }
}

/**
 * Size/level ceilings used when building copy and transcode stream URLs ([StreamResolver]).
 * For the reference BRAVIA VU1 these are exactly the previously hardcoded values.
 */
internal data class StreamLimits(
    /** The device has a hardware HEVC decoder that can take HEVC transcodes. */
    val hevc: Boolean,
    val hevcMaxWidth: Int,
    val hevcMaxHeight: Int,
    val hevcLevel: Int,
    val hevcMain10: Boolean,
    /** H.264 stream-copy ceiling (decoder size, capped at 1920x1088 as before). */
    val h264CopyWidth: Int,
    val h264CopyHeight: Int,
    val h264Level: Int,
    /** H.264 transcode output ceiling. */
    val h264TranscodeWidth: Int,
    val h264TranscodeHeight: Int,
) {
    companion object {
        val BRAVIA_VU1 = StreamLimits(true, 4096, 2176, 153, true, 1920, 1088, 42, 1920, 1080)

        fun of(caps: DeviceCapabilities = PlaybackCapabilities.get()): StreamLimits {
            if (DeviceQuirks.isBraviaVu1(caps)) return BRAVIA_VU1
            val c = if (caps.probeFailed) DeviceCapabilities.SAFE else caps
            val hevc = c.video["hevc"]?.takeIf { it.hardware }
            val h264 = c.video["h264"] ?: DeviceCapabilities.SAFE.video.getValue("h264")
            return StreamLimits(
                hevc = hevc != null,
                hevcMaxWidth = minOf(hevc?.maxWidth ?: 1920, 4096),
                hevcMaxHeight = minOf(hevc?.maxHeight ?: 1080, 2176),
                hevcLevel = hevc?.maxLevel?.takeIf { it > 0 } ?: 123,
                hevcMain10 = hevc != null && hevc.maxBitDepth >= 10,
                h264CopyWidth = minOf(h264.maxWidth, 1920),
                h264CopyHeight = minOf(h264.maxHeight, 1088),
                h264Level = minOf(h264.maxLevel.takeIf { it > 0 } ?: 41, 42),
                h264TranscodeWidth = minOf(h264.maxWidth, 1920),
                h264TranscodeHeight = minOf(h264.maxHeight, 1080),
            )
        }
    }
}
