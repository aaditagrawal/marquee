package com.mav.marquee.player

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Profiles derived from fake capability inputs; pure JVM, no device or server. */
class DeviceCapabilityProfileTest {
    private fun JsonObject.codecProfile(codec: String) =
        arr("CodecProfiles").objs().firstOrNull { it.str("Type") == "Video" && it.str("Codec") == codec }
    private fun JsonObject.condition(property: String) = arr("Conditions").objs().first { it.str("Property") == property }.str("Value")
    private fun JsonObject.directVideo(container: String) =
        arr("DirectPlayProfiles").objs().firstOrNull { it.str("Container") == container }?.str("VideoCodec")
    private fun JsonObject.transcode() = arr("TranscodingProfiles").objs().first()

    @Test fun generic4kHdr10Device() {
        val caps = ReferenceDevices.GENERIC_4K_HDR10
        val p = DeviceProfile.build(caps = caps)
        assertEquals("Marquee", p.str("Name"))
        assertEquals("hevc,h264,vp9,mpeg2video", p.directVideo("mkv,webm"))
        assertEquals("hevc,h264,vp9", p.directVideo("mp4,m4v,mov"))
        val hevc = p.codecProfile("hevc")!!
        assertEquals("4096", hevc.condition("Width"))
        assertEquals("2304", hevc.condition("Height"))
        assertEquals("153", hevc.condition("VideoLevel"))
        assertEquals("10", hevc.condition("VideoBitDepth"))
        assertEquals("main|main 10", hevc.condition("VideoProfile"))
        val ranges = hevc.condition("VideoRangeType")!!.split('|')
        assertTrue(ranges.containsAll(listOf("SDR", "HDR10", "HLG", "HDR10Plus", "DOVIWithHDR10", "DOVIWithEL")))
        assertFalse("no DV decoder or DV display", "DOVI" in ranges)
        assertEquals("SDR|HDR10|HLG", p.codecProfile("vp9")!!.condition("VideoRangeType"))
        assertEquals("high|main|baseline|constrained baseline", p.codecProfile("h264")!!.condition("VideoProfile"))
        assertNull("no AV1 decoder", p.codecProfile("av1"))
        assertEquals("hevc", p.transcode().str("VideoCodec"))
        assertEquals("eac3", p.transcode().str("AudioCodec"))
        assertEquals("6", p.transcode().str("MaxAudioChannels"))
        assertEquals("ac3,aac", DeviceProfile.build(ac3Fallback = true, caps = caps).transcode().str("AudioCodec"))
        // H.264 fallback keeps the single-codec transcode with a 1080p ceiling.
        val h264 = DeviceProfile.build(mode = DeviceProfile.Mode.H264, caps = caps)
        assertEquals("h264", h264.transcode().str("VideoCodec"))
        assertEquals("1920", h264.transcode().condition("Width"))
        assertEquals("1080", h264.transcode().condition("Height"))
        assertEquals("SDR", h264.codecProfile("hevc")!!.condition("VideoRangeType"))
        assertEquals("SDR,HDR10,HLG", DeviceProfile.remuxRanges(caps))
        val limits = StreamLimits.of(caps)
        assertTrue(limits.hevc && limits.hevcMain10)
        assertEquals(4096 to 2176, limits.hevcMaxWidth to limits.hevcMaxHeight)
        assertEquals(42, limits.h264Level)
    }

    @Test fun dolbyVisionDecoderOnlyAdvertisedWithDvDisplay() {
        val dvNoDisplay = ReferenceDevices.GENERIC_4K_HDR10.copy(dolbyVisionDecoder = true)
        val ranges = DeviceProfile.build(caps = dvNoDisplay).codecProfile("hevc")!!.condition("VideoRangeType")!!.split('|')
        assertFalse("DOVI" in ranges)
        assertFalse("a DV decoder would receive P7/P8 without a DV display", "DOVIWithHDR10" in ranges)
        val dvDisplay = dvNoDisplay.copy(displayHdr = dvNoDisplay.displayHdr + DeviceCapabilities.Hdr.DOLBY_VISION)
        val dvRanges = DeviceProfile.build(caps = dvDisplay).codecProfile("hevc")!!.condition("VideoRangeType")!!.split('|')
        assertTrue(dvRanges.containsAll(listOf("DOVI", "DOVIWithHDR10")))
    }

    @Test fun sdr1080pDeviceNeverAsksForHevc() {
        val caps = ReferenceDevices.SDR_1080P
        for (mode in DeviceProfile.Mode.entries) {
            val p = DeviceProfile.build(mode = mode, videoCodec = "hevc", caps = caps)
            assertEquals("h264", p.transcode().str("VideoCodec"))
            assertEquals("1920", p.transcode().condition("Width"))
            assertEquals("1080", p.transcode().condition("Height"))
            assertNull(p.codecProfile("hevc"))
            assertEquals("h264", p.directVideo("mkv,webm"))
            assertEquals("aac", p.transcode().str("AudioCodec"))
            assertEquals("2", p.transcode().str("MaxAudioChannels"))
        }
        val h264 = DeviceProfile.build(caps = caps).codecProfile("h264")!!
        assertEquals("41", h264.condition("VideoLevel"))
        assertEquals("SDR", h264.condition("VideoRangeType"))
        assertEquals("SDR", DeviceProfile.remuxRanges(caps))
        val limits = StreamLimits.of(caps)
        assertFalse(limits.hevc)
        assertEquals(41, limits.h264Level)
        assertEquals(1920 to 1088, limits.h264CopyWidth to limits.h264CopyHeight)
    }

    @Test fun detectionFailureUsesSafeProfile() {
        val p = DeviceProfile.build(caps = ReferenceDevices.FAILED)
        assertEquals("h264", p.directVideo("mkv,webm"))
        assertEquals("h264", p.directVideo("ts,mpegts,m2ts"))
        val h264 = p.codecProfile("h264")!!
        assertEquals("1920", h264.condition("Width"))
        assertEquals("1080", h264.condition("Height"))
        assertEquals("41", h264.condition("VideoLevel"))
        assertEquals("30", h264.condition("VideoFramerate"))
        assertEquals("8", h264.condition("VideoBitDepth"))
        assertEquals(listOf("h264"), p.arr("CodecProfiles").objs().filter { it.str("Type") == "Video" }.map { it.str("Codec") })
        assertEquals("aac,mp3", p.arr("DirectPlayProfiles").objs().first { it.str("Container") == "mkv,webm" }.str("AudioCodec"))
        assertEquals("h264", p.transcode().str("VideoCodec"))
        assertEquals("aac", p.transcode().str("AudioCodec"))
        assertEquals("2", p.transcode().str("MaxAudioChannels"))
        // A probe that throws never reaches playback: it degrades to the same safe input.
        val failed = PlaybackCapabilities.detectSafely { error("MediaCodecList unavailable") }
        assertTrue(failed.probeFailed)
        assertEquals(DeviceProfile.build(caps = failed).toString(),
            DeviceProfile.build(caps = DeviceCapabilities.SAFE.copy(manufacturer = failed.manufacturer, model = failed.model)).toString())
        assertNotNull(StreamLimits.of(failed))
        assertFalse(StreamLimits.of(failed).hevc)
    }

    @Test fun codecConstantMapping() {
        assertEquals(153, CodecMapping.level("hevc", android.media.MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51))
        assertEquals(153, CodecMapping.level("hevc", android.media.MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51))
        assertEquals(42, CodecMapping.level("h264", android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel42))
        assertEquals("main 10", CodecMapping.profile("hevc", android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10))
        assertEquals("constrained high", CodecMapping.profile("h264", android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh))
        assertEquals(10, CodecMapping.bitDepth("vp9", android.media.MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR))
        assertEquals(8, CodecMapping.bitDepth("hevc", android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain))
    }
}
