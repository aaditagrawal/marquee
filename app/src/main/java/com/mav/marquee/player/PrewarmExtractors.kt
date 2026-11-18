package com.mav.marquee.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.common.C
import androidx.media3.extractor.mkv.MatroskaExtractor
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Parse Cues on the detail worker, then supply the exact index without repeating its startup seek. */
@UnstableApi
internal object PrewarmExtractors : ExtractorsFactory {
    private data class Index(val uri: Uri, val map: SeekMap, val expires: Long)
    @Volatile private var cached: Index? = null
    private data class Target(val uri: Uri, val positionUs: Long, val hasAudio: Boolean, val durationUs: Long?)
    @Volatile private var target: Target? = null

    fun exactTarget(uri: Uri, localMs: Long, hasAudio: Boolean, remainingDurationMs: Long?) {
        target = Target(uri, localMs * 1000, hasAudio, remainingDurationMs?.times(1000))
    }

    fun resumeMs(uri: Uri, requestedMs: Long): Long? = cached
        ?.takeIf { it.uri == uri && it.expires >= android.os.SystemClock.elapsedRealtime() }
        ?.let { RemuxSyncIndex.restartMs(it.map, requestedMs, remux = false) }

    internal fun timedSeekMap(durationUs: Long, exact: Boolean): SeekMap =
        if (exact) PrerollSeekMap(durationUs) else SeekMap.Unseekable(durationUs)

    suspend fun warm(r: Resolved, factory: DataSource.Factory) {
        if (r.isTranscode || r.mediaSource.str("Container")?.lowercase() !in setOf("mkv", "webm")) return
        val uri = r.mediaItem.localConfiguration?.uri ?: return
        val source = factory.createDataSource()
        val extractor = MatroskaExtractor()
        var index: SeekMap? = null
        var opened = false
        val begin = android.os.SystemClock.elapsedRealtime()
        try {
            extractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = DummyTrackOutput()
                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) { index = seekMap }
            })
            var position = 0L
            var length = source.open(DataSpec.Builder().setUri(uri).build()); opened = true
            var input = DefaultExtractorInput(source, position, length)
            if (!extractor.sniff(input)) return
            input.resetPeekPosition()
            val holder = PositionHolder()
            for (n in 0 until 64) {
                coroutineContext.ensureActive()
                val result = extractor.read(input, holder)
                if (index != null || result == Extractor.RESULT_END_OF_INPUT) break
                if (result == Extractor.RESULT_SEEK) {
                    source.close(); opened = false
                    position = holder.position
                    length = source.open(DataSpec.Builder().setUri(uri).setPosition(position).build()); opened = true
                    input = DefaultExtractorInput(source, position, if (length >= 0) position + length else length)
                }
            }
            index?.takeIf { it.isSeekable }?.let {
                cached = Index(uri, it, android.os.SystemClock.elapsedRealtime() + 30_000)
                android.util.Log.i("MarqueePlayer", "PREWARM index item=${r.itemId} ms=${android.os.SystemClock.elapsedRealtime() - begin} seekable=true")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { android.util.Log.i("MarqueePlayer", "PREWARM index unavailable item=${r.itemId}") }
        finally { if (opened) source.close(); extractor.release() }
    }

    override fun createExtractors(): Array<Extractor> = DefaultExtractorsFactory().createExtractors()
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
        val index = cached?.takeIf { it.uri == uri && it.expires >= android.os.SystemClock.elapsedRealtime() }?.map
        val timedMkv = uri.lastPathSegment == "stream.mkv" && uri.getQueryParameter("CopyTimestamps") == "true"
        val offsetUs = if (timedMkv) (uri.getQueryParameter("StartTimeTicks")?.toLongOrNull() ?: 0) / 10 else 0L
        val timedTarget = target?.takeIf { it.uri == uri && timedMkv }
        val exact = timedTarget?.takeIf { it.positionUs > 0 }
        val factory = DefaultExtractorsFactory()
        if (timedMkv) factory.setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
        if (index != null) factory.setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
        return factory.createExtractors(uri, responseHeaders).map { extractor ->
            if ((index == null && !timedMkv) || extractor !is MatroskaExtractor) extractor else object : Extractor {
                override fun sniff(input: ExtractorInput): Boolean = extractor.sniff(input)
                override fun init(output: ExtractorOutput) {
                    // Normal starts need only the first usable A/V samples. Exact opt-in
                    // starts buffer through their local target before track selection,
                    // preserving the single-use speculative HTTP connection.
                    var pendingMap: SeekMap? = null
                    var videoBuffered = !timedMkv
                    var audioBuffered = !timedMkv || timedTarget?.hasAudio == false
                    val began = android.os.SystemClock.elapsedRealtime()
                    fun publish() {
                        if (videoBuffered && audioBuffered) pendingMap?.let {
                            pendingMap = null
                            output.seekMap(it)
                            if (exact != null) android.util.Log.i("MarqueePlayer", "PREROLL_BUFFER targetMs=${exact.positionUs / 1000} ms=${android.os.SystemClock.elapsedRealtime() - began}")
                        }
                    }
                    extractor.init(object : ExtractorOutput {
                        override fun track(id: Int, type: Int): TrackOutput = object : ForwardingTrackOutput(output.track(id, type)) {
                            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                                // Keep negative keyframe samples for decode-only preroll. The audio starts at local zero.
                                val localUs = timeUs - offsetUs
                                super.sampleMetadata(localUs, flags, size, offset, cryptoData)
                                if (timedMkv && localUs >= (exact?.positionUs ?: 0)) {
                                    if (type == C.TRACK_TYPE_VIDEO) videoBuffered = true
                                    if (type == C.TRACK_TYPE_AUDIO) audioBuffered = true
                                    publish()
                                }
                            }
                            override fun durationUs(durationUs: Long) = super.durationUs(
                                if (durationUs == C.TIME_UNSET) durationUs else (durationUs - offsetUs).coerceAtLeast(0))
                        }
                        override fun endTracks() = output.endTracks()
                        override fun seekMap(seekMap: SeekMap) {
                            val durationUs = timedTarget?.durationUs ?: if (seekMap.durationUs == C.TIME_UNSET)
                                C.TIME_UNSET else (seekMap.durationUs - offsetUs).coerceAtLeast(0)
                            pendingMap = index ?: timedSeekMap(durationUs, exact != null)
                            publish()
                        }
                    })
                }
                override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = extractor.read(input, seekPosition)
                override fun seek(position: Long, timeUs: Long) = extractor.seek(position, timeUs)
                override fun release() = extractor.release()
            }
        }.toTypedArray()
    }
}
