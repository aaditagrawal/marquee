package com.mav.marquee.player

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the profile JSON sent by the reference Sony BRAVIA VU1 against a frozen copy of the pre-
 * generalization builder ([Frozen]), for every Mode and option. If this fails, the reference TV's
 * request changed: re-measure on that device before updating the frozen copy.
 */
class DeviceProfileBaselineTest {
    @After fun clear() { PlaybackCapabilities.override = null }

    @Test fun braviaVu1ProfileIsByteForByteUnchanged() {
        var cases = 0
        for (mode in DeviceProfile.Mode.entries) for (codec in listOf("hevc", "h264")) for (ac3 in listOf(false, true))
            for (aac in listOf(false, true)) for (bitrate in listOf(0, 4_000_000, 80_000_000)) {
                val expected = Frozen.build(bitrate, mode, codec, ac3, aac).toString()
                val actual = DeviceProfile.build(bitrate, mode, codec, ac3, aac, ReferenceDevices.BRAVIA_VU1).toString()
                assertEquals("mode=$mode codec=$codec ac3=$ac3 aac=$aac bitrate=$bitrate", expected, actual)
                cases++
            }
        assertEquals(96, cases)
        // The default-argument path used by StreamResolver resolves to the same JSON.
        PlaybackCapabilities.override = ReferenceDevices.BRAVIA_VU1
        assertEquals(Frozen.build().toString(), DeviceProfile.build().toString())
        assertEquals("SDR,HDR10,HLG", DeviceProfile.remuxRanges())
        assertEquals(StreamLimits.BRAVIA_VU1, StreamLimits.of())
    }

    @Test fun braviaGateIsNarrow() {
        assertTrue(DeviceQuirks.isBraviaVu1(ReferenceDevices.BRAVIA_VU1))
        // Decoder list unreadable: identity alone still selects the measured profile.
        assertTrue(DeviceQuirks.isBraviaVu1(ReferenceDevices.BRAVIA_VU1.copy(decoderNames = emptyList())))
        // Same model string on a different SoC, other Sony models, other vendors: capability detection.
        assertFalse(DeviceQuirks.isBraviaVu1(ReferenceDevices.BRAVIA_VU1.copy(decoderNames = listOf("c2.mtk.hevc.decoder"))))
        assertFalse(DeviceQuirks.isBraviaVu1(ReferenceDevices.BRAVIA_VU1.copy(model = "BRAVIA 4K VH2")))
        assertFalse(DeviceQuirks.isBraviaVu1(ReferenceDevices.BRAVIA_VU1.copy(manufacturer = "Example")))
        assertFalse(DeviceQuirks.isBraviaVu1(ReferenceDevices.GENERIC_4K_HDR10))
        assertTrue(DeviceQuirks.realtekStereoInputLimit("OMX.realtek.audio.decoder"))
        assertFalse(DeviceQuirks.realtekStereoInputLimit("c2.android.aac.decoder"))
        assertFalse(DeviceQuirks.realtekStereoInputLimit(null))
    }

    /** Verbatim copy of the baseline DeviceProfile.build (only the Mode type is qualified). Do not edit. */
    private object Frozen {
        private const val HEVC_RANGES =
            "SDR|HDR10|HLG|HDR10Plus|DOVIWithHDR10|DOVIWithHLG|DOVIWithSDR|" +
                "DOVIWithHDR10Plus|DOVIWithEL|DOVIWithELHDR10Plus"
        private const val UNLIMITED_BITRATE = Int.MAX_VALUE

    fun build(
        maxBitrate: Int = 0,
        mode: DeviceProfile.Mode = DeviceProfile.Mode.Direct,
        videoCodec: String = "hevc",
        ac3Fallback: Boolean = false,
        aacAudio: Boolean = false,
    ): JsonObject {
        val mkvAudio = "aac,ac3,eac3,dts,mp3,mp2,opus,vorbis,flac,pcm_s16le,pcm_s24le"
        val mp4Audio = "aac,ac3,eac3,dts,mp3,opus,flac"
        val tsAudio = "aac,ac3,eac3,dts,mp3,mp2"
        // Jellyfin 10.11 merges non-qualified Width/Height conditions across EVERY
        // codec in this field. Negotiate a single codec so H.264's limits cannot
        // shrink a HEVC video-copy stream. The default encoder is HEVC.
        val transcodeVideo = if (mode != DeviceProfile.Mode.H264 && videoCodec == "hevc") "hevc" else "h264"

        return buildJsonObject {
            put("Name", "Marquee BRAVIA VU1")
            put("MaxStreamingBitrate", if (maxBitrate > 0) maxBitrate else UNLIMITED_BITRATE)
            put("MaxStaticBitrate", if (maxBitrate > 0) maxBitrate else UNLIMITED_BITRATE)
            put("MusicStreamingTranscodingBitrate", 320_000)
            put("DirectPlayProfiles", buildJsonArray {
                add(dp("mkv,webm", "Video", "hevc,h264,vp9,vp8,mpeg2video,mpeg4", mkvAudio))
                add(dp("mp4,m4v,mov", "Video", "hevc,h264,vp9,mpeg4", mp4Audio))
                add(dp("ts,mpegts,m2ts", "Video", "hevc,h264,mpeg2video", tsAudio))
                add(dp("mp3", "Audio", null, "mp3"))
                add(dp("flac", "Audio", null, "flac"))
                add(dp("m4a,mp4,m4b", "Audio", null, "aac,flac"))
                add(dp("ogg,oga,opus,webm", "Audio", null, "opus,vorbis,flac"))
                add(dp("wav", "Audio", null, "pcm_s16le,pcm_s24le"))
                add(dp("mka,mkv", "Audio", null, "aac,ac3,eac3,mp3,opus,vorbis,flac"))
            })
            put("TranscodingProfiles", buildJsonArray {
                add(buildJsonObject {
                    put("Container", "mp4")
                    put("Type", "Video")
                    // One codec per request; StreamResolver chooses HEVC only for a safe base layer.
                    put("VideoCodec", transcodeVideo)
                    if (transcodeVideo == "h264") put("Conditions", buildJsonArray {
                        add(cond("LessThanEqual", "Width", "1920"))
                        add(cond("LessThanEqual", "Height", "1080"))
                    })
                    // Jellyfin 10.11's encoder allowlist omits eac3. A mixed list silently
                    // chooses AC3, even though FFmpeg encodes EAC3. A singleton preserves
                    // the requested encoder; runtime recovery negotiates AC3 separately.
                    put("AudioCodec", if (aacAudio) "aac" else if (ac3Fallback) "ac3,aac" else "eac3")
                    // Retain server-negotiated HLS for seeks/recovery. StreamResolver substitutes
                    // progressive MKV for audio-only starts/resumes, and pins HDR HLS to main.m3u8.
                    put("Protocol", "hls")
                    put("Context", "Streaming")
                    put("MaxAudioChannels", "6")
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
                // DTS-HD is a different workload from the measured Realtek DTS-core path.
                // FLAC stereo <=48 kHz/24 bit is a conservative software-decoding budget,
                // not a measured multichannel/high-rate capability of this A55.
                for (type in listOf("VideoAudio", "Audio")) {
                    add(buildJsonObject {
                        put("Type", type); put("Codec", "flac")
                        put("Conditions", buildJsonArray {
                            add(audioCondition("LessThanEqual", "AudioChannels", "2"))
                            add(audioCondition("LessThanEqual", "AudioSampleRate", "48000"))
                            add(audioCondition("LessThanEqual", "AudioBitDepth", "24"))
                        })
                    })
                }
                add(buildJsonObject {
                    put("Type", "VideoAudio"); put("Codec", "dts")
                    put("Conditions", buildJsonArray {
                        add(audioCondition("EqualsAny", "AudioProfile", "DTS"))
                    })
                })
                add(codec("h264",
                    cond("NotEquals", "IsAnamorphic", "true"),
                    cond("EqualsAny", "VideoProfile", "high|main|baseline|constrained baseline|constrained high"),
                    cond("LessThanEqual", "VideoBitDepth", "8"),
                    cond("LessThanEqual", "VideoLevel", "42"),
                    cond("LessThanEqual", "Width", "1920"),
                    cond("LessThanEqual", "Height", "1088"),
                    cond("EqualsAny", "VideoRangeType", "SDR"),
                    cond("NotEquals", "IsInterlaced", "true"),
                ))
                add(codec("hevc",
                    cond("EqualsAny", "VideoProfile", "main|main 10"),
                    cond("LessThanEqual", "VideoBitDepth", "10"),
                    cond("LessThanEqual", "VideoLevel", "153"),
                    cond("LessThanEqual", "Width", "4096"),
                    cond("LessThanEqual", "Height", "2176"),
                    cond("LessThanEqual", "VideoBitrate", "80000000"),
                    cond("LessThanEqual", "VideoFramerate", "60"),
                    cond("EqualsAny", "VideoRangeType", if (mode == DeviceProfile.Mode.H264) "SDR" else HEVC_RANGES),
                ))
                add(codec("vp9",
                    cond("EqualsAny", "VideoProfile", "profile 0|profile 1|profile 2|profile 3|0|1|2|3"),
                    cond("LessThanEqual", "VideoBitDepth", "10"),
                    cond("LessThanEqual", "VideoBitrate", "60000000"),
                    cond("LessThanEqual", "VideoLevel", "153"),
                    cond("LessThanEqual", "Width", "4096"),
                    cond("LessThanEqual", "Height", "2176"),
                    cond("EqualsAny", "VideoRangeType", "SDR|HDR10|HLG"),
                ))
                for (legacy in listOf("mpeg4", "vp8")) add(codec(legacy,
                    cond("LessThanEqual", "Width", "1920"),
                    cond("LessThanEqual", "Height", "1088"),
                    cond("LessThanEqual", "VideoBitDepth", "8"),
                ))
                add(codec("mpeg2video",
                    cond("LessThanEqual", "Width", "1920"),
                    cond("LessThanEqual", "Height", "1088"),
                ))
            })
            put("SubtitleProfiles", buildJsonArray {
                for (f in listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "ttml")) add(sub(f, "External"))
                // Media3 renders text, PGS (S_HDMV/PGS) and VobSub tracks embedded in MKV/MP4 itself.
                for (f in listOf("srt", "subrip", "ass", "ssa")) add(sub(f, "Embed"))
                for (f in listOf("pgssub", "pgs", "dvdsub")) add(sub(f, "Embed", "mkv"))
                add(sub("dvbsub", "Embed", "mkv,ts,mpegts"))
                // Image subtitles in an HLS transcode: burn in only that stream.
                for (f in listOf("pgssub", "dvdsub", "dvbsub")) add(sub(f, "Encode"))
            })
        }
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

    private fun codec(name: String, vararg conditions: JsonObject) = buildJsonObject {
        put("Type", "Video")
        put("Codec", name)
        put("Conditions", JsonArray(conditions.toList()))
    }

    private fun sub(format: String, method: String, container: String? = null) = buildJsonObject {
        put("Format", format); put("Method", method)
        if (container != null) put("Container", container)
    }
    }
}
