package com.mav.marquee.ui.components

import android.os.Trace
import android.os.Build
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/** Narrow app-owned atrace spans. Placement and drawing remain outside the measure span. */
fun Modifier.traceMeasure(section: String): Modifier = layout { measurable, constraints ->
    NavWorkTrace.observer?.measured(section)
    val tracing = Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()
    if (tracing) Trace.beginSection(section)
    val placeable = try { measurable.measure(constraints) } finally { if (tracing) Trace.endSection() }
    layout(placeable.width, placeable.height) {
        NavWorkTrace.observer?.placed(section)
        placeable.place(0, 0)
    }
}

/** Release diagnostics only; R8 removes this modifier from production builds. */
fun Modifier.traceDraw(section: String): Modifier {
    if (com.mav.marquee.BuildConfig.BUILD_TYPE != "profile") return this
    return this.then(Modifier.drawWithContent {
        val tracing = Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()
        if (tracing) Trace.beginSection(section)
        try { drawContent() } finally { if (tracing) Trace.endSection() }
    })
}
