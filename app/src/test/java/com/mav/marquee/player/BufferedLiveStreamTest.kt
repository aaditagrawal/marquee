package com.mav.marquee.player

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class BufferedLiveStreamTest {
    @Test fun boundedCacheHandsExactBytesToConsumerWithoutSecondHttpRequest() {
        MockWebServer().use { server ->
            val content = ByteArray(256 * 1024) { (it % 251).toByte() }
            server.enqueue(MockResponse().setBody(Buffer().write(content)))
            val stream = BufferedLiveStream(OkHttpClient().newCall(Request.Builder().url(server.url("/stream.mkv")).build()), 16384)
            stream.start(); stream.open()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (stream.bufferedBytes < stream.capacity && System.nanoTime() < deadline) Thread.yield()
            assertEquals(16384, stream.bufferedBytes)
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(7777)
            while (true) { val n = stream.read(chunk, 0, chunk.size); if (n < 0) break; output.write(chunk, 0, n) }
            assertArrayEquals(content, output.toByteArray())
            assertEquals(1, server.requestCount)
            stream.close(); stream.awaitClosed()
        }
    }
    @Test fun cancelUnblocksAFullBufferAndNeverRequiresConsumer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(100_000))))
            val stream = BufferedLiveStream(OkHttpClient().newCall(Request.Builder().url(server.url("/stream.mkv")).build()), 1024)
            stream.start(); stream.open(); stream.close(); stream.awaitClosed()
            assertEquals(1, server.requestCount)
        }
    }
}
