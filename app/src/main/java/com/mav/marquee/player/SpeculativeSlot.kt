package com.mav.marquee.player

/** Clock-driven ownership policy. A stale negotiation can never publish a stream for a new focus. */
internal class SpeculativeSlot<T>(private val dispose: (T) -> Unit) {
    companion object { const val DWELL_MS = 600L; const val TTL_MS = 45_000L }
    private var key: String? = null
    private var focusedAt = 0L
    private var startedAt: Long? = null
    private var value: T? = null
    private var generation = 0L
    private var active = false

    @Synchronized fun focus(next: String, now: Long): Long {
        if (key == next) return generation
        clear()
        key = next; focusedAt = now
        return generation
    }
    @Synchronized fun blur(expected: String) { if (!active && key == expected) clear() }
    @Synchronized fun start(token: Long, now: Long, eligible: Boolean, create: () -> T): T? {
        if (active || token != generation || key == null || !eligible || value != null ||
            now - focusedAt < DWELL_MS || startedAt != null) return null
        return create().also { value = it; startedAt = now }
    }
    @Synchronized fun expire(now: Long) {
        if (startedAt?.let { now - it >= TTL_MS } == true) clear()
    }
    @Synchronized fun adopt(expected: String, now: Long): T? {
        expire(now)
        val result = if (key == expected) value.also { value = null } else null
        clear(); active = true
        return result
    }
    @Synchronized fun playbackStarting() { active = true }
    @Synchronized fun playbackEnded() { active = false; clear() }
    @Synchronized fun cancel() { clear() }
    @Synchronized fun cancelUnlessPlaying() { if (!active) clear() }
    private fun clear() {
        value?.let(dispose); value = null; key = null; startedAt = null; generation++
    }
}
