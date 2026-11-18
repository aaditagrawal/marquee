package com.mav.marquee.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Validates a typed server address against `GET /System/Info/Public` (unauthenticated) and returns the
 * first candidate from [ServerAddress.candidates] that answers as Jellyfin. Errors carry a message
 * that tells the user what to change.
 *
 * Downgrade rule for addresses typed without a scheme: the next (http://) candidate is tried only when
 * the https:// candidate could not be used at the transport level: unknown host, refused, timed out,
 * or no TLS spoken on that port (a plain-HTTP server answers a TLS ClientHello with plaintext or by
 * closing, which surfaces as a non-certificate [SSLException]). When a server completes enough of a
 * TLS handshake to present a certificate that is not trusted (unknown CA, self-signed, expired, or
 * wrong host name), probing stops with [UNTRUSTED_CERTIFICATE]; Marquee never falls back to cleartext
 * in that case. A typed `http://` address is used as typed.
 */
internal object ServerProbe {
    class Failure(message: String) : IOException(message)

    suspend fun resolve(
        input: String,
        fetch: suspend (String) -> PublicSystemInfo = { fetchPublicInfo(it) },
    ): Pair<String, PublicSystemInfo> {
        val candidates = ServerAddress.candidates(input) // throws ServerAddress.Invalid
        var firstError: Throwable? = null
        var notJellyfin: Throwable? = null
        for (base in candidates) {
            try {
                val info = fetch(base)
                if (info.id.isNullOrBlank()) throw NotJellyfin()
                return base to info
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
                // Something answered TLS but could not be trusted: stop rather than retry over cleartext.
                if (isUntrustedCertificate(e)) throw Failure(UNTRUSTED_CERTIFICATE)
                // An answering non-Jellyfin service is more useful to report than a refused TLS attempt.
                if (notJellyfin == null && (e is NotJellyfin || e is HttpException || e is SerializationException)) notJellyfin = e
            }
        }
        throw Failure(message(notJellyfin ?: firstError, explicitScheme = candidates.size == 1))
    }

    private class NotJellyfin : IOException("not Jellyfin")

    internal const val UNTRUSTED_CERTIFICATE =
        "This server's certificate isn't trusted. Fix the certificate, or type the address with http:// to connect without encryption."

    /**
     * True when TLS reached certificate validation and failed it: a [CertificateException] or
     * [CertPathValidatorException] among the causes or suppressed failures (JDK and Android trust managers both
     * wrap one), or OkHttp's host-name check ([SSLPeerUnverifiedException]). Protocol-level TLS errors
     * such as a plaintext reply are not certificate failures.
     */
    internal fun isUntrustedCertificate(e: Throwable): Boolean {
        // Causes and suppressed failures (OkHttp attaches failures from other routes as suppressed).
        val pending = ArrayDeque<Throwable>().apply { add(e) }
        val seen = HashSet<Throwable>()
        while (pending.isNotEmpty() && seen.size < 32) {
            val t = pending.removeFirst()
            if (!seen.add(t)) continue
            if (t is SSLPeerUnverifiedException || t is CertificateException || t is CertPathValidatorException) return true
            t.cause?.let(pending::add)
            pending.addAll(t.suppressed)
        }
        return false
    }

    internal fun message(e: Throwable?, explicitScheme: Boolean): String = when (e) {
        is NotJellyfin, is SerializationException -> "That address answered, but it isn't a Jellyfin server. Check the port and any base path (for example /jellyfin)."
        is HttpException -> if (e.code == 404) "No Jellyfin server at that path. Check the port and any base path (for example /jellyfin)."
            else "The server answered with an error (HTTP ${e.code}). Check the address, or try again later."
        is UnknownHostException -> "Couldn't find that server name. Check the spelling, or use its IP address."
        is SSLException -> if (explicitScheme) "A secure connection couldn't be made. Check that the server uses HTTPS on this port, or use http:// for a server on your home network."
            else "Couldn't connect. Check the address and port, and that the server is running."
        is SocketTimeoutException -> "The server didn't respond in time. Check that it's running and on the same network."
        is ConnectException, is NoRouteToHostException -> "Couldn't connect. Check the address and port, and that the server is running."
        else -> "Couldn't reach this Jellyfin server. Check the address and connection."
    }

    private val probeClient by lazy {
        Session.httpClient.newBuilder().callTimeout(8, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).build()
    }

    internal suspend fun fetchPublicInfo(base: String, client: OkHttpClient = probeClient): PublicSystemInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$base/System/Info/Public".toHttpUrl()).get().build()
        client.newCall(request).await().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code} /System/Info/Public")
            MarqueeJson.decodeFromString(PublicSystemInfo.serializer(), r.body!!.string())
        }
    }
}
