package com.mav.marquee.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackReportsTest {
    private val body = JsonObject(emptyMap())

    @Test fun delayedOldStopCannotEraseReplacementPlaybackAndDoesNotBlockFrames() = runBlocking {
        val releaseOldStop = CompletableDeferred<Unit>()
        val enteredOldStop = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var nowPlaying: String? = "old"
        val events = mutableListOf<String>()
        val reports = PlaybackReports(scope) { path, _ ->
            if (path == "Stopped") {
                enteredOldStop.complete(Unit)
                releaseOldStop.await()
                nowPlaying = null
            } else if (path == "Playing") nowPlaying = "replacement"
            events += path
        }
        val stop = reports.submit("Stopped", body)
        enteredOldStop.await()
        val start = reports.submit("Playing", body)
        val progress = reports.submit("Progress", body)
        // Enqueuing replacement metadata returns immediately, so its frame can display.
        assertFalse(start.isCompleted)
        assertEquals("old", nowPlaying)
        releaseOldStop.complete(Unit)
        joinAll(stop, start, progress)
        assertEquals(listOf("Stopped", "Playing", "Progress"), events)
        assertEquals("replacement", nowPlaying)
        scope.cancel()
    }

    @Test fun terminalStopStillFollowsPendingStartWhenActivityScopeIsCancelled() = runBlocking {
        val releaseStart = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var nowPlaying = false
        val reports = PlaybackReports(scope) { path, _ ->
            if (path == "Playing") {
                releaseStart.await()
                nowPlaying = true
            } else nowPlaying = false
        }
        val start = reports.submit("Playing", body)
        val stop = reports.submit("Stopped", body)
        scope.cancel()
        releaseStart.complete(Unit)
        joinAll(start, stop)
        assertFalse(nowPlaying)
    }
}
