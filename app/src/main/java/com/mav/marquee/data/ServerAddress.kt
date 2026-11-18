package com.mav.marquee.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Turns what a user types into the base URLs to try, in order. Pure; unit-tested in ServerAddressTest.
 *
 * - `https://host[:port][/base]` or `http://...`: used as typed (one candidate).
 * - No scheme: HTTPS first, then HTTP. [ServerProbe.resolve] moves on to an HTTP candidate only when
 *   the HTTPS one is unreachable at the transport level (unknown host, refused, timed out, or no TLS
 *   spoken on that port). If a server presents a certificate that is not trusted, probing stops
 *   instead of falling back to cleartext; the user can type `http://` explicitly.
 *   Without a port, HTTP tries Jellyfin's default 8096 and then port 80 (reverse proxies).
 *   The first candidate that answers `/System/Info/Public` as Jellyfin is stored, scheme included,
 *   so later launches do not probe again.
 * - IPv6 literals need brackets, as in URLs: `[fd00::1]:8096`. A bare IPv6 address is bracketed for you.
 * - Base paths are kept (`https://host/jellyfin`); a pasted web-client URL (`.../web/#/home`) is
 *   trimmed to its base. Query strings and fragments are dropped; user:password@ is rejected.
 */
internal object ServerAddress {
    class Invalid(message: String) : IllegalArgumentException(message)

    private val schemeRegex = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    fun hasScheme(input: String) = schemeRegex.containsMatchIn(input.trim())

    fun candidates(input: String): List<String> {
        var text = input.trim()
        if (text.isEmpty()) throw Invalid("Enter your server address.")
        if (text.any { it.isWhitespace() }) throw Invalid("Server addresses can't contain spaces.")
        if (hasScheme(text)) {
            val scheme = text.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https") throw Invalid("Use an http:// or https:// address.")
            return listOf(canonicalOrThrow(text))
        }
        // A bare IPv6 address (more than one colon, no brackets) cannot carry a port; bracket it.
        val authority = text.substringBefore('/')
        if (!authority.startsWith("[") && authority.count { it == ':' } > 1) {
            text = "[$authority]" + text.removePrefix(authority)
        }
        val https = canonicalOrThrow("https://$text")
        val http = canonicalOrThrow("http://$text")
        val explicitPort = hasExplicitPort(text)
        return if (explicitPort) listOf(https, http)
        else listOf(https, withPort(http, 8096), http).distinct()
    }

    /** Canonical base URL, or null if [url] is not a valid http(s) URL. */
    fun canonical(url: String): String? = runCatching { canonicalOrThrow(url) }.getOrNull()

    private fun canonicalOrThrow(url: String): String {
        val parsed = url.trim().toHttpUrlOrNull() ?: throw Invalid("That doesn't look like a server address.")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty())
            throw Invalid("Don't include a username or password in the address.")
        val segments = parsed.pathSegments.filter { it.isNotEmpty() }
        val web = segments.indexOf("web")
        val kept = if (web >= 0) segments.subList(0, web) else segments
        val base = parsed.newBuilder().query(null).fragment(null).encodedPath("/").apply {
            kept.forEach { addPathSegment(it) }
        }.build()
        return base.toString().trimEnd('/')
    }

    private fun hasExplicitPort(schemeless: String): Boolean {
        val authority = schemeless.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostEnd = if (authority.startsWith("[")) authority.indexOf(']') + 1 else 0
        return authority.indexOf(':', hostEnd) >= 0
    }

    private fun withPort(url: String, port: Int): String =
        url.toHttpUrlOrNull()?.newBuilder()?.port(port)?.build()?.toString()?.trimEnd('/') ?: url
}
