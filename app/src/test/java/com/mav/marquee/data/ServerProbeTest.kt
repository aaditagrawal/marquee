package com.mav.marquee.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException
import kotlin.concurrent.thread

/**
 * The https -> http downgrade rule of [ServerProbe.resolve], exercised against real local sockets:
 * plain HTTP, trusted TLS, self-signed TLS and a wrong host name. No external network is used.
 */
class ServerProbeTest {
    private val json = """{"ServerName":"Test server","Version":"10.11.0","Id":"0123456789abcdef0123456789abcdef"}"""
    private val servers = mutableListOf<AutoCloseable>()

    @After fun tearDown() { servers.forEach { runCatching { it.close() } } }

    private fun client(trust: HandshakeCertificates? = null): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .apply { if (trust != null) sslSocketFactory(trust.sslSocketFactory(), trust.trustManager) }
        .build()

    /** Runs [ServerProbe.resolve] with the real fetcher on [client], recording every candidate tried. */
    private fun resolve(input: String, client: OkHttpClient, attempts: MutableList<String>) = runBlocking {
        ServerProbe.resolve(input) { base -> attempts += base; ServerProbe.fetchPublicInfo(base, client) }
    }

    private fun mockServer(tls: HeldCertificate? = null): MockWebServer = MockWebServer().also { s ->
        if (tls != null) s.useHttps(HandshakeCertificates.Builder().heldCertificate(tls).build().sslSocketFactory(), false)
        repeat(4) { s.enqueue(MockResponse().setBody(json)) }
        s.start(InetAddress.getByName("localhost"), 0)
        servers += AutoCloseable { s.shutdown() }
    }

    private fun certificate(host: String) = HeldCertificate.Builder().addSubjectAlternativeName(host).build()

    @Test fun explicitHttpIsUsedAsTyped() {
        val server = mockServer()
        val attempts = mutableListOf<String>()
        val (base, info) = resolve("http://localhost:${server.port}", client(), attempts)
        assertEquals("http://localhost:${server.port}", base)
        assertEquals("Test server", info.serverName)
        assertEquals(listOf(base), attempts)
    }

    @Test fun schemelessFallsBackToHttpWhenThePortSpeaksPlainHttp() {
        // The common LAN case "host:8096": Jellyfin speaks plain HTTP there, so the https candidate gets a
        // plaintext reply to its ClientHello (a protocol error, not a certificate failure).
        val server = PlainHttpServer(json).also { servers += it }
        val attempts = mutableListOf<String>()
        val (base, _) = resolve("localhost:${server.port}", client(), attempts)
        assertEquals("http://localhost:${server.port}", base)
        assertEquals(listOf("https://localhost:${server.port}", "http://localhost:${server.port}"), attempts)
    }

    @Test fun plaintextReplyToTlsIsNotACertificateFailure() {
        val server = PlainHttpServer(json).also { servers += it }
        // OkHttp may surface the SSLException directly or as a suppressed failure after trying another route.
        val e = assertThrows(java.io.IOException::class.java) {
            runBlocking { ServerProbe.fetchPublicInfo("https://localhost:${server.port}", client()) }
        }
        assertFalse(e.toString(), ServerProbe.isUntrustedCertificate(e))
    }

    @Test fun trustedTlsServerIsUsedOverHttps() {
        val cert = certificate("localhost")
        val server = mockServer(cert)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val attempts = mutableListOf<String>()
        val (base, _) = resolve("localhost:${server.port}", client(trust), attempts)
        assertEquals("https://localhost:${server.port}", base)
        assertEquals(listOf(base), attempts)
    }

    @Test fun selfSignedCertificateStopsWithoutCleartextFallback() {
        val server = mockServer(certificate("localhost")) // not trusted by the client's system trust store
        for (input in listOf("localhost:${server.port}", "https://localhost:${server.port}")) {
            val attempts = mutableListOf<String>()
            val failure = assertThrows(input, ServerProbe.Failure::class.java) { resolve(input, client(), attempts) }
            assertEquals(ServerProbe.UNTRUSTED_CERTIFICATE, failure.message)
            assertEquals("never retried over http", listOf("https://localhost:${server.port}"), attempts)
        }
        assertEquals("no HTTP request reached the server", 0, server.requestCount)
    }

    @Test fun wrongHostNameIsAnUntrustedCertificate() {
        val cert = certificate("jellyfin.example")
        val server = mockServer(cert)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val attempts = mutableListOf<String>()
        val failure = assertThrows(ServerProbe.Failure::class.java) { resolve("localhost:${server.port}", client(trust), attempts) }
        assertEquals(ServerProbe.UNTRUSTED_CERTIFICATE, failure.message)
        assertEquals(1, attempts.size)
    }

    @Test fun unreachableHttpsFallsThroughToHttp() = runBlocking {
        val attempts = mutableListOf<String>()
        val (base, _) = ServerProbe.resolve("jellyfin.example:8096") { b ->
            attempts += b
            if (b.startsWith("https://")) throw ConnectException("refused")
            PublicSystemInfo(serverName = "Test server", id = "0123456789abcdef0123456789abcdef")
        }
        assertEquals("http://jellyfin.example:8096", base)
        assertEquals(listOf("https://jellyfin.example:8096", "http://jellyfin.example:8096"), attempts)
    }

    @Test fun certificateClassification() {
        assertTrue(ServerProbe.isUntrustedCertificate(SSLHandshakeException("handshake").apply { initCause(CertificateException("no trust anchor")) }))
        assertTrue(ServerProbe.isUntrustedCertificate(javax.net.ssl.SSLPeerUnverifiedException("Hostname localhost not verified")))
        assertTrue(ServerProbe.isUntrustedCertificate(SSLHandshakeException("x").apply {
            initCause(CertificateException(java.security.cert.CertPathValidatorException("Trust anchor for certification path not found.")))
        }))
        assertFalse(ServerProbe.isUntrustedCertificate(SSLException("Unsupported or unrecognized SSL message")))
        assertFalse(ServerProbe.isUntrustedCertificate(SSLHandshakeException("Handshake failed").apply { initCause(SSLProtocolException("WRONG_VERSION_NUMBER")) }))
        assertFalse(ServerProbe.isUntrustedCertificate(ConnectException("refused")))
    }

    /**
     * A minimal plain-HTTP server on localhost: answers a TLS ClientHello (first byte 0x16) the way plain
     * HTTP servers do, with a plaintext 400, and answers HTTP requests with [body].
     */
    private class PlainHttpServer(private val body: String) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("localhost"))
        val port: Int get() = socket.localPort

        init {
            thread(isDaemon = true, name = "plain-http") {
                while (!socket.isClosed) {
                    val conn = runCatching { socket.accept() }.getOrNull() ?: break
                    runCatching { conn.use { handle(it) } }
                }
            }
        }

        private fun handle(conn: Socket) {
            conn.soTimeout = 5_000
            val input = conn.getInputStream()
            val out = conn.getOutputStream()
            val first = input.read()
            if (first == 0x16) {
                out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 11\r\nConnection: close\r\n\r\nBad Request".toByteArray())
                out.flush(); return
            }
            // Read the request head (until CRLF CRLF); the request has no body.
            var last4 = first
            while (last4 != 0x0D0A0D0A) {
                val b = input.read()
                if (b < 0) return
                last4 = (last4 shl 8) or b
            }
            val bytes = body.toByteArray()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes); out.flush()
        }

        override fun close() = socket.close()
    }
}
