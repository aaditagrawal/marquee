package com.mav.marquee.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Two bounded byte ranges, only for the prefetched direct file. Head and Matroska Cues stay in RAM. */
@UnstableApi
internal object PrewarmBytes {
    private data class Range(val start: Long, val bytes: ByteArray)
    private data class Entry(val uri: Uri, val size: Long, val ranges: List<Range>, val expires: Long)
    @Volatile private var entry: Entry? = null

    suspend fun warm(r: Resolved) = withContext(Dispatchers.IO) {
        if (r.isTranscode) return@withContext
        val size = r.mediaSource.long("Size") ?: return@withContext
        if (size <= 0) return@withContext
        val uri = r.mediaItem.localConfiguration?.uri ?: return@withContext
        val ranges = listOf(0L to minOf(size, 2 * 1024 * 1024L), maxOf(2 * 1024 * 1024L, size - 512 * 1024L) to size)
            .filter { it.first < it.second }.mapNotNull { (start, end) ->
                runCatching {
                    Env.http.newBuilder().callTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build().newCall(
                        Request.Builder().url(uri.toString()).header("Range", "bytes=$start-${end - 1}").build()
                    ).execute().use { response ->
                        if (response.code != 206 || response.body?.contentLength() != end - start) null
                        else response.body?.bytes()?.let { Range(start, it) }
                    }
                }.getOrNull()
            }
        entry = Entry(uri, size, ranges, android.os.SystemClock.elapsedRealtime() + 30_000)
        android.util.Log.i("MarqueePlayer", "PREWARM bytes item=${r.itemId} bytes=${ranges.sumOf { it.bytes.size }} ranges=${ranges.size}")
    }

    fun wrap(factory: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        object : DataSource {
            private val upstream = factory.createDataSource()
            private var spec: DataSpec? = null
            private var cached: Entry? = null
            private var position = 0L
            private var remaining = C.LENGTH_UNSET.toLong()
            private var remoteOpen = false
            override fun addTransferListener(listener: TransferListener) { upstream.addTransferListener(listener) }
            override fun open(dataSpec: DataSpec): Long {
                spec = dataSpec; position = dataSpec.position
                cached = entry?.takeIf { it.uri == dataSpec.uri && it.expires >= android.os.SystemClock.elapsedRealtime() }
                remaining = dataSpec.length.takeIf { it != C.LENGTH_UNSET.toLong() }
                    ?: cached?.let { it.size - position } ?: C.LENGTH_UNSET.toLong()
                if (range() == null) { remaining = upstream.open(dataSpec); remoteOpen = true }
                if (cached != null) android.util.Log.i("MarqueePlayer", "PREWARM read position=${dataSpec.position} cached=${range() != null}")
                return remaining
            }
            private fun range(): Range? = cached?.ranges?.firstOrNull { position >= it.start && position < it.start + it.bytes.size }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (remaining == 0L) return C.RESULT_END_OF_INPUT
                val range = range()
                val count = if (range != null) {
                    if (remoteOpen) { upstream.close(); remoteOpen = false }
                    val from = (position - range.start).toInt()
                    val n = minOf(length, range.bytes.size - from, if (remaining >= 0) remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else Int.MAX_VALUE)
                    range.bytes.copyInto(buffer, offset, from, from + n); n
                } else {
                    if (!remoteOpen) {
                        val dataSpec = checkNotNull(spec)
                        upstream.open(dataSpec.subrange(position - dataSpec.position)); remoteOpen = true
                    }
                    upstream.read(buffer, offset, length)
                }
                if (count > 0) { position += count; if (remaining >= 0) remaining -= count }
                return count
            }
            override fun getUri(): Uri? = spec?.uri
            override fun getResponseHeaders(): Map<String, List<String>> = if (remoteOpen) upstream.responseHeaders else emptyMap()
            override fun close() { if (remoteOpen) upstream.close(); remoteOpen = false; spec = null; cached = null }
        }
    }
}
