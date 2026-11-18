package com.mav.marquee.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Read the original file's small Cues index, never decode its unsupported audio or start an encode. */
@UnstableApi
internal object RemuxSyncIndex {
    private data class Entry(val key: String, val map: SeekMap, val expires: Long)
    @Volatile private var cached: Entry? = null

    internal fun restartMs(map: SeekMap, requestedMs: Long, preferNext: Boolean = false, remux: Boolean = true): Long {
        if (!map.isSeekable || requestedMs <= 0) return requestedMs
        val points = map.getSeekPoints(requestedMs * 1000)
        val before = points.first.timeUs / 1000
        val after = points.second.timeUs / 1000
        val sync = if (preferNext) {
            // A nearby preceding cue is cheap too. Otherwise start at the next cue.
            if (requestedMs - before in 0..2_000) before else after
        } else {
            // Resume rewinds slightly. Very sparse indexes use the nearer cue.
            if (requestedMs - before in 0..6_000) before
            else if (after - requestedMs < requestedMs - before) after else before
        }
        // FFmpeg's input seek at/just after a cue can still copy the previous GOP.
        // Host probes: +1/+100ms miss, +250/+500ms select this GOP. Keep the smaller
        // verified mux margin; normal starts never decode through the user target.
        return (sync + if (remux && sync > 0) 250 else 0).coerceAtLeast(0)
    }

    fun hasIndex(source: JsonObject?, itemId: String): Boolean {
        val sourceId = source?.str("Id") ?: itemId
        val key = "${Env.serverUrl}|$sourceId|${source?.str("ETag") ?: ""}"
        return cached?.let { it.key == key && it.expires > SystemClock.elapsedRealtime() } == true
    }

    suspend fun startMs(source: JsonObject?, itemId: String, requestedMs: Long, preferNext: Boolean = false, remux: Boolean = true): Long = withContext(Dispatchers.IO) {
        if (requestedMs <= 0 || source?.str("Container")?.lowercase() !in setOf("mkv", "webm")) return@withContext requestedMs
        val sourceId = source?.str("Id") ?: itemId
        val key = "${Env.serverUrl}|$sourceId|${source?.str("ETag") ?: ""}"
        cached?.takeIf { it.key == key && it.expires > SystemClock.elapsedRealtime() }?.let {
            return@withContext restartMs(it.map, requestedMs, preferNext, remux)
        }
        val uri = Uri.parse("${Env.serverUrl}/Videos/$itemId/stream").buildUpon()
            .appendQueryParameter("static", "true").appendQueryParameter("mediaSourceId", sourceId).build()
        val http = Env.http.newBuilder().connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(1_500, TimeUnit.MILLISECONDS).callTimeout(2_000, TimeUnit.MILLISECONDS).build()
        val data = OkHttpDataSource.Factory(http).createDataSource()
        val extractor = MatroskaExtractor()
        var opened = false
        var map: SeekMap? = null
        val began = SystemClock.elapsedRealtime()
        try {
            extractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = DummyTrackOutput()
                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) { map = seekMap }
            })
            var position = 0L
            var length = data.open(DataSpec.Builder().setUri(uri).build()); opened = true
            var input = DefaultExtractorInput(data, position, length)
            if (!extractor.sniff(input)) return@withContext requestedMs
            input.resetPeekPosition()
            val holder = PositionHolder()
            for (n in 0 until 64) {
                coroutineContext.ensureActive()
                if (SystemClock.elapsedRealtime() - began > 2500) break
                val result = extractor.read(input, holder)
                if (map != null || result == Extractor.RESULT_END_OF_INPUT) break
                if (result == Extractor.RESULT_SEEK) {
                    data.close(); opened = false
                    position = holder.position
                    length = data.open(DataSpec.Builder().setUri(uri).setPosition(position).build()); opened = true
                    input = DefaultExtractorInput(data, position, if (length >= 0) position + length else length)
                }
            }
            map?.takeIf { it.isSeekable }?.let {
                cached = Entry(key, it, SystemClock.elapsedRealtime() + 300_000)
                val start = restartMs(it, requestedMs, preferNext, remux)
                Log.i("MarqueePlayer", "REMUX_SYNC requested=$requestedMs start=$start next=$preferNext indexMs=${SystemClock.elapsedRealtime() - began}")
                return@withContext start
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { Log.i("MarqueePlayer", "REMUX_SYNC index unavailable; fast timed restart") }
        finally { if (opened) data.close(); extractor.release() }
        requestedMs
    }
}
