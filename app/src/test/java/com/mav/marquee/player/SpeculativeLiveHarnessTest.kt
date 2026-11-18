package com.mav.marquee.player

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Opt-in host replay against a Jellyfin server you control. Skipped unless MARQUEE_SPECULATIVE_LIVE=1;
 * ordinary and CI runs never touch the network. Required environment: JF_URL, JF_API_KEY, JF_TV_USER_ID,
 * MARQUEE_SPECULATIVE_ITEM_ID (an item present in bench/evidence/audio-remux/requests.json, produced by
 * AudioSelectionTest's opt-in export) and JF_LOG_DIR (the server's transcode log directory, read to
 * confirm ffmpeg cleanup). Optional MARQUEE_SPECULATIVE_DIRECT_IDS: comma-separated direct-play item IDs
 * from the same requests.json to re-check HEAD/Range direct play. Never addresses a TV.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class SpeculativeLiveHarnessTest {
    // Expectations below are the measured reference TV's profile (see DeviceProfileBaselineTest).
    @org.junit.Before fun referenceDevice() { PlaybackCapabilities.override = ReferenceDevices.BRAVIA_VU1 }
    @org.junit.After fun clearReferenceDevice() { PlaybackCapabilities.override = null }

    @Test fun dwellToPlayAgainstRealJellyfin() {
        assumeTrue(System.getenv("MARQUEE_SPECULATIVE_LIVE") == "1")
        val root = File(System.getProperty("marquee.projectRoot"))
        val base = requireNotNull(System.getenv("JF_URL")).trimEnd('/')
        val secret = requireNotNull(System.getenv("JF_API_KEY"))
        val user = requireNotNull(System.getenv("JF_TV_USER_ID"))
        val itemId = requireNotNull(System.getenv("MARQUEE_SPECULATIVE_ITEM_ID")) { "set MARQUEE_SPECULATIVE_ITEM_ID" }
        val logDir = File(requireNotNull(System.getenv("JF_LOG_DIR")) { "set JF_LOG_DIR to the server's transcode log directory" })
        val client = OkHttpClient.Builder().callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        val cases = Json.parseToJsonElement(File(root, "bench/evidence/audio-remux/requests.json").readText()).jsonArray.objs()
        val c = cases.first { it.obj("item")!!.str("Id") == itemId }
        val runs = mutableListOf<JsonObject>()
        val out = File(com.mav.marquee.offline.OfflinePaths.evidence, "speculative-live-results.json")
        fun save() {
            val text = buildJsonObject { put("method", "Production SpeculativeSlot, BufferedLiveStream, resolver and Media3 DataSource; local ffprobe first decoded frame. Host only, no TV timing."); put("runs", JsonArray(runs)) }.toString()
            check(!text.contains(secret)); out.writeText(text + "\n")
        }
        for ((delayAfterDwell, startMs) in listOf(1000L to 0L, 3000L to 0L, -600L to 0L, 3000L to 120_000L)) repeat(if (startMs == 0L) 3 else 1) { sample ->
            val device = "marquee-speculative-" + UUID.randomUUID().toString()
            val auth = "MediaBrowser Client=\"MarqueeHostSpeculative\", Device=\"Host\", DeviceId=\"$device\", Token=\"$secret\""
            fun request(path: String) = Request.Builder().url(base + path).header("Authorization", auth)
            val startedNs = System.nanoTime()
            fun elapsed() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
            fun waitUntil(ms: Long) { val remaining = ms - elapsed(); if (remaining > 0) Thread.sleep(remaining) }
            val pressAt = 600L + delayAfterDwell
            var session: String? = null
            var stream: BufferedLiveStream? = null
            var source: androidx.media3.datasource.DataSource? = null
            var decoder: Process? = null
            val before = logDir.listFiles()?.map { it.name }?.toSet().orEmpty()
            var decodedMs = 0L
            var pressMs = 0L
            var maxCached = 0
            var cleanup = 0
            var remainingPids: List<Int> = emptyList()
            var prefix: String? = null
            var frame = JsonObject(emptyMap())
            try {
                // Detail prewarm begins at focus; immediate Play has to wait for this same negotiation.
                val body = JsonObject(c.obj("request")!!.toMutableMap().apply { put("UserId", JsonPrimitive(user)); put("StartTimeTicks", JsonPrimitive(startMs * 10_000)); put("SubtitleStreamIndex", JsonPrimitive(-1)) })
                val info = client.newCall(request("/Items/${c.obj("item")!!.str("Id")}/PlaybackInfo?userId=$user")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
                    check(it.isSuccessful); Json.parseToJsonElement(it.body!!.string()).jsonObject
                }
                session = requireNotNull(info.str("PlaySessionId"))
                val cold = StreamResolver.fromPlaybackInfo(c.obj("item")!!, info, DeviceProfile.Mode.Direct,
                    80_000_000, 0, -1, null, base, device, startMs)
                val r = requireNotNull(StreamResolver.speculativeProgressive(cold, startMs))
                val streamUri = r.mediaItem.localConfiguration!!.uri
                val deviceKey = streamUri.queryParameterNames.first { it.equals("DeviceId", true) }
                assertEquals(device, streamUri.getQueryParameter(deviceKey))
                assertEquals(session, r.playSessionId)
                val cleanupRequest = request("/Videos/ActiveEncodings?deviceId=$device&playSessionId=$session").delete().build()
                stream = BufferedLiveStream(client.newCall(Request.Builder().url(r.mediaItem.localConfiguration!!.uri.toString()).header("Authorization", auth).build()))
                val lease = SpeculativeDataSource.Lease(r, stream, cleanupRequest, client)
                val slot = SpeculativeSlot<SpeculativeDataSource.Lease> { it.stream.close() }
                val token = slot.focus("detail", 0)
                if (pressAt > 0) {
                    waitUntil(600)
                    assertSame(lease, slot.start(token, elapsed(), true) { lease.also { it.stream.start() } })
                    waitUntil(pressAt)
                    maxCached = stream.bufferedBytes
                    slot.playbackStarting()
                    SpeculativeDataSource.adopted = slot.adopt("detail", elapsed())
                } else {
                    // No speculation before the threshold: this is the ordinary Play request.
                    stream.start(); SpeculativeDataSource.adopted = lease
                }
                pressMs = if (pressAt == 0L) 0 else elapsed()
                decoder = ProcessBuilder("ffprobe", "-v", "error", "-threads", "2", "-probesize", "1000000",
                    "-analyzeduration", "1000000", "-select_streams", "v:0", "-read_intervals", "%+#1", "-show_frames", "-of", "json", "pipe:0")
                    .redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
                val probe = requireNotNull(decoder)
                var decoded = ""
                val reader = thread(isDaemon = true) { decoded = probe.inputStream.bufferedReader().readText(); decodedMs = elapsed() }
                source = SpeculativeDataSource.wrap(OkHttpDataSource.Factory(client)).createDataSource()
                source.open(DataSpec(r.mediaItem.localConfiguration!!.uri))
                val chunk = ByteArray(65536)
                var total = 0
                while (reader.isAlive && total < 16 * 1024 * 1024) {
                    val n = source.read(chunk, 0, chunk.size)
                    if (n < 0) break
                    total += n
                    try { probe.outputStream.write(chunk, 0, n); probe.outputStream.flush() } catch (_: java.io.IOException) { break }
                }
                runCatching { probe.outputStream.close() }
                reader.join(20_000)
                frame = Json.parseToJsonElement(decoded).jsonObject.arr("frames").objs().first()
                assertTrue(decodedMs >= pressMs)
            } finally {
                source?.close(); stream?.close(); stream?.awaitClosed(); SpeculativeDataSource.adopted = null
                decoder?.destroyForcibly(); decoder?.waitFor(5, TimeUnit.SECONDS)
                if (session != null) cleanup = client.newCall(request("/Videos/ActiveEncodings?deviceId=$device&playSessionId=$session").delete().build()).execute().use { it.code }
                Thread.sleep(300)
                val log = logDir.listFiles()?.filter { it.name !in before && it.name.contains(itemId) }?.maxByOrNull { it.lastModified() }
                val command = log?.readLines()?.firstOrNull { it.contains(" -i ") }.orEmpty()
                prefix = Regex("-y\\s+\"([^\"]+)\"").find(command)?.groupValues?.get(1)?.let { File(it).nameWithoutExtension }
                remainingPids = File("/proc").listFiles()?.filter { it.name.all(Char::isDigit) }?.mapNotNull { p ->
                    val text = runCatching { File(p, "cmdline").readText() }.getOrDefault("")
                    if (prefix != null && text.contains("ffmpeg") && text.contains(prefix!!)) p.name.toInt() else null
                }.orEmpty()
                runs += buildJsonObject {
                    put("case", if (pressAt == 0L) "immediate" else "${delayAfterDwell / 1000}s after dwell")
                    put("start_ms", startMs); put("sample", sample + 1); put("device_id", device); put("play_session_id", session)
                    put("focus_to_play_ms", pressMs); put("focus_to_frame_ms", decodedMs)
                    put("perceived_ms", decodedMs - pressMs); put("buffered_at_play_bytes", maxCached)
                    put("cleanup_http", cleanup); put("output_prefix", prefix)
                    put("remaining_ffmpeg_pids", JsonArray(remainingPids.map(::JsonPrimitive)))
                    put("frame", frame)
                }
                save()
            }
            assertEquals(204, cleanup); assertNotNull(prefix); assertTrue(remainingPids.isEmpty())
        }
        // Re-negotiate maintainer-chosen direct-play titles through the production resolver.
        val directIds = System.getenv("MARQUEE_SPECULATIVE_DIRECT_IDS").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
        for (directId in directIds) {
            val fixture = cases.first { it.obj("item")!!.str("Id") == directId }
            val item = fixture.obj("item")!!
            val device = "marquee-speculative-direct-" + UUID.randomUUID().toString()
            val auth = "MediaBrowser Client=\"MarqueeHostSpeculative\", Device=\"Host\", DeviceId=\"$device\", Token=\"$secret\""
            val body = JsonObject(c.obj("request")!!.toMutableMap().apply {
                put("UserId", JsonPrimitive(user)); put("MediaSourceId", item.getValue("Id")); put("AudioStreamIndex", fixture.getValue("selectedAudio")); put("SubtitleStreamIndex", JsonPrimitive(-1))
            })
            val info = client.newCall(Request.Builder().url(base + "/Items/${item.str("Id")}/PlaybackInfo?userId=$user")
                .header("Authorization", auth).post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { check(it.isSuccessful); Json.parseToJsonElement(it.body!!.string()).jsonObject }
            val r = StreamResolver.fromPlaybackInfo(item, info, DeviceProfile.Mode.Direct, 80_000_000,
                fixture.int("selectedAudio"), -1, null, base, device)
            assertFalse(r.isTranscode); assertNull(StreamResolver.speculativeProgressive(r, 0))
            val req = Request.Builder().url(r.mediaItem.localConfiguration!!.uri.toString()).header("Authorization", auth)
            val head = client.newCall(req.head().build()).execute().use { it.code }
            val range = client.newCall(req.get().header("Range", "bytes=0-65535").build()).execute().use { it.body!!.bytes(); it.code }
            assertEquals(200, head); assertEquals(206, range)
            runs += buildJsonObject { put("case", "direct regression"); put("title", item.str("SeriesName") ?: item.str("Name")); put("direct_play", true); put("head", head); put("range", range); put("speculative_encode", false) }
            save()
        }
    }
}
