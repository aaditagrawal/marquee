package com.mav.marquee.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class SpeculativeDataSourceTest {
    // Expectations below are the measured reference TV's profile (see DeviceProfileBaselineTest).
    @org.junit.Before fun referenceDevice() { PlaybackCapabilities.override = ReferenceDevices.BRAVIA_VU1 }
    @org.junit.After fun clearReferenceDevice() { PlaybackCapabilities.override = null }

    private class Fallback : DataSource {
        var opens = 0; var listeners = 0
        override fun addTransferListener(listener: TransferListener) { listeners++ }
        override fun open(dataSpec: DataSpec): Long { opens++; return 1 }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = null
        override fun close() {}
    }
    @Test fun adoptsExactConnectionAndReportsTransferWithoutOpeningFallback() {
        MockWebServer().use { server ->
            val payload = ByteArray(50000) { (it % 127).toByte() }
            server.enqueue(MockResponse().setBody(Buffer().write(payload)))
            val uri = Uri.parse(server.url("/stream.mkv").toString())
            val client = OkHttpClient()
            val stream = BufferedLiveStream(client.newCall(Request.Builder().url(uri.toString()).build()), 8192)
            val r = Resolved(MediaItem.fromUri(uri), "item", "session", "source", JsonObject(emptyMap()),
                DeviceProfile.Mode.Direct, "Transcode", true, listOf("AudioCodecNotSupported"), 0, -1, 80000000)
            SpeculativeDataSource.adopted = SpeculativeDataSource.Lease(r, stream,
                Request.Builder().url(server.url("/Videos/ActiveEncodings")).delete().build(), client)
            val fallback = Fallback()
            val source = SpeculativeDataSource.wrap(DataSource.Factory { fallback }).createDataSource()
            var received = 0; var starts = 0; var ends = 0
            source.addTransferListener(object : TransferListener {
                override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
                override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { starts++ }
                override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) { received += bytesTransferred }
                override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { ends++ }
            })
            try {
                stream.start()
                assertEquals(C.LENGTH_UNSET.toLong(), source.open(DataSpec(uri)))
                val output = java.io.ByteArrayOutputStream()
                val bytes = ByteArray(4000)
                while (true) { val n = source.read(bytes, 0, bytes.size); if (n < 0) break; output.write(bytes, 0, n) }
                assertArrayEquals(payload, output.toByteArray())
                source.close()
                assertEquals(0, fallback.opens); assertEquals(1, server.requestCount)
                assertEquals(payload.size, received); assertEquals(1, starts); assertEquals(1, ends)
                try { source.open(DataSpec(uri)); fail("Second request must recover through HLS") } catch (_: java.io.IOException) {}
            } finally { source.close(); stream.close(); stream.awaitClosed(); SpeculativeDataSource.adopted = null }
        }
    }
    @Test fun directStreamAndItsBandwidthListenerPassThroughUnchanged() {
        val fallback = Fallback()
        val source = SpeculativeDataSource.wrap(DataSource.Factory { fallback }).createDataSource()
        source.addTransferListener(object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {}
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        })
        assertEquals(1L, source.open(DataSpec(Uri.parse("http://server/direct"))))
        assertEquals(1, fallback.opens); assertEquals(1, fallback.listeners)
        source.close()
    }
}
