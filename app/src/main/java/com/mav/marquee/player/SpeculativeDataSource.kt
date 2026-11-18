package com.mav.marquee.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/** Never opens a second request for the adopted progressive URL. Retry/seek uses the normal HLS fallback. */
@UnstableApi
internal object SpeculativeDataSource {
    class Lease(val resolved: Resolved, val stream: BufferedLiveStream, val cleanup: okhttp3.Request,
        val client: okhttp3.OkHttpClient, val warm: PlaybackPrewarm.Warm? = null) {
        var claimed = false
    }
    @Volatile var adopted: Lease? = null
    fun wrap(factory: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        val upstream = factory.createDataSource()
        object : DataSource {
            private var lease: Lease? = null
            private var spec: DataSpec? = null
            private val listeners = mutableListOf<TransferListener>()
            private var opened = false
            override fun addTransferListener(listener: TransferListener) { if (listener !in listeners) listeners += listener; upstream.addTransferListener(listener) }
            private var remaining = C.LENGTH_UNSET.toLong()
            override fun open(dataSpec: DataSpec): Long {
                val matching = adopted?.takeIf { it.resolved.mediaItem.localConfiguration?.uri == dataSpec.uri }
                if (matching == null) return upstream.open(dataSpec)
                synchronized(matching) {
                    if (matching.claimed || dataSpec.position != 0L) throw IOException("Progressive stream requires HLS recovery")
                    matching.claimed = true; lease = matching
                }
                spec = dataSpec; remaining = dataSpec.length
                listeners.forEach { it.onTransferInitializing(this, dataSpec, true) }
                matching.stream.open(); opened = true
                listeners.forEach { it.onTransferStart(this, dataSpec, true) }
                return remaining
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val live = lease ?: return upstream.read(buffer, offset, length)
                if (length == 0) return 0
                if (remaining == 0L) return C.RESULT_END_OF_INPUT
                val n = live.stream.read(buffer, offset, if (remaining >= 0) minOf(length.toLong(), remaining).toInt() else length)
                if (n > 0) { if (remaining >= 0) remaining -= n; listeners.forEach { it.onBytesTransferred(this, checkNotNull(spec), true, n) } }
                return n
            }
            override fun getUri(): Uri? = spec?.uri ?: upstream.uri
            override fun getResponseHeaders(): Map<String, List<String>> = lease?.stream?.responseHeaders() ?: upstream.responseHeaders
            override fun close() {
                if (lease != null) { lease?.stream?.close(); if (opened) listeners.forEach { it.onTransferEnd(this, checkNotNull(spec), true) } }
                else upstream.close()
                lease = null; spec = null; opened = false
            }
        }
    }
}
