package com.mav.marquee.ui.library.kit

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.mav.marquee.BuildConfig
import kotlinx.coroutines.delay

/** Debug-only, request-local fault injection. Never changes routes, connectivity or server data. */
internal class ScreenTestPolicy private constructor(
    private val action: String?, private val delayMs: Long, private var failures: Int,
) {
    suspend fun before(operation: String) {
        if (action != operation || !BuildConfig.DEBUG) return
        val start = SystemClock.elapsedRealtime()
        Log.i("Marquee", "MARQUEE_SCREEN_TEST action=$operation begin=$start delay=$delayMs")
        if (delayMs > 0) delay(delayMs)
        if (failures > 0) { failures--; throw java.io.IOException("Screen test unavailable") }
    }

    companion object {
        fun from(context: Context): ScreenTestPolicy {
            val intent = (context as? Activity)?.intent
            return if (BuildConfig.DEBUG && intent != null) ScreenTestPolicy(
                intent.getStringExtra("screenTestAction"),
                intent.getLongExtra("screenTestDelayMs", 0).coerceIn(0, 15_000),
                if (intent.getBooleanExtra("screenTestFailOnce", false)) 1 else 0,
            ) else ScreenTestPolicy(null, 0, 0)
        }
    }
}
