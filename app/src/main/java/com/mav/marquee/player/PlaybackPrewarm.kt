package com.mav.marquee.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Bounded, account/pref/track-scoped in-memory PlaybackInfo cache. Never persists media URLs. */
@UnstableApi
object PlaybackPrewarm {
    private const val TTL_MS = 30_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private class Entry(val key: String, val expires: Long, val task: Deferred<Warm?>)
    internal class Warm(val item: JsonObject, val resolved: Resolved, val backdrop: Bitmap?)
    private val entries = LinkedHashMap<String, Entry>()

    private val encodingMutex = Mutex()
    private val retired = java.util.concurrent.ConcurrentLinkedQueue<SpeculativeDataSource.Lease>()
    private val invalidSessions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val speculative = SpeculativeSlot<SpeculativeDataSource.Lease> { lease ->
        lease.stream.close()
        invalidSessions[lease.resolved.playSessionId] = SystemClock.elapsedRealtime()
        retired.add(lease)
        scheduleCleanup()
    }
    private val cleanupLock = Any()
    private var cleanupJob: Job? = null
    private fun scheduleCleanup(): Unit = synchronized(cleanupLock) {
        if (cleanupJob?.isActive == true) return@synchronized
        cleanupJob = scope.launch {
            try {
                while (retired.isNotEmpty()) {
                    val success = encodingMutex.withLock { drainRetired() }
                    if (!success) delay(5_000)
                }
            } finally {
                synchronized(cleanupLock) {
                    cleanupJob = null
                    if (retired.isNotEmpty()) scheduleCleanup()
                }
            }
        }
    }
    private var focusJob: Job? = null
    private var focusedKey: String? = null

    // Captured request/client keep cleanup scoped to the original account and device after logout.
    private fun drainRetired(): Boolean {
        while (true) {
            val lease = retired.peek() ?: return true
            lease.stream.awaitClosed()
            var stopped = false
            repeat(3) {
                if (!stopped) stopped = runCatching {
                    lease.client.newCall(lease.cleanup).execute().use { it.isSuccessful }
                }.getOrDefault(false)
            }
            if (!stopped) { Log.w("MarqueePlayer", "PRESTART scoped cleanup pending"); return false }
            retired.remove(lease)
        }
    }

    /** Detail Play/Resume focus only. Entry prewarm alone does not start an encoder. */
    @Synchronized
    fun focus(context: Context, itemId: String, focused: Boolean, startPositionMs: Long = 0,
        mediaSourceId: String? = null, audioStreamIndex: Int? = null, subtitleStreamIndex: Int? = null) {
        val k = key(context, itemId, startPositionMs, mediaSourceId, audioStreamIndex, subtitleStreamIndex)
        if (!focused) { speculative.blur(k); if (focusedKey == k) { focusJob?.cancel(); focusedKey = null }; return }
        if (focusedKey == k) return
        focusJob?.cancel(); focusedKey = k
        val token = speculative.focus(k, SystemClock.elapsedRealtime())
        val warm = obtain(context.applicationContext, itemId, startPositionMs, mediaSourceId, audioStreamIndex, subtitleStreamIndex)
        focusJob = scope.launch {
            delay(SpeculativeSlot.DWELL_MS)
            val w = warm.await() ?: return@launch
            val r = StreamResolver.speculativeProgressive(w.resolved, startPositionMs) ?: return@launch
            encodingMutex.withLock {
                if (!drainRetired()) return@withLock
                val lease = speculative.start(token, SystemClock.elapsedRealtime(), true) {
                    val client = Env.http.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
                    val uri = r.mediaItem.localConfiguration!!.uri.toString()
                    val cleanupUrl = (Env.serverUrl + "/Videos/ActiveEncodings").toHttpUrl().newBuilder()
                            .addQueryParameter("deviceId", Env.deviceId).addQueryParameter("playSessionId", r.playSessionId).build()
                    // Auth is captured now rather than read from Session during eventual cleanup.
                    val auth = Env.authHeader()
                    val cleanupClient = okhttp3.OkHttpClient.Builder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
                    SpeculativeDataSource.Lease(r, BufferedLiveStream(client.newCall(Request.Builder().url(uri)
                        .header("Authorization", auth).build())), Request.Builder().url(cleanupUrl)
                        .header("Authorization", auth).delete().build(), cleanupClient, w).also { it.stream.start() }
                }
                if (lease != null) scope.launch {
                    delay(SpeculativeSlot.TTL_MS)
                    speculative.expire(SystemClock.elapsedRealtime())
                }
            }
        }
    }

    /** Reserve before startActivity so detail focus loss cannot cancel the stream being adopted. */
    @Synchronized fun playbackStarting() { speculative.playbackStarting(); focusJob?.cancel(); focusedKey = null }
    @Synchronized fun leaveDetail() { focusJob?.cancel(); focusedKey = null; speculative.cancelUnlessPlaying() }
    @Synchronized internal fun releaseAdopted(sessionId: String? = null) {
        SpeculativeDataSource.adopted?.takeIf { sessionId == null || it.resolved.playSessionId == sessionId }?.let { lease ->
            SpeculativeDataSource.adopted = null; lease.stream.close(); retired.add(lease)
            scheduleCleanup()
        }
    }
    @Synchronized internal fun playbackEnded() { releaseAdopted(); speculative.playbackEnded() }
    @Synchronized internal fun discardPending() { speculative.cancel() }

    /** Call when a playable detail opens or Play receives focus. Series must resolve an episode first. */
    fun prewarm(
        context: Context, itemId: String, startPositionMs: Long = 0,
        mediaSourceId: String? = null, audioStreamIndex: Int? = null, subtitleStreamIndex: Int? = null,
    ) {
        obtain(context.applicationContext, itemId, startPositionMs, mediaSourceId, audioStreamIndex, subtitleStreamIndex)
    }

    private fun key(context: Context, id: String, start: Long, source: String?, audio: Int?, sub: Int?): String {
        val p = PlayerPrefs(context)
        return listOf(Env.serverUrl, Env.userId, Env.authHeader(), id, start, source, audio, sub,
            p.maxBitrate, p.audioLanguage, p.subtitleLanguage, p.subtitleMode).joinToString("|")
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Synchronized
    private fun obtain(context: Context, id: String, start: Long, source: String?, audio: Int?, sub: Int?): Deferred<Warm?> {
        val now = SystemClock.elapsedRealtime()
        entries.entries.removeAll { if (it.value.expires < now) { it.value.task.cancel(); true } else false }
        val k = key(context, id, start, source, audio, sub)
        invalidSessions.entries.removeAll { now - it.value > TTL_MS + SpeculativeSlot.TTL_MS }
        entries[id]?.takeIf { entry ->
            entry.key == k && !(entry.task.isCompleted && !entry.task.isCancelled &&
                entry.task.getCompleted()?.resolved?.playSessionId?.let { invalidSessions.containsKey(it) } == true)
        }?.let { return it.task }
        while (entries.size >= 2) entries.remove(entries.keys.first())?.task?.cancel()
        val task = scope.async {
            try {
                withTimeout(12_000) {
                    if (!Env.isLoggedIn) return@withTimeout null
                    val item = JfApi.item(id)
                    val r = StreamResolver.resolve(item, DeviceProfile.Mode.Direct, PlayerPrefs(context).maxBitrate,
                        start, audio, sub, PlayerPrefs(context), source)
                    // HEAD reuses the authenticated connection; never starts an unused encoder or downloads video.
                    if (!r.isTranscode) runCatching {
                        Env.http.newBuilder().callTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
                            .newCall(Request.Builder().url(r.mediaItem.localConfiguration!!.uri.toString()).head().build())
                            .execute().close()
                    }
                    PrewarmBytes.warm(r)
                    PrewarmExtractors.warm(r, PrewarmBytes.wrap(androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(Env.http)))
                    val backdrop = loadBackdrop(item)
                    Log.i("MarqueePlayer", "PREWARM ready item=$id method=${r.playMethod} start=$start source=${source ?: "-"} audio=${audio ?: "-"} sub=${sub ?: "-"}")
                    Warm(item, r, backdrop)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { Log.i("MarqueePlayer", "PREWARM unavailable item=$id"); null }
        }
        entries.remove(id)?.task?.cancel()
        entries[id] = Entry(k, now + TTL_MS, task)
        return task
    }

    internal suspend fun take(context: Context, id: String, start: Long, source: String?, audio: Int?, sub: Int?): Warm? {
        val lease = speculative.adopt(key(context, id, start, source, audio, sub), SystemClock.elapsedRealtime())
        SpeculativeDataSource.adopted = lease
        // A quick blur/refocus must not reuse a session whose DELETE is still queued.
        val clean = kotlinx.coroutines.withContext(Dispatchers.IO) { encodingMutex.withLock { drainRetired() } }
        if (!clean) { releaseAdopted(); throw java.io.IOException("Previous speculative encoding cleanup pending") }
        lease?.warm?.let { warm ->
            synchronized(this) { entries.remove(id)?.task?.cancel() }
            return Warm(warm.item, lease.resolved, warm.backdrop)
        }
        val expectedKey = key(context, id, start, source, audio, sub)
        val candidate = synchronized(this) { entries.remove(id) }
        val entry = candidate?.takeIf {
            (lease != null || it.expires >= SystemClock.elapsedRealtime()) && it.key == expectedKey
        }
        Log.i("MarqueePlayer", "PREWARM adoption item=$id lease=${lease != null} entry=${candidate != null} match=${candidate?.key == expectedKey}")
        if (entry == null) { releaseAdopted(); return null }
        return try { entry.task.await()?.takeUnless { lease == null && invalidSessions.containsKey(it.resolved.playSessionId) }
            ?.let { Warm(it.item, lease?.resolved ?: it.resolved, it.backdrop) } }
        catch (_: Exception) { releaseAdopted(); null }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun backdrop(id: String): Bitmap? = synchronized(this) {
        entries[id]?.takeIf { it.expires >= SystemClock.elapsedRealtime() && it.task.isCompleted && !it.task.isCancelled && it.key.startsWith(listOf(Env.serverUrl, Env.userId, Env.authHeader()).joinToString("|") + "|") }
            ?.task?.getCompleted()?.backdrop
    }

    internal fun loadBackdrop(item: JsonObject): Bitmap? = runCatching {
        val id = if (item.arr("BackdropImageTags").isNotEmpty()) item.str("Id") else item.str("ParentBackdropItemId") ?: item.str("SeriesId")
        if (id == null) return@runCatching null
        Env.http.newBuilder().callTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build().newCall(
            Request.Builder().url("${Env.serverUrl}/Items/$id/Images/Backdrop/0?maxWidth=960&maxHeight=540&quality=70").build()
        ).execute().use { r -> if (!r.isSuccessful) null else r.body?.byteStream()?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })?.also { bitmap -> bitmap.prepareToDraw() }
        } }
    }.getOrNull()
}
