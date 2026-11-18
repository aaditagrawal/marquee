package com.mav.marquee.player

import android.app.Application
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class AudioSelectionTest {
    // Expectations below are the measured reference TV's profile (see DeviceProfileBaselineTest).
    @org.junit.Before fun referenceDevice() { PlaybackCapabilities.override = ReferenceDevices.BRAVIA_VU1 }
    @org.junit.After fun clearReferenceDevice() { PlaybackCapabilities.override = null }

    private fun track(index: Int, codec: String, language: String = "eng", profile: String? = null,
                      channels: Int = 6, rate: Int = 48_000, depth: Int = 24, commentary: Boolean = false) = buildJsonObject {
        put("Type", "Audio"); put("Index", index); put("Codec", codec); put("Language", language)
        put("Channels", channels); put("SampleRate", rate); put("BitDepth", depth)
        put("IsCommentary", commentary)
        profile?.let { put("Profile", it) }
    }
    private fun source(vararg tracks: JsonObject) = buildJsonObject {
        put("DefaultAudioStreamIndex", tracks.first().int("Index"))
        put("MediaStreams", JsonArray(tracks.toList()))
    }

    @Test fun heavyCodecsCannotBeDirectPlayed() {
        for (t in listOf(track(1, "truehd"), track(1, "alac"), track(1, "mlp"),
            track(1, "dts", profile = "DTS-HD MA"), track(1, "dts", profile = "DTS-HD HRA"),
            track(1, "dts"), track(1, "flac"), track(1, "flac", channels = 2, rate = 96_000))) {
            assertFalse(t.toString(), AudioSelection.isDirectCompatible(t))
        }
        assertTrue(AudioSelection.isDirectCompatible(track(1, "dts", profile = "DTS")))
        assertTrue(AudioSelection.isDirectCompatible(track(1, "flac", channels = 2)))
        for (mode in DeviceProfile.Mode.entries) {
            val p = DeviceProfile.build(mode = mode)
            for (dp in p.arr("DirectPlayProfiles").objs()) {
                assertFalse(dp.str("AudioCodec")!!.split(',').any { it in setOf("truehd", "alac", "mlp") })
            }
            val output = p.arr("TranscodingProfiles").objs().first()
            assertEquals("eac3", output.str("AudioCodec"))
            assertEquals("6", output.str("MaxAudioChannels"))
        }
        assertEquals("ac3,aac", DeviceProfile.build(ac3Fallback = true).arr("TranscodingProfiles").objs().first().str("AudioCodec"))
    }

    @Test fun lowChannelInputsUseAacWhenRemuxing() {
        val video = buildJsonObject { put("Codec", "hevc") }
        for (fallback in listOf(false, true)) {
            val profile = StreamResolver.profileFor(video, 80_000_000, DeviceProfile.Mode.Remux,
                fallback, track(1, "alac", channels = 2))
            assertEquals("aac", profile.arr("TranscodingProfiles").objs().first().str("AudioCodec"))
        }
    }

    @Test fun secondaryTrackPreservesLanguageAndExplicitHeavyChoiceTranscodes() {
        val s = source(track(1, "truehd"), track(2, "ac3", "hin"), track(3, "eac3"))
        assertEquals(3, AudioSelection.choose(s, null, ""))
        assertEquals(2, AudioSelection.choose(s, null, "hin"))
        assertEquals(1, AudioSelection.choose(s, 1, "hin"))
        assertEquals(3, AudioSelection.choose(s, 99, ""))
        assertEquals(1, AudioSelection.choose(source(track(1, "truehd")), null, ""))
        assertEquals(1, AudioSelection.choose(source(track(1, "eac3"), track(2, "aac")), null, ""))
    }

    @Test fun automaticSelectionAvoidsCommentaryAndFallsBackToCompatibleLanguage() {
        val s = source(track(1, "truehd"), track(2, "ac3", commentary = true), track(3, "aac", "hin"))
        assertEquals(3, AudioSelection.choose(s, null, "eng"))
        assertEquals(2, AudioSelection.choose(s, 2, "eng"))
    }

    @Test fun exportsProductionAudioPolicyForLiveLibraryProof() {
        val root = File(System.getProperty("marquee.projectRoot") ?: "..")
        val out = File(root, "bench/evidence/audio-remux")
        val file = File(out, "library.json")
        // Opt-in maintainer export from a live library snapshot; reported as skipped in ordinary runs.
        org.junit.Assume.assumeTrue("needs bench/evidence/audio-remux/library.json", file.exists())
        val requests = buildJsonArray {
            for (item in Json.parseToJsonElement(file.readText()).jsonArray.objs()) {
                for (source in item.arr("MediaSources").objs()) {
                    val audio = source.arr("MediaStreams").objs().filter { it.str("Type") == "Audio" }
                    if (audio.none { !AudioSelection.isDirectCompatible(it) }) continue
                    val selected = AudioSelection.choose(source, null, "")
                    val video = source.arr("MediaStreams").objs().firstOrNull { it.str("Type") == "Video" }
                    val mode = StreamResolver.effectiveMode(video, DeviceProfile.Mode.Direct)
                    val requests = listOf(false, true).map { fallback ->
                        JfApi.playbackInfoBody("host-user-placeholder", StreamResolver.profileFor(video, 120_000_000, mode, fallback, audio.firstOrNull { it.int("Index") == selected }),
                            120_000_000, 0, source.str("Id"), selected, -1, mode)
                    }
                    add(buildJsonObject {
                        put("item", item); put("source", source); put("selectedAudio", selected)
                        put("allAudioHeavy", audio.all { !AudioSelection.isDirectCompatible(it) })
                        put("request", requests[0]); put("ac3Request", requests[1])
                    })
                }
            }
        }
        File(out, "requests.json").writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), requests) + "\n")
    }

    @Test fun replaysFreshAudioDecisionsThroughProductionResolver() {
        val root = File(System.getProperty("marquee.projectRoot") ?: "..")
        val out = File(root, "bench/evidence/audio-remux")
        val fixture = File(out, "playback-info.json")
        org.junit.Assume.assumeTrue("needs bench/evidence/audio-remux/playback-info.json", fixture.exists())
        val resolved = buildJsonArray {
            for (case in Json.parseToJsonElement(fixture.readText()).jsonArray.objs()) {
                val item = case.obj("item")!!
                val request = case.obj("request")!!
                val mode = if (request.bool("EnableDirectPlay")) DeviceProfile.Mode.Direct else DeviceProfile.Mode.Remux
                val stream = StreamResolver.fromPlaybackInfo(item, case.obj("info")!!, mode,
                    request.int("MaxStreamingBitrate")!!, case.int("selectedAudio"), -1,
                    case.obj("source")!!.str("Id"), case.str("server")!!, case.str("device")!!)
                if (case.bool("allAudioHeavy")) {
                    assertTrue(item.str("Name"), stream.videoCopy)
                    assertTrue(stream.transcodeReasons.isNotEmpty())
                    assertTrue(stream.transcodeReasons.all { it.startsWith("Audio") })
                } else assertEquals("DirectPlay", stream.playMethod)
                add(buildJsonObject {
                    put("title", item.str("Name")); put("itemId", item.str("Id")); put("variant", case.str("variant"))
                    put("url", stream.mediaItem.localConfiguration!!.uri.toString())
                    put("playSessionId", stream.playSessionId); put("deviceId", case.str("device"))
                    put("videoCopy", stream.videoCopy); put("playMethod", stream.playMethod)
                    put("audioIndex", stream.audioIndex)
                    put("transcodeReasons", JsonArray(stream.transcodeReasons.map(::JsonPrimitive)))
                })
            }
        }
        File(out, "resolved.json").writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), resolved) + "\n")
    }
}
