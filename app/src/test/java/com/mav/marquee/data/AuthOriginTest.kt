package com.mav.marquee.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The access token is only ever attached to the configured server origin and base path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class AuthOriginTest {
    private val server = MockWebServer()
    private val other = MockWebServer()

    @Before fun setup() {
        Session::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(null, null)
        Session.init(RuntimeEnvironment.getApplication())
        server.start(); other.start()
        Session.login(server.url("/jf").toString(), "synthetic-token", "user", "Demo")
    }

    @After fun cleanup() {
        Session.logout(); Session.setServer("")
        server.shutdown(); other.shutdown()
    }

    private fun fetch(url: String) = Session.httpClient.newCall(Request.Builder().url(url).build()).execute().use { it.code }

    @Test fun originAndBasePathMatching() {
        val base = "https://media.example.com/jf/".toHttpUrl()
        assertTrue(Session.isServerUrl(base, "https://media.example.com/jf/Items".toHttpUrl()))
        assertTrue(Session.isServerUrl(base, "https://MEDIA.example.com/jf".toHttpUrl()))
        assertFalse(Session.isServerUrl(base, "https://media.example.com/jfx/Items".toHttpUrl()))
        assertFalse(Session.isServerUrl(base, "http://media.example.com/jf/Items".toHttpUrl()))
        assertFalse(Session.isServerUrl(base, "https://media.example.com:8443/jf/Items".toHttpUrl()))
        assertFalse(Session.isServerUrl(base, "https://media.example.com.evil.example/jf/Items".toHttpUrl()))
        assertFalse(Session.isServerUrl(null, "https://media.example.com/jf/Items".toHttpUrl()))
        val root = "http://jellyfin.local:8096/".toHttpUrl()
        assertTrue(Session.isServerUrl(root, "http://jellyfin.local:8096/Items/x/Images/Primary".toHttpUrl()))
        assertFalse(Session.isServerUrl(root, "http://jellyfin.local:809/Items".toHttpUrl()))
    }

    @Test fun headerOnlyOnServerRequests() {
        server.enqueue(MockResponse().setBody("{}"))
        fetch(server.url("/jf/System/Info").toString())
        assertTrue(server.takeRequest().getHeader("Authorization")!!.contains("Token=\"synthetic-token\""))

        other.enqueue(MockResponse().setBody("{}"))
        fetch(other.url("/jf/System/Info").toString())
        assertNull(other.takeRequest().getHeader("Authorization"))

        // Same host and port but outside the base path.
        server.enqueue(MockResponse().setBody("{}"))
        fetch(server.url("/other/System/Info").toString())
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun redirectToAnotherOriginDropsToken() {
        // OkHttp removes Authorization when a redirect changes scheme, host or port.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/image.jpg").toString()))
        other.enqueue(MockResponse().setBody("ok"))
        assertEquals(200, fetch(server.url("/jf/Items/1/Images/Primary").toString()))
        assertTrue(server.takeRequest().getHeader("Authorization") != null)
        assertNull(other.takeRequest().getHeader("Authorization"))
    }
}
