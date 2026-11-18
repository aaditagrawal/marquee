package com.mav.marquee.player

import com.mav.marquee.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The only file in .player that touches app-level classes (data.Session, see docs/architecture/PLAYER_CONTRACT.md).
 * Everything else in the package depends on this facade, so .data refactors break at most one file.
 */
internal object Env {
    val serverUrl: String get() = Session.serverUrl
    val userId: String get() = Session.userId
    val deviceId: String get() = Session.deviceId
    val isLoggedIn: Boolean get() = Session.isLoggedIn
    val http: OkHttpClient get() = Session.httpClient
    fun login(url: String, token: String, user: String, name: String) = Session.login(url, token, user, name)
    fun authHeader(token: String = Session.accessToken): String = Session.authorizationHeader(token)
}

internal class JfHttpException(val code: Int, msg: String) : IOException(msg)

/** Minimal Jellyfin calls the player needs; JSON is kept as JsonObject to stay independent of .data models. */
internal object JfApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val JSON = "application/json".toMediaType()

    private suspend fun call(req: Request): JsonElement? = withContext(Dispatchers.IO) {
        Env.http.newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw JfHttpException(r.code, "HTTP ${r.code} ${req.method} ${req.url.encodedPath}")
            val body = r.body?.string().orEmpty()
            if (body.isBlank()) null else json.parseToJsonElement(body)
        }
    }

    private fun url(path: String) = Env.serverUrl + path

    suspend fun get(path: String): JsonObject =
        call(Request.Builder().url(url(path)).get().build())?.jsonObject ?: JsonObject(emptyMap())

    suspend fun post(path: String, body: JsonElement? = null): JsonElement? =
        call(Request.Builder().url(url(path)).post((body?.toString() ?: "").toRequestBody(JSON)).build())

    suspend fun delete(path: String) {
        call(Request.Builder().url(url(path)).delete().build())
    }

    suspend fun item(id: String): JsonObject =
        get("/Users/${Env.userId}/Items/$id?fields=MediaSources,MediaStreams,Chapters,Trickplay,Overview")

    suspend fun playbackInfo(
        itemId: String,
        profile: JsonObject,
        maxBitrate: Int,
        startTicks: Long,
        mediaSourceId: String?,
        audioIndex: Int?,
        subtitleIndex: Int?,
        mode: DeviceProfile.Mode,
    ): JsonObject {
        return post("/Items/$itemId/PlaybackInfo?userId=${Env.userId}",
            playbackInfoBody(Env.userId, profile, maxBitrate, startTicks, mediaSourceId, audioIndex, subtitleIndex, mode))?.jsonObject
            ?: throw IOException("empty PlaybackInfo")
    }

    internal fun playbackInfoBody(
        userId: String, profile: JsonObject, maxBitrate: Int, startTicks: Long,
        mediaSourceId: String?, audioIndex: Int?, subtitleIndex: Int?, mode: DeviceProfile.Mode,
    ): JsonObject = buildJsonObject {
        put("UserId", userId)
        put("DeviceProfile", profile)
        put("MaxStreamingBitrate", if (maxBitrate > 0) maxBitrate else DeviceProfile.UNLIMITED_BITRATE)
        put("StartTimeTicks", startTicks)
        if (mediaSourceId != null) put("MediaSourceId", mediaSourceId)
        if (audioIndex != null) put("AudioStreamIndex", audioIndex)
        if (subtitleIndex != null) put("SubtitleStreamIndex", subtitleIndex)
        put("EnableDirectPlay", mode == DeviceProfile.Mode.Direct)
        put("EnableDirectStream", mode == DeviceProfile.Mode.Direct)
        put("EnableTranscoding", true)
        put("AllowVideoStreamCopy", mode == DeviceProfile.Mode.Direct || mode == DeviceProfile.Mode.Remux)
        put("AllowAudioStreamCopy", mode == DeviceProfile.Mode.Direct)
        put("AutoOpenLiveStream", true)
    }

    suspend fun mediaSegments(itemId: String): JsonArray =
        runCatching { get("/MediaSegments/$itemId").arr("Items") }.getOrDefault(JsonArray(emptyList()))

    /** Next episode in the same series after [itemId], or null. */
    suspend fun nextEpisode(seriesId: String, itemId: String): JsonObject? {
        val r = get("/Shows/$seriesId/Episodes?userId=${Env.userId}&startItemId=$itemId&limit=2&fields=Overview")
        val items = r.arr("Items")
        return items.getOrNull(1)?.jsonObject
    }

    suspend fun children(parentId: String, sort: String): JsonArray =
        get("/Users/${Env.userId}/Items?parentId=$parentId&sortBy=$sort&recursive=true&includeItemTypes=Audio&fields=MediaSources")
            .arr("Items")

    suspend fun instantMix(itemId: String): JsonArray =
        get("/Items/$itemId/InstantMix?userId=${Env.userId}&limit=60").arr("Items")

    suspend fun report(path: String, body: JsonObject) {
        runCatching { post(path, body) }
    }

    suspend fun stopEncoding(playSessionId: String) {
        runCatching { delete("/Videos/ActiveEncodings?deviceId=${Env.deviceId}&playSessionId=$playSessionId") }
    }
}

// --- tiny JSON accessors -------------------------------------------------------------------------

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
internal fun JsonObject.arr(k: String): JsonArray = (this[k] as? JsonArray) ?: JsonArray(emptyList())
internal fun JsonArray.objs(): List<JsonObject> = mapNotNull { it as? JsonObject }
@Suppress("unused") internal fun JsonElement.asArr(): JsonArray = jsonArray
