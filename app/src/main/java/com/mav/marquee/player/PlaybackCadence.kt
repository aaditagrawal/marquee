package com.mav.marquee.player

import kotlin.math.abs
import kotlin.math.round

/** This panel has one 60 Hz mode. Fractional rates still drift against its clock. */
internal object PlaybackCadence {
    fun speeds(sourceFps: Float): List<Float> =
        if (abs(sourceFps - 25f) < .05f || abs(sourceFps - 50f) < .1f)
            listOf(.75f, 1f, 1.2f, 1.25f, 1.5f, 2f)
        else listOf(.75f, 1f, 1.25f, 1.5f, 2f)

    fun label(speed: Float, sourceFps: Float): String {
        val base = (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()) + "×"
        if (!sourceFps.isFinite() || sourceFps <= 0f) return base
        val outputFps = sourceFps * speed
        if (outputFps > 60.12f) return "$base · exceeds 60 fps"
        val repeats = 60f / outputFps
        val nearest = round(repeats)
        // Accept NTSC's ~0.1% clock drift, but not the audit's 23.809525 fps.
        val even = nearest >= 1f && abs(repeats - nearest) / nearest <= .002f
        return "$base · ${if (even) "smooth" else "may judder"}"
    }
}

/** Deliberately process-local: a new app session starts at normal speed. */
internal object PlaybackSessionSpeed {
    var speed = 1f
}

/** Wall time spent actually playing, excluding pauses and rebuffering. */
internal class PlaybackFrameRate {
    private var elapsedMs = 0L
    private var startedMs: Long? = null
    fun playing(playing: Boolean, nowMs: Long) {
        startedMs?.let { elapsedMs += (nowMs - it).coerceAtLeast(0) }
        startedMs = if (playing) nowMs else null
    }
    fun reset(playing: Boolean, nowMs: Long) {
        elapsedMs = 0
        startedMs = if (playing) nowMs else null
    }
    fun droppedPerMinute(dropped: Int, nowMs: Long): Double {
        val duration = elapsedMs + (startedMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0L)
        return if (duration > 0) dropped * 60_000.0 / duration else 0.0
    }
}
