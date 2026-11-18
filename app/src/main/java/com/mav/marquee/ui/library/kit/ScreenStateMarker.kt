package com.mav.marquee.ui.library.kit

import android.os.SystemClock
import android.util.Log
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import com.mav.marquee.BuildConfig

/** A debug draw marker, not a physical-presentation or screenshot-latency claim. */
@Composable
internal fun ScreenStateMarker(screen: String, state: String) {
    if (!BuildConfig.DEBUG) return
    val view = LocalView.current
    DisposableEffect(view, screen, state) {
        var recorded = false
        val listener = ViewTreeObserver.OnDrawListener {
            if (!recorded) {
                recorded = true
                Log.i("Marquee", "MARQUEE_SCREEN_STATE screen=$screen state=$state draw=${SystemClock.elapsedRealtime()}")
            }
        }
        view.viewTreeObserver.addOnDrawListener(listener)
        onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener) }
    }
}
