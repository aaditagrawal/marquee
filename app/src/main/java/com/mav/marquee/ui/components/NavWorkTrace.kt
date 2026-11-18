package com.mav.marquee.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.NonRestartableComposable

/** Optional JVM-test observer. Null during normal launches; no snapshot writes or timing logs. */
object NavWorkTrace {
    interface Observer {
        fun composed(scope: String)
        fun measured(scope: String)
        fun placed(scope: String)
        fun keyWork(nanos: Long) {}
    }
    var observer: Observer? = null
    var clock: (() -> Long)? = null

    private var keyTimerActive = false // JVM observer runs on Android main; no nested double counting.
    fun keyStart(): Long {
        if (observer == null || keyTimerActive) return 0L
        keyTimerActive = true
        return clock?.invoke() ?: System.nanoTime()
    }
    fun keyEnd(start: Long) {
        if (start == 0L) return
        val elapsed = (clock?.invoke() ?: System.nanoTime()) - start
        keyTimerActive = false
        observer?.keyWork(elapsed)
    }

    @Composable
    @NonRestartableComposable
    fun composition(scope: String) {
        if (observer != null) SideEffect { observer?.composed(scope) }
    }
}
