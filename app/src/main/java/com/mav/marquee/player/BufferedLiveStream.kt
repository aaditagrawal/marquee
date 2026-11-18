package com.mav.marquee.player

import java.io.IOException
import java.util.concurrent.CountDownLatch
import okhttp3.Call
import okhttp3.Response

/** One HTTP connection, a bounded FIFO, and backpressure. The consumer drains the prefix then live bytes. */
internal class BufferedLiveStream(private val call: Call, val capacity: Int = 16_000_000) {
    private val monitor = Object()
    private val bytes = ByteArray(capacity)
    private var head = 0
    private var count = 0
    private var closed = false
    private var ended = false
    private var failure: IOException? = null
    private var response: Response? = null
    private val headers = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private var started = false
    val bufferedBytes: Int get() = synchronized(monitor) { count }
    fun start() = synchronized(monitor) {
        check(!started); started = true
        Thread({ pump() }, "MarqueeSpeculativeStream").apply { isDaemon = true; start() }
    }
    private fun pump() {
        try {
            call.execute().use { r ->
                synchronized(monitor) {
                    if (closed) return@use
                    if (!r.isSuccessful) throw IOException("Speculative HTTP ${r.code}")
                    response = r
                }
                headers.countDown()
                val input = r.body?.byteStream() ?: throw IOException("Empty speculative body")
                while (true) {
                    val range = synchronized(monitor) {
                        while (!closed && count == capacity) monitor.wait()
                        if (closed) return@use
                        val tail = (head + count) % capacity
                        tail to minOf(capacity - count, capacity - tail, 8192)
                    }
                    val n = input.read(bytes, range.first, range.second)
                    synchronized(monitor) {
                        if (n < 0) { ended = true; monitor.notifyAll(); return@use }
                        count += n; monitor.notifyAll()
                    }
                }
            }
        } catch (_: InterruptedException) {
            synchronized(monitor) { failure = IOException("Speculative stream interrupted") }
        } catch (e: IOException) {
            synchronized(monitor) { failure = IOException("Speculative stream unavailable", e) }
        } finally {
            headers.countDown(); finished.countDown()
            synchronized(monitor) { ended = true; monitor.notifyAll() }
        }
    }
    fun open() {
        try { headers.await() } catch (_: InterruptedException) { throw java.io.InterruptedIOException("Stream open interrupted") }
        synchronized(monitor) { failure?.let { throw it }; if (closed) throw IOException("Speculative stream closed") }
    }
    fun read(target: ByteArray, offset: Int, length: Int): Int = synchronized(monitor) {
        if (length == 0) return 0
        while (count == 0 && !ended && !closed) {
            try { monitor.wait() } catch (_: InterruptedException) { throw java.io.InterruptedIOException("Stream read interrupted") }
        }
        if (closed) throw IOException("Speculative stream closed")
        if (count == 0) { failure?.let { throw it }; return -1 }
        val n = minOf(length, count, capacity - head)
        bytes.copyInto(target, offset, head, head + n)
        head = (head + n) % capacity; count -= n; monitor.notifyAll(); n
    }
    fun responseHeaders(): Map<String, List<String>> = synchronized(monitor) { response?.headers?.toMultimap().orEmpty() }
    fun close() {
        synchronized(monitor) { closed = true; monitor.notifyAll() }
        call.cancel()
        // Cancellation interrupts a blocked HTTP read. The pump owns Response.close().
        headers.countDown()
    }
    fun awaitClosed() { if (started) finished.await() }
}
