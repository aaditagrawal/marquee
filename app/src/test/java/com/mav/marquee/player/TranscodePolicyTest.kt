package com.mav.marquee.player

import android.app.Application
import android.net.Uri
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class TranscodePolicyTest {
    // Expectations below are the measured reference TV's profile (see DeviceProfileBaselineTest).
    @org.junit.Before fun referenceDevice() { PlaybackCapabilities.override = ReferenceDevices.BRAVIA_VU1 }
    @org.junit.After fun clearReferenceDevice() { PlaybackCapabilities.override = null }

    @Test fun defaultAndExplicitAutoDoNotCapDirectPlay() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val sp = ctx.getSharedPreferences("marquee_prefs", 0)
        sp.edit().clear().commit()
        val prefs = PlayerPrefs(ctx)
        assertEquals(0, prefs.maxBitrate)
        sp.edit().putInt("maxBitrateBps", 0).commit()
        assertEquals(0, prefs.maxBitrate)
        val p = DeviceProfile.build(prefs.maxBitrate)
        val body = JfApi.playbackInfoBody("user", p, prefs.maxBitrate, 0, null, null, -1, DeviceProfile.Mode.Direct)
        assertEquals(DeviceProfile.UNLIMITED_BITRATE, body.int("MaxStreamingBitrate"))
        assertEquals(DeviceProfile.UNLIMITED_BITRATE, p.int("MaxStreamingBitrate"))
        assertEquals(DeviceProfile.UNLIMITED_BITRATE, p.int("MaxStaticBitrate"))
        sp.edit().putInt("maxBitrateBps", 4_000_000).commit()
        assertEquals(4_000_000, prefs.maxBitrate)
        assertEquals(4_000_000, DeviceProfile.build(prefs.maxBitrate).int("MaxStreamingBitrate"))
    }

    @Test fun fallbackTriesCopyThenHevcBeforeH264() {
        val order = generateSequence(DeviceProfile.Mode.Direct, TranscodePolicy::next).toList()
        assertEquals(listOf(DeviceProfile.Mode.Direct, DeviceProfile.Mode.Remux, DeviceProfile.Mode.Hevc, DeviceProfile.Mode.H264), order)
        for (mode in DeviceProfile.Mode.entries) {
            val body = JfApi.playbackInfoBody("user", DeviceProfile.build(mode = mode), 0, 0, null, null, -1, mode)
            assertEquals(mode == DeviceProfile.Mode.Direct || mode == DeviceProfile.Mode.Remux, body.bool("AllowVideoStreamCopy"))
        }
    }

    @Test fun h264WidthsAlignWithoutExceedingDecoderLimits() {
        assertEquals(1584 to 1080, TranscodePolicy.h264Size(3164, 2152))
        assertEquals(1920 to 1080, TranscodePolicy.h264Size(3840, 2160))
        assertEquals(1920 to 800, TranscodePolicy.h264Size(3840, 1600))
        assertEquals(1280 to 720, TranscodePolicy.h264Size(1280, 720))
        for ((w,h) in listOf(4096 to 2176, 1080 to 1920, 1918 to 1078, 640 to 480)) {
            val (ow,oh) = TranscodePolicy.h264Size(w,h)
            assertTrue(ow <= 1920 && oh <= 1080)
            assertEquals(0, ow % 16); assertEquals(0, oh % 2)
        }
    }
    @Test fun encodingTargetsDoNotCopyOrInheritH264CapsIntoHevc() {
        val hdr = buildJsonObject { put("Codec", "hevc"); put("Width", 3164); put("Height", 2152); put("ColorTransfer", "smpte2084"); put("DvProfile", 8) }
        val server = Uri.parse("http://server/master.m3u8?VideoCodec=h264&MaxWidth=1920&MaxHeight=1080&AllowVideoStreamCopy=true&AudioCodec=eac3&AllowAudioStreamCopy=true&MaxFramerate=23.976&ApiKey=secret")
        val hevc = Uri.parse(StreamResolver.transcodeUrl(server, hdr, false, 0))
        assertEquals("hevc", hevc.getQueryParameter("VideoCodec"))
        assertEquals("ac3", hevc.getQueryParameter("AudioCodec"))
        assertEquals("false", hevc.getQueryParameter("AllowAudioStreamCopy"))
        assertEquals("false", hevc.getQueryParameter("AllowVideoStreamCopy"))
        assertEquals("4096", hevc.getQueryParameter("MaxWidth"))
        assertEquals("2176", hevc.getQueryParameter("MaxHeight"))
        assertEquals("main10,main", hevc.getQueryParameter("hevc-profile"))
        assertEquals("23.976", hevc.getQueryParameter("MaxFramerate"))
        assertEquals("/main.m3u8", hevc.path)
        assertFalse(hevc.toString().contains("secret"))
        val h264 = Uri.parse(StreamResolver.transcodeUrl(server, hdr, true, 6_000_000))
        assertEquals("1584", h264.getQueryParameter("Width"))
        assertEquals("1080", h264.getQueryParameter("Height"))
        assertEquals("6000000", h264.getQueryParameter("VideoBitrate"))
        assertEquals("8", h264.getQueryParameter("h264-videobitdepth"))
    }

    @Test fun compatibleH264AudioRemuxKeepsVideoWhileHigh10EncodesHevc() {
        fun video(depth: Int) = buildJsonObject { put("Codec", "h264"); put("BitDepth", depth); put("Width", 1920); put("Height", 1080); put("Level", 41); put("Profile", if (depth == 10) "High 10" else "High") }
        assertEquals("h264", StreamResolver.profileFor(video(8), 0, DeviceProfile.Mode.Remux).arr("TranscodingProfiles").objs().first().str("VideoCodec"))
        assertEquals("hevc", StreamResolver.profileFor(video(10), 0, DeviceProfile.Mode.Direct).arr("TranscodingProfiles").objs().first().str("VideoCodec"))
        assertEquals("hevc", StreamResolver.profileFor(video(8), 0, DeviceProfile.Mode.Hevc).arr("TranscodingProfiles").objs().first().str("VideoCodec"))
    }

}
