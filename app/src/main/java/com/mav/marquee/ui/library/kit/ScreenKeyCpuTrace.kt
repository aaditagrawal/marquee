package com.mav.marquee.ui.library.kit

/** Optional JVM method clock. Null in normal launches; no clocks, allocations or logs on TV keys. */
internal object ScreenKeyCpuTrace {
    var clock: (() -> Long)? = null
    var work: ((Long) -> Unit)? = null
    private var active = false

    fun start(): Long {
        val timer = clock ?: return -1
        if (active) return -1
        active = true
        return timer()
    }

    fun end(start: Long) {
        if (start < 0) return
        val elapsed = requireNotNull(clock)() - start
        active = false
        work?.invoke(elapsed)
    }

    fun clear() { clock = null; work = null; active = false }
}
