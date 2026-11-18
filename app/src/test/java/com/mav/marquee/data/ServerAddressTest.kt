package com.mav.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerAddressTest {
    private fun c(input: String) = ServerAddress.candidates(input)

    @Test fun explicitSchemeIsUsedAsTyped() {
        assertEquals(listOf("http://jellyfin.local:8096"), c("http://jellyfin.local:8096/"))
        assertEquals(listOf("https://media.example.com"), c("HTTPS://Media.Example.com:443///"))
        assertEquals(listOf("https://media.example.com/jellyfin"), c("https://media.example.com/jellyfin/"))
        assertEquals(listOf("https://media.example.com:8920/jf"), c(" https://media.example.com:8920/jf?x=1#frag "))
    }

    @Test fun pastedWebClientUrlIsTrimmedToBase() {
        assertEquals(listOf("https://media.example.com"), c("https://media.example.com/web/#/home.html"))
        assertEquals(listOf("http://jellyfin.local:8096/jf"), c("http://jellyfin.local:8096/jf/web/index.html"))
    }

    @Test fun schemelessTriesHttpsThenHttp() {
        assertEquals(listOf("https://jellyfin.local:8096", "http://jellyfin.local:8096"), c("jellyfin.local:8096"))
        assertEquals(listOf("https://media.example.com", "http://media.example.com:8096", "http://media.example.com"), c("media.example.com"))
        assertEquals(listOf("https://10.0.0.5", "http://10.0.0.5:8096", "http://10.0.0.5"), c("10.0.0.5/"))
        assertEquals(listOf("https://media.example.com/jellyfin", "http://media.example.com:8096/jellyfin", "http://media.example.com/jellyfin"),
            c("media.example.com/jellyfin"))
    }

    @Test fun ipv6Literals() {
        assertEquals(listOf("http://[fd00::1]:8096"), c("http://[fd00::1]:8096"))
        assertEquals(listOf("https://[fd00::1]:8096", "http://[fd00::1]:8096"), c("[fd00::1]:8096"))
        // Bare IPv6 cannot carry a port; it is bracketed and Jellyfin's default port tried over HTTP.
        assertEquals(listOf("https://[fd00::1]", "http://[fd00::1]:8096", "http://[fd00::1]"), c("fd00::1"))
    }

    @Test fun rejectsUnusableInput() {
        for (bad in listOf("", "   ", "ftp://host", "http://user:pw@host:8096", "http://", "host name")) {
            assertThrows(bad, ServerAddress.Invalid::class.java) { c(bad) }
        }
    }

    @Test fun sessionNormalizationKeepsStoredValues() {
        // Previously stored addresses are canonical already and must round-trip unchanged.
        assertEquals("http://jellyfin.local:8096", Session.normalizeUrl("http://jellyfin.local:8096"))
        assertEquals("https://media.example.com/jellyfin", Session.normalizeUrl("https://media.example.com/jellyfin/"))
        assertEquals("http://[fd00::1]:8096", Session.normalizeUrl("http://[fd00::1]:8096/"))
        assertEquals("", Session.normalizeUrl("  "))
    }

    @Test fun deviceNameIsHeaderSafe() {
        assertEquals("Living Room TV", Session.sanitizeDeviceName("  Living\tRoom   TV "))
        assertEquals("Wohnzimmer-Fernseher", Session.sanitizeDeviceName("Wohnzimmer-Fernseher"))
        assertEquals("Caf TV", Session.sanitizeDeviceName("Café TV"))
        assertEquals("Bob s TV", Session.sanitizeDeviceName("Bob\"s TV"))
        assertEquals("Android TV", Session.sanitizeDeviceName(null))
        assertEquals("Android TV", Session.sanitizeDeviceName("电视"))
        assertEquals(64, Session.sanitizeDeviceName("x".repeat(200)).length)
    }
}
