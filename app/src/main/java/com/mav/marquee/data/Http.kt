package com.mav.marquee.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared JSON config: tolerant of server-side additions, compact on disk. */
val MarqueeJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}

class HttpException(val code: Int, message: String) : IOException(message)

internal val JSON_MEDIA = "application/json".toMediaType()
internal val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

/** Await an OkHttp call without blocking a thread; cancels the call on coroutine cancellation. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
}

internal fun url(base: String, path: String, build: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
    "$base$path".toHttpUrl().newBuilder().apply(build).build()

internal fun HttpUrl.Builder.q(name: String, value: Any?): HttpUrl.Builder {
    if (value != null) addQueryParameter(name, value.toString())
    return this
}

internal fun HttpUrl.Builder.q(name: String, values: Collection<String>?): HttpUrl.Builder {
    if (!values.isNullOrEmpty()) addQueryParameter(name, values.joinToString(","))
    return this
}

internal fun get(u: HttpUrl): Request = Request.Builder().url(u).get().build()
internal fun post(u: HttpUrl, body: RequestBody = EMPTY_BODY): Request = Request.Builder().url(u).post(body).build()
internal fun delete(u: HttpUrl): Request = Request.Builder().url(u).delete().build()
