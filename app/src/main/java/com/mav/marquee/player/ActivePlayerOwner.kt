package com.mav.marquee.player

/** Main-thread ownership. Release the previous Activity before building another ExoPlayer. */
internal object ActivePlayerOwner {
    private var owner: Any? = null
    private var release: (() -> Unit)? = null

    fun replace(next: Any, stopPrevious: () -> Unit) {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        release?.invoke()
        owner = next
        release = stopPrevious
    }

    fun clear(previous: Any) {
        if (owner === previous) {
            owner = null
            release = null
        }
    }
}
