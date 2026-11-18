package com.mav.marquee.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import com.mav.marquee.BuildConfig
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Process-wide Jellyfin session. Shared contract with the player package (see docs/architecture/PLAYER_CONTRACT.md).
 *
 * - [serverUrl]   normalized base URL without trailing slash, e.g. https://media.example.com/jellyfin
 * - [accessToken] user access token (never log it)
 * - [userId]      Jellyfin user id (dashless hex)
 * - [deviceId]    random per-install id used in the Authorization header (not hardware-derived)
 * - [httpClient]  shared OkHttpClient; adds the MediaBrowser Authorization header only to requests
 *                 whose origin (scheme, host, port) and base path match [serverUrl].
 *
 * Storage: app-private SharedPreferences (`marquee_session`). The manifest disables backup and
 * device-to-device transfer, so the token stays on this install. See docs/dev/BUILD_NOTES.md.
 */
object Session {
    const val CLIENT_NAME = "Marquee"
    val clientVersion: String = BuildConfig.VERSION_NAME

    private const val PREFS = "marquee_session"

    @Volatile var serverUrl: String = ""
        private set
    @Volatile var accessToken: String = ""
        private set
    @Volatile var userId: String = ""
        private set
    @Volatile var userName: String = ""
        private set
    @Volatile var deviceId: String = ""
        private set
    @Volatile private var serverBase: HttpUrl? = null
    // Application context only (never an Activity), so holding it statically does not leak.
    @android.annotation.SuppressLint("StaticFieldLeak")
    @Volatile private var app: Context? = null

    private lateinit var prefs: SharedPreferences

    /** The name users gave this device (Settings), else its model; ASCII-only for the HTTP header. */
    val deviceName: String by lazy {
        val raw = app?.let { ctx ->
            runCatching { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: runCatching { Build.MODEL }.getOrNull()
        sanitizeDeviceName(raw)
    }

    val isLoggedIn: Boolean
        get() = serverUrl.isNotEmpty() && accessToken.isNotEmpty() && userId.isNotEmpty()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        app = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        deviceId = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().replace("-", "").also {
            prefs.edit().putString("deviceId", it).apply()
        }
        applyServer(prefs.getString("serverUrl", "") ?: "")
        accessToken = prefs.getString("accessToken", "") ?: ""
        userId = prefs.getString("userId", "") ?: ""
        userName = prefs.getString("userName", "") ?: ""
    }

    /** Sets the server base URL (already validated; blank clears it). */
    fun setServer(url: String) {
        applyServer(if (url.isBlank()) "" else normalizeUrl(url))
        prefs.edit().putString("serverUrl", serverUrl).apply()
    }

    fun login(url: String, token: String, user: String, name: String) {
        applyServer(normalizeUrl(url))
        accessToken = token
        userId = user
        userName = name
        prefs.edit()
            .putString("serverUrl", serverUrl)
            .putString("accessToken", token)
            .putString("userId", user)
            .putString("userName", name)
            .apply()
    }

    /** Clears the token and user. The server address is kept so the next sign-in starts there. */
    fun logout() {
        accessToken = ""
        userId = ""
        userName = ""
        prefs.edit().remove("accessToken").remove("userId").remove("userName").apply()
    }

    private fun applyServer(url: String) {
        serverUrl = url
        serverBase = url.takeIf { it.isNotEmpty() }?.let { "$it/".toHttpUrlOrNull() }
    }

    /** Value for the `Authorization` header. Token omitted when not logged in. */
    fun authorizationHeader(token: String = accessToken): String = buildString {
        append("MediaBrowser Client=\"").append(CLIENT_NAME)
        append("\", Device=\"").append(deviceName)
        append("\", DeviceId=\"").append(deviceId)
        append("\", Version=\"").append(clientVersion).append('"')
        if (token.isNotEmpty()) append(", Token=\"").append(token).append('"')
    }

    /**
     * True only for URLs on the configured server: same scheme, host and port, and inside its base path.
     * A plain string prefix check would also match `http://host:80960` or `http://host.evil.example`.
     */
    internal fun isServerUrl(base: HttpUrl?, url: HttpUrl): Boolean {
        if (base == null || url.scheme != base.scheme || !url.host.equals(base.host, ignoreCase = true) || url.port != base.port) return false
        val basePath = base.encodedPath // always ends with '/'
        return basePath == "/" || url.encodedPath.startsWith(basePath) || url.encodedPath == basePath.dropLast(1)
    }

    /**
     * Application interceptor: runs once per call, before redirects. OkHttp itself removes the
     * Authorization header when following a redirect to a different host, so the token never
     * follows a server redirect to another origin.
     */
    private val authInterceptor = Interceptor { chain ->
        val req = chain.request()
        if (req.header("Authorization") == null && isServerUrl(serverBase, req.url)) {
            chain.proceed(req.newBuilder().header("Authorization", authorizationHeader()).build())
        } else chain.proceed(req)
    }

    /** Shared client: long-lived keep-alive pool, transparent gzip (OkHttp default). */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 12; maxRequests = 32 })
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(authInterceptor)
            .build()
    }

    /**
     * Canonical form of an address that already has a scheme: no trailing slash, query or fragment.
     * Inputs without a scheme get http:// (legacy stored values); the login screen resolves the scheme
     * explicitly with [ServerAddress.candidates] before anything is stored.
     */
    fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        val withScheme = if (ServerAddress.hasScheme(trimmed)) trimmed else "http://$trimmed"
        return ServerAddress.canonical(withScheme) ?: trimmed.trimEnd('/')
    }

    /** OkHttp rejects non-ASCII header values; Jellyfin also splits on quotes and commas. */
    internal fun sanitizeDeviceName(raw: String?): String {
        val cleaned = raw.orEmpty()
            .map { c -> if (c in ' '..'~' && c !in "\"\\,=") c else ' ' }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(64)
        return cleaned.ifEmpty { "Android TV" }
    }
}
