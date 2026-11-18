package com.mav.marquee.player

import android.app.Application
import android.net.Uri
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class StreamResolverTest {
    // Expectations below are the measured reference TV's profile (see DeviceProfileBaselineTest).
    @org.junit.Before fun referenceDevice() { PlaybackCapabilities.override = ReferenceDevices.BRAVIA_VU1 }
    @org.junit.After fun clearReferenceDevice() { PlaybackCapabilities.override = null }

    private val json = Json { prettyPrint = true }
    private val root = File(System.getProperty("marquee.projectRoot") ?: "..") // repository root
    /** Optional maintainer-supplied live inputs (opt-in; absent in a clean checkout). */
    private val evidence = File(root, "bench/evidence/offline-player")
    /** Reports written by these tests; never the source tree. */
    private val reports = File(com.mav.marquee.offline.OfflinePaths.evidence, "offline-player").apply { mkdirs() }
    private fun fixture(name: String) = Json.parseToJsonElement(com.mav.marquee.offline.OfflinePaths.resource(name))

    @Test fun removesBlankNamesDuplicatesAndEveryTokenSpelling() {
        val url = Uri.parse("http://server/master.m3u8?&DeviceId=first&deviceid=second&ApiKey=secret&api_key=secret&access_token=secret&Token=secret&VideoCodec=hevc&MaxWidth=1920&MaxHeight=1080&hevc-level=153")
        // This is the actual Android Uri behavior that made the old resolver emit '='.
        val old = url.buildUpon().clearQuery()
        url.queryParameterNames.forEach { old.appendQueryParameter(it, url.getQueryParameter(it).orEmpty()) }
        assertTrue(old.build().encodedQuery!!.split('&').contains("="))
        val fixed = Uri.parse(StreamResolver.safeUrl(url, false, "hevc"))
        assertFalse(fixed.queryParameterNames.any { it.isBlank() })
        assertEquals("first", fixed.getQueryParameter("DeviceId"))
        assertFalse(fixed.queryParameterNames.contains("deviceid"))
        assertFalse(fixed.toString().contains("secret"))
        assertEquals("4096", fixed.getQueryParameter("MaxWidth"))
        assertEquals("2176", fixed.getQueryParameter("MaxHeight"))
        assertEquals("153", fixed.getQueryParameter("hevc-level"))
    }

    @Test fun capsOnlyH264OutputAndKeepsCodecConditionsScoped() {
        for (mode in DeviceProfile.Mode.entries) {
            val p = DeviceProfile.build(mode = mode, videoCodec = "hevc")
            val output = p.arr("TranscodingProfiles").objs().first()
            assertEquals(if (mode == DeviceProfile.Mode.H264) "h264" else "hevc", output.str("VideoCodec"))
            if (mode != DeviceProfile.Mode.H264) assertNull(output["Conditions"])
            val limits = p.arr("CodecProfiles").objs().associate { codec ->
                codec.str("Codec") to codec.arr("Conditions").objs().associate { it.str("Property") to it.str("Value") }
            }
            assertEquals("1920", limits["h264"]?.get("Width"))
            assertEquals("1088", limits["h264"]?.get("Height"))
            assertEquals("42", limits["h264"]?.get("VideoLevel"))
            assertEquals("4096", limits["hevc"]?.get("Width"))
            assertEquals("2176", limits["hevc"]?.get("Height"))
            assertEquals("153", limits["hevc"]?.get("VideoLevel"))
        }
        val capped = Uri.parse(StreamResolver.safeUrl(Uri.parse("http://server/master.m3u8?VideoCodec=hevc&Width=3840&Height=2160&MaxWidth=4096&AllowVideoStreamCopy=true"), true))
        assertEquals("h264", capped.getQueryParameter("VideoCodec"))
        assertEquals("1920", capped.getQueryParameter("MaxWidth"))
        assertEquals("1080", capped.getQueryParameter("MaxHeight"))
        assertEquals("false", capped.getQueryParameter("AllowVideoStreamCopy"))
        assertNull(capped.getQueryParameter("Width"))
    }

    @Test fun publishedDeviceProfileMatchesCode() {
        val published = File(root, "docs/device-profile.json")
        assertTrue("docs/device-profile.json must be published alongside DeviceProfile.build()", published.exists())
        assertEquals(DeviceProfile.build(), Json.parseToJsonElement(published.readText()))
    }


    @Test fun pgsNeverForcesHdrVideoEncodingAcrossStartResumeAndSeek() {
        val fixture = fixture("player-audio-remux.json").jsonObject
        val original = fixture.obj("info")!!
        val ms = original.arr("MediaSources").objs().first()
        val url = Uri.parse(ms.str("TranscodingUrl")).buildUpon()
            .appendQueryParameter("SubtitleMethod", "Encode").build().toString()
        val response = JsonObject(original + ("MediaSources" to JsonArray(listOf(JsonObject(ms +
            ("TranscodingUrl" to JsonPrimitive(url)))))))
        for (start in listOf(0L, 701_945L, 711_945L)) for (prefer in listOf(true, false)) {
            val r = StreamResolver.fromPlaybackInfo(fixture.obj("item")!!, response, DeviceProfile.Mode.Direct,
                80_000_000, 0, 1, null, "http://server", "host", start, prefer)
            assertTrue(r.videoCopy)
            assertTrue(r.isProgressiveRemux)
            val query = r.mediaItem.localConfiguration!!.uri
            assertEquals("hevc", query.getQueryParameter("VideoCodec"))
            assertEquals("Embed", query.getQueryParameter("SubtitleMethod"))
            assertEquals("1", query.getQueryParameter("SubtitleStreamIndex"))
            assertEquals("false", query.getQueryParameter("AlwaysBurnInSubtitleWhenTranscoding"))
            assertEquals((start * 10_000).toString(), query.getQueryParameter("StartTimeTicks"))
        }
    }

    @Test fun unsafeDolbyVisionAlwaysUsesH264() {
        val p5 = buildJsonObject { put("Codec", "hevc"); put("DvProfile", 5) }
        val invalid = buildJsonObject { put("Codec", "hevc"); put("VideoRangeType", "DOVIInvalid") }
        for (video in listOf(p5, invalid)) for (mode in DeviceProfile.Mode.entries) {
            assertEquals(DeviceProfile.Mode.H264, StreamResolver.effectiveMode(video, mode))
            assertEquals("h264", StreamResolver.profileFor(video, 80_000_000, mode).arr("TranscodingProfiles").objs().first().str("VideoCodec"))
        }
    }

    @Test fun progressiveRemuxKeepsAudioHdrAndAuthenticationContract() {
        val hls = Uri.parse("http://server/jellyfin/videos/source/master.m3u8?&api_key=secret&DeviceId=host&PlaySessionId=session&MediaSourceId=source&VideoCodec=hevc&AllowVideoStreamCopy=true&AudioCodec=eac3&AudioStreamIndex=0&TranscodingMaxAudioChannels=6&AudioBitrate=640000&MaxWidth=4096&MaxHeight=2176&hevc-rangetype=HDR10&SegmentLength=4&SegmentContainer=mp4&MinSegments=1&BreakOnNonKeyFrames=True&CopyTimestamps=true&StartTimeTicks=1200000000&Static=true")
        val progressive = Uri.parse(StreamResolver.progressiveRemuxUrl(hls))
        assertEquals("/jellyfin/videos/source/stream.mkv", progressive.path)
        for (key in listOf("DeviceId", "PlaySessionId", "MediaSourceId", "VideoCodec", "AllowVideoStreamCopy",
            "AudioCodec", "AudioStreamIndex", "TranscodingMaxAudioChannels", "AudioBitrate", "MaxWidth", "MaxHeight", "hevc-rangetype")) {
            assertEquals(key, hls.getQueryParameter(key), progressive.getQueryParameter(key))
        }
        for (key in listOf("api_key", "SegmentLength", "SegmentContainer", "MinSegments", "BreakOnNonKeyFrames")) {
            assertNull(progressive.getQueryParameter(key))
        }
        assertEquals("false", progressive.getQueryParameter("Static"))
        assertEquals("true", progressive.getQueryParameter("CopyTimestamps"))
        assertEquals("false", progressive.getQueryParameter("AllowAudioStreamCopy"))
        assertEquals("0", progressive.getQueryParameter("StartTimeTicks"))
        assertEquals("Streaming", progressive.getQueryParameter("Context"))
        assertFalse(progressive.toString().contains("secret"))
        val disabled = Uri.parse(StreamResolver.progressiveRemuxUrl(Uri.parse(hls.toString() + "&SubtitleStreamIndex=2"), -1))
        assertEquals(listOf("-1"), disabled.getQueryParameters("SubtitleStreamIndex"))
    }

    @Test fun audioConversionSeeksUseTimedMkvAndRecoveryUsesHls() {
        val audio = listOf("AudioCodecNotSupported", "AudioProfileNotSupported")
        assertTrue(StreamResolver.useProgressiveRemux(DeviceProfile.Mode.Direct, true, audio, 0, true))
        assertTrue(StreamResolver.useProgressiveRemux(DeviceProfile.Mode.Direct, true, audio, 120_000, true))
        assertFalse(StreamResolver.useProgressiveRemux(DeviceProfile.Mode.Direct, true, audio, 0, false))
        for (mode in listOf(DeviceProfile.Mode.Remux, DeviceProfile.Mode.H264))
            assertFalse(StreamResolver.useProgressiveRemux(mode, true, audio, 0, true))
        for (reasons in listOf(emptyList(), audio + "ContainerNotSupported", audio + "VideoCodecNotSupported", audio + "SubtitleCodecNotSupported"))
            assertFalse(StreamResolver.useProgressiveRemux(DeviceProfile.Mode.Direct, true, reasons, 0, true))
        assertFalse(StreamResolver.useProgressiveRemux(DeviceProfile.Mode.Direct, false, audio, 0, true))
    }

    @Test fun audioOnlyConversionResponseUsesTimedMkvResumeAndKeepsHlsRecovery() {
        val c = fixture("player-audio-remux.json").jsonObject
        fun resolve(start: Long, prefer: Boolean) = StreamResolver.fromPlaybackInfo(c.obj("item")!!, c.obj("info")!!,
            DeviceProfile.Mode.Direct, 80_000_000, 0, -1, null, "http://server", "host", start, prefer)
        val progressive = resolve(0, true)
        assertTrue(progressive.isProgressiveRemux)
        assertTrue(progressive.videoCopy)
        assertEquals("eac3", progressive.mediaItem.localConfiguration!!.uri.getQueryParameter("AudioCodec"))
        assertEquals("-1", progressive.mediaItem.localConfiguration!!.uri.getQueryParameter("SubtitleStreamIndex"))
        assertEquals(androidx.media3.common.MimeTypes.VIDEO_MATROSKA, progressive.mediaItem.localConfiguration!!.mimeType)
        assertTrue(progressive.durationMs!! > 120_000)
        val resume = resolve(701_945, true)
        assertTrue(resume.isProgressiveRemux)
        assertEquals(701_945L, resume.progressiveStartMs)
        assertEquals("7019450000", resume.mediaItem.localConfiguration!!.uri.getQueryParameter("StartTimeTicks"))
        for (hls in listOf(resolve(120_000, false), resolve(0, false))) {
            assertFalse(hls.isProgressiveRemux)
            assertTrue(hls.videoCopy)
            assertEquals(androidx.media3.common.MimeTypes.APPLICATION_M3U8, hls.mediaItem.localConfiguration!!.mimeType)
            assertEquals("eac3", hls.mediaItem.localConfiguration!!.uri.getQueryParameter("AudioCodec"))
            assertEquals(progressive.playSessionId, hls.playSessionId)
            assertEquals("main.m3u8", hls.mediaItem.localConfiguration!!.uri.lastPathSegment)
            assertEquals(DeviceProfile.HEVC_REMUX_RANGES, hls.mediaItem.localConfiguration!!.uri.getQueryParameter("hevc-rangetype"))
        }
    }

    @Test fun negotiatedDisabledSubtitleWithNullCallerChoiceKeepsHdrVideoCopy() {
        val c = fixture("player-audio-remux.json").jsonObject
        assertEquals(-1, c.obj("info")!!.arr("MediaSources").objs().first().int("DefaultSubtitleStreamIndex"))
        for (start in listOf(0L, 701_945L)) for (progressive in listOf(true, false)) {
            val r = StreamResolver.fromPlaybackInfo(c.obj("item")!!, c.obj("info")!!,
                DeviceProfile.Mode.Direct, 80_000_000, 0, null, null, "http://server", "host", start, progressive)
            assertTrue(r.videoCopy)
            assertEquals(-1, r.subtitleIndex)
            val url = r.mediaItem.localConfiguration!!.uri
            assertEquals("hevc", url.getQueryParameter("VideoCodec"))
            assertEquals("-1", url.getQueryParameter("SubtitleStreamIndex"))
            assertEquals("4096", url.getQueryParameter("MaxWidth"))
            assertEquals("2176", url.getQueryParameter("MaxHeight"))
        }
    }

    @Test fun hdrHlsPinsCopyPlaylistAndRepairsOnlyRangeDowngrades() {
        val c = fixture("player-audio-remux.json").jsonObject
        val item = c.obj("item")!!
        val video = item.arr("MediaSources").objs().first().arr("MediaStreams").objs().first { it.str("Type") == "Video" }
        assertTrue(StreamResolver.hasHdrBaseLayer(video))
        val original = c.obj("info")!!
        fun response(reasons: String, burnIn: Boolean = false): JsonObject {
            val ms = original.arr("MediaSources").objs().first()
            val b = Uri.parse(ms.str("TranscodingUrl")).buildUpon().clearQuery()
            b.appendQueryParameter("DeviceId", "host").appendQueryParameter("PlaySessionId", "session")
                .appendQueryParameter("VideoCodec", "hevc").appendQueryParameter("AudioCodec", "eac3")
                .appendQueryParameter("AllowVideoStreamCopy", "false").appendQueryParameter("hevc-rangetype", "SDR")
                .appendQueryParameter("VideoRangeType", "SDR").appendQueryParameter("hevc-videorangetype", "SDR")
                .appendQueryParameter("TranscodeReasons", reasons).appendQueryParameter("SubtitleMethod", if (burnIn) "Encode" else "External")
                .appendQueryParameter("StartTimeTicks", "7019450000")
            val updated = JsonObject(ms + ("TranscodingUrl" to JsonPrimitive(b.build().toString())))
            return JsonObject(original + ("MediaSources" to JsonArray(listOf(updated))))
        }
        for (start in listOf(0L, 701_945L, 1_200_000L)) for (mode in listOf(DeviceProfile.Mode.Direct, DeviceProfile.Mode.Remux)) {
            val r = StreamResolver.fromPlaybackInfo(item, response("AudioCodecNotSupported,VideoRangeTypeNotSupported"),
                mode, 80_000_000, 0, -1, null, "http://server", "host", start, false)
            val url = r.mediaItem.localConfiguration!!.uri
            assertTrue(r.videoCopy)
            assertEquals("main.m3u8", url.lastPathSegment)
            assertEquals("true", url.getQueryParameter("AllowVideoStreamCopy"))
            assertEquals("hevc", url.getQueryParameter("VideoCodec"))
            assertEquals("eac3", url.getQueryParameter("AudioCodec"))
            assertEquals(DeviceProfile.HEVC_REMUX_RANGES, url.getQueryParameter("hevc-rangetype"))
            assertEquals("-1", url.getQueryParameter("SubtitleStreamIndex"))
            assertNull(url.getQueryParameter("VideoRangeType"))
            assertNull(url.getQueryParameter("hevc-videorangetype"))
            assertNull(url.getQueryParameter("StartTimeTicks"))
        }
        for (reason in listOf("VideoCodecNotSupported", "VideoLevelNotSupported", "VideoBitrateNotSupported",
            "ContainerBitrateExceedsLimit", "SubtitleCodecNotSupported")) {
            val r = StreamResolver.fromPlaybackInfo(item, response("VideoRangeTypeNotSupported,$reason"),
                DeviceProfile.Mode.Direct, 80_000_000, 0, -1, null, "http://server", "host", 701_945, false)
            assertFalse(reason, r.videoCopy)
            assertEquals("hevc", r.mediaItem.localConfiguration!!.uri.getQueryParameter("VideoCodec"))
        }
        val burn = StreamResolver.fromPlaybackInfo(item, response("VideoRangeTypeNotSupported", true),
            DeviceProfile.Mode.Direct, 80_000_000, 0, 1, null, "http://server", "host", 701_945, false)
        assertTrue(burn.videoCopy)
        assertTrue(burn.isProgressiveRemux)
        assertEquals("Embed", burn.mediaItem.localConfiguration!!.uri.getQueryParameter("SubtitleMethod"))
        assertEquals("true", burn.mediaItem.localConfiguration!!.uri.getQueryParameter("CopyTimestamps"))
        val p5 = JsonObject(video + ("DvProfile" to JsonPrimitive(5)))
        assertFalse(StreamResolver.rangeOnlyHdrDowngrade(p5, listOf("VideoRangeTypeNotSupported")))
        assertEquals(DeviceProfile.Mode.H264, StreamResolver.effectiveMode(p5, DeviceProfile.Mode.Direct))
    }

    @Test fun hdrBaseLayerQueryKeepsHlgAndDoesNotTouchSdrTransport() {
        val hdr = buildJsonObject { put("Codec", "hevc"); put("ColorTransfer", "arib-std-b67"); put("DvProfile", 8) }
        assertTrue(StreamResolver.hasHdrBaseLayer(hdr))
        val invalidWithPq = buildJsonObject { put("Codec", "hevc"); put("VideoRangeType", "DOVIInvalid"); put("ColorTransfer", "smpte2084") }
        assertEquals(DeviceProfile.Mode.Direct, StreamResolver.effectiveMode(invalidWithPq, DeviceProfile.Mode.Direct))
        val cases = fixture("player-playback-info.json").jsonArray.objs()
        for (c in cases.filter { it.str("mode") == "Remux" }) {
            val r = StreamResolver.fromPlaybackInfo(c.obj("item")!!, c.obj("info")!!, DeviceProfile.Mode.Remux,
                80_000_000, c.int("audioIndex"), -1, null, "http://server", "host", 120_000, false)
            if (r.videoStream()?.str("ColorTransfer") !in setOf("smpte2084", "arib-std-b67"))
                assertEquals("master.m3u8", r.mediaItem.localConfiguration!!.uri.lastPathSegment)
        }
    }

    @Test fun originalDirectTitlesNeverEnterRemuxTransport() {
        val cases = fixture("player-direct-regression.json").jsonArray.objs()
        assertEquals(3, cases.size)
        for (c in cases) for (start in listOf(0L, 120_000L)) {
            val r = StreamResolver.fromPlaybackInfo(c.obj("item")!!, c.obj("info")!!, DeviceProfile.Mode.Direct,
                80_000_000, c.int("audio_index"), -1, null, "http://server", "host", start)
            assertEquals("DirectPlay", r.playMethod)
            assertFalse(r.isTranscode)
            assertFalse(r.isProgressiveRemux)
            assertFalse(r.videoCopy)
            assertEquals("true", r.mediaItem.localConfiguration!!.uri.getQueryParameter("static"))
            assertNull(r.mediaItem.localConfiguration!!.mimeType)
            assertEquals(c.int("audio_index"), r.audioIndex)
        }
    }

    @Test fun exportsExactKotlinRequestBodiesForHostCurl() {
        val itemsFile = File(evidence, "items.json")
        // Opt-in maintainer export; ordinary runs need no live server or credentials and report this as skipped.
        org.junit.Assume.assumeTrue("needs bench/evidence/offline-player/items.json", itemsFile.exists())
        val items = Json.parseToJsonElement(itemsFile.readText()).jsonArray.objs()
        val requests = buildJsonArray {
            for (item in items) for (mode in DeviceProfile.Mode.entries) {
                val video = item.arr("MediaSources").objs().first().arr("MediaStreams").objs().first { it.str("Type") == "Video" }
                val effective = StreamResolver.effectiveMode(video, mode)
                val bitrate = if (effective == DeviceProfile.Mode.H264) 12_000_000 else 80_000_000
                add(buildJsonObject {
                    put("item", item); put("mode", mode.name); put("startMs", 120_000)
                    put("request", JfApi.playbackInfoBody("host-user-placeholder",
                        StreamResolver.profileFor(video, bitrate, effective), bitrate, 120_000L * 10_000,
                        null, null, -1, effective))
                })
            }
        }
        File(evidence, "requests.json").writeText(json.encodeToString(JsonArray.serializer(), requests) + "\n") // opt-in export, beside its input
    }

    @Test fun replaysPlaybackInfoFixturesThroughProductionResolver() {
        val live = File(evidence, "playback-info-fixtures.json").takeIf { it.exists() }
        val cases = (live?.let { Json.parseToJsonElement(it.readText()) } ?: fixture("player-playback-info.json")).jsonArray.objs()
        val resolved = buildJsonArray {
            for (case in cases) {
                val item = case.obj("item")!!
                val mode = DeviceProfile.Mode.valueOf(case.str("mode")!!)
                val request = case.obj("request")!!
                val stream = StreamResolver.fromPlaybackInfo(item, case.obj("info")!!, mode,
                    request.int("MaxStreamingBitrate")!!, null, -1, null, case.str("server")!!, case.str("device")!!,
                    request.long("StartTimeTicks")!! / 10_000)
                val url = stream.mediaItem.localConfiguration!!.uri
                assertFalse(url.queryParameterNames.any { it.isBlank() })
                assertFalse(url.queryParameterNames.any { it.replace("_", "").lowercase() in setOf("apikey", "accesstoken", "token") })
                if (stream.videoCopy && stream.videoStream()?.str("Codec") == "hevc") {
                    assertEquals("4096", url.getQueryParameter("MaxWidth"))
                    assertEquals("2176", url.getQueryParameter("MaxHeight"))
                }
                if (stream.isTranscode && !stream.videoCopy) {
                    if (stream.mode == DeviceProfile.Mode.H264) {
                        val size = TranscodePolicy.h264Size(stream.videoStream()?.int("Width") ?: 1920, stream.videoStream()?.int("Height") ?: 1080)
                        assertEquals("h264", url.getQueryParameter("VideoCodec"))
                        assertEquals(size.first.toString(), url.getQueryParameter("Width"))
                        assertEquals(size.second.toString(), url.getQueryParameter("Height"))
                    } else {
                        assertEquals("hevc", url.getQueryParameter("VideoCodec"))
                        assertEquals("4096", url.getQueryParameter("MaxWidth"))
                        assertEquals("2176", url.getQueryParameter("MaxHeight"))
                    }
                }
                add(buildJsonObject {
                    put("title", item.str("Name")); put("itemId", stream.itemId); put("mode", mode.name)
                    put("url", url.toString()); put("playSessionId", stream.playSessionId); put("deviceId", case.str("device"))
                    put("videoCopy", stream.videoCopy); put("playMethod", stream.playMethod)
                    put("sourceVideo", stream.videoStream() ?: JsonObject(emptyMap()))
                    put("supportsDirectPlay", case.obj("info")!!.arr("MediaSources").objs().first().bool("SupportsDirectPlay"))
                    put("transcodeReasons", JsonArray(stream.transcodeReasons.map(::JsonPrimitive)))
                })
            }
        }
        File(reports, "resolved.json").writeText(json.encodeToString(JsonArray.serializer(), resolved) + "\n")
        assertEquals(12, resolved.size)
    }
    @Test fun speculativeResumeUsesSameIdentityAndExplicitServerTimelineOffset() {
        val c = fixture("player-audio-remux.json").jsonObject
        val cold = StreamResolver.fromPlaybackInfo(c.obj("item")!!, c.obj("info")!!,
            DeviceProfile.Mode.Direct, 80_000_000, 0, -1, null, "http://server", "host", 120_000, true)
        assertTrue(cold.isProgressiveRemux)
        val warm = requireNotNull(StreamResolver.speculativeProgressive(cold, 120_000))
        assertTrue(warm.isProgressiveRemux)
        assertEquals(120_000L, warm.progressiveStartMs)
        assertEquals("1200000000", warm.mediaItem.localConfiguration!!.uri.getQueryParameter("StartTimeTicks"))
        assertEquals(cold.playSessionId, warm.playSessionId)
        assertEquals(cold.mediaSourceId, warm.mediaSourceId)
        val originalUri = cold.mediaItem.localConfiguration!!.uri
        val warmedUri = warm.mediaItem.localConfiguration!!.uri
        for (key in originalUri.queryParameterNames.filter { it.equals("DeviceId", ignoreCase = true) })
            assertEquals(originalUri.getQueryParameter(key), warmedUri.getQueryParameter(key))
        assertEquals(cold.audioIndex, warm.audioIndex)
        assertEquals(cold.durationMs, warm.durationMs)
    }
}
