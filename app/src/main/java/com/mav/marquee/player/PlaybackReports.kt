package com.mav.marquee.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Order server metadata while frames and scoped encoder cleanup continue independently. */
internal class PlaybackReports(
    private val scope: CoroutineScope,
    private val send: suspend (String, JsonObject) -> Unit,
) {
    private var tail: Job? = null

    // Called on the Activity thread. Capture the body before its media item changes.
    fun submit(path: String, body: JsonObject): Job {
        val previous = tail
        return scope.launch(NonCancellable) {
            previous?.join()
            send(path, body)
        }.also { tail = it }
    }
}
