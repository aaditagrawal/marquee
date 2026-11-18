package com.mav.marquee.player

import android.app.Application
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PlaybackRobustnessTest {
    @Test fun exactRestartRetainsRequestedTargetAndForwardOnlyMapNeverByteSeeks() {
        assertEquals(3456L, ExactPlaybackStart.localMs(701945, 698489))
        assertEquals(5621L, ExactPlaybackStart.localMs(709907, 704286))
        assertEquals(701945L, ExactPlaybackStart.localMs(701945, 0))
        assertEquals(0L, ExactPlaybackStart.localMs(100, 200))
        val map = PrerollSeekMap(C.TIME_UNSET)
        assertTrue(map.isSeekable)
        assertEquals(C.TIME_UNSET, map.durationUs)
        for (target in listOf(3_456_000L, 5_621_000L)) {
            val points = map.getSeekPoints(target)
            assertEquals(0L, points.first.position)
            assertEquals(target, androidx.media3.exoplayer.SeekParameters.EXACT.resolveSeekPositionUs(
                target, points.first.timeUs, points.second.timeUs))
        }
    }

    @Test fun indexedStartsRewindResumeAndChooseNextCueForDistantRestartSeeks() {
        val map = androidx.media3.extractor.ChunkIndex(intArrayOf(1, 1, 1, 1), longArrayOf(0, 100, 200, 300),
            longArrayOf(697_989_000, 10_427_000, 10_427_000, 10_427_000),
            longArrayOf(0, 697_989_000, 708_416_000, 718_843_000))
        assertEquals(698_239L, RemuxSyncIndex.restartMs(map, 701_945))
        assertEquals(697_989L, RemuxSyncIndex.restartMs(map, 701_945, remux = false))
        assertEquals(708_666L, RemuxSyncIndex.restartMs(map, 701_945, preferNext = true))
        assertEquals(708_666L, RemuxSyncIndex.restartMs(map, 709_907, preferNext = true))
        assertEquals(719_093L, RemuxSyncIndex.restartMs(map, 711_945, preferNext = true))
        assertEquals(0L, RemuxSyncIndex.restartMs(map, 0))
        assertEquals(701_945L, RemuxSyncIndex.restartMs(androidx.media3.extractor.SeekMap.Unseekable(900_000_000), 701_945))
        val direct = androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC
        assertEquals(715_465_000L, direct.resolveSeekPositionUs(718_310_000, 715_465_000, 723_911_000))
    }

    @Test fun normalTimedStartHasFiniteDurationWithoutAdvertisingByteSeeks() {
        val normal = PrewarmExtractors.timedSeekMap(8_000_000_000, exact = false)
        assertFalse(normal.isSeekable)
        assertEquals(8_000_000_000L, normal.durationUs)
        assertEquals(0L, normal.getSeekPoints(9_000_000).first.position)
        val fallback = PrewarmExtractors.timedSeekMap(8_000_000_000, exact = true)
        assertTrue(fallback.isSeekable)
        assertEquals(0L, fallback.getSeekPoints(9_000_000).first.position)
    }

    @Test fun pcmBufferReservesHalfSecondAndRespectsPlatformMinimum() {
        val provider = MarqueeRenderersFactory.pcmBufferProvider()
        for ((rate, frameBytes) in listOf(48_000 to 4, 44_100 to 4, 48_000 to 12)) {
            val bytes = provider.getBufferSizeInBytes(4096, C.ENCODING_PCM_16BIT,
                DefaultAudioSink.OUTPUT_MODE_PCM, frameBytes, rate, -1, 1.0)
            assertEquals(rate * frameBytes / 2, bytes)
            assertEquals(0, bytes % frameBytes)
        }
        assertEquals(120_000, provider.getBufferSizeInBytes(120_000, C.ENCODING_PCM_16BIT,
            DefaultAudioSink.OUTPUT_MODE_PCM, 4, 48_000, -1, 1.0))
    }

    @Test fun fixedPanelLabelsFilmNtscBroadcastAndHighRateCorrectly() {
        for (fps in listOf(24f, 23.976025f)) {
            assertEquals("1.25× · smooth", PlaybackCadence.label(1.25f, fps))
            assertEquals("1.5× · may judder", PlaybackCadence.label(1.5f, fps))
            assertEquals("1× · may judder", PlaybackCadence.label(1f, fps))
        }
        for (fps in listOf(25f, 50f)) {
            assertTrue(PlaybackCadence.speeds(fps).contains(1.2f))
            assertEquals("1.2× · smooth", PlaybackCadence.label(1.2f, fps))
        }
        assertFalse(PlaybackCadence.speeds(24f).contains(1.2f))
        assertEquals("1.25× · may judder", PlaybackCadence.label(1.25f, 23.809525f))
        assertEquals("2× · smooth", PlaybackCadence.label(2f, 29.97003f))
        assertEquals("1× · smooth", PlaybackCadence.label(1f, 59.94006f))
        assertEquals("1.25× · exceeds 60 fps", PlaybackCadence.label(1.25f, 60f))
        assertEquals("1×", PlaybackCadence.label(1f, -1f))
        assertEquals("1×", PlaybackCadence.label(1f, Float.NaN))
    }

    @Test fun droppedRateExcludesPauseAndRebufferTimeAndResetsForNewStream() {
        val rate = PlaybackFrameRate()
        rate.playing(true, 1000)
        assertEquals(8.0, rate.droppedPerMinute(4, 31_000), .001)
        rate.playing(false, 31_000)
        assertEquals(8.0, rate.droppedPerMinute(4, 91_000), .001)
        rate.playing(true, 91_000)
        assertEquals(4.0, rate.droppedPerMinute(4, 121_000), .001)
        rate.reset(false, 121_000)
        assertEquals(0.0, rate.droppedPerMinute(0, 150_000), .001)
    }

    @Test fun pgsWithHeavyAudioUsesMatroskaAndOnlyUnsupportedBitmapWarns() {
        fun stream(codec: String, delivery: String? = null) = buildJsonObject {
            put("Codec", codec); delivery?.let { put("DeliveryMethod", it) }
        }
        val bitmap = stream("pgssub", "Embed")
        assertFalse(TrackConversionPolicy.needsVideoConversion(stream("truehd"), bitmap))
        assertTrue(TrackConversionPolicy.canEmbedBitmap(bitmap))
        assertTrue(TrackConversionPolicy.needsVideoConversion(stream("truehd"), stream("dvbsub", "Encode")))
        assertFalse(TrackConversionPolicy.needsVideoConversion(stream("dts", "Encode"), bitmap))
        assertFalse(TrackConversionPolicy.needsVideoConversion(stream("eac3"), bitmap))
        assertFalse(TrackConversionPolicy.needsVideoConversion(stream("truehd"), null))
        val text = stream("srt", "External")
        assertTrue(TrackConversionPolicy.isExternalText(text))
        assertFalse(TrackConversionPolicy.needsVideoConversion(stream("truehd"), text))
    }
}
