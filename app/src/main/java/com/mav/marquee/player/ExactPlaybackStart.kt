package com.mav.marquee.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint

internal object ExactPlaybackStart {
    fun localMs(requestedMs: Long, streamStartMs: Long): Long =
        (requestedMs - streamStartMs).coerceAtLeast(0)
}

/**
 * A new timed MKV can decode forward from byte zero to its initial exact target.
 * Media3 otherwise clamps an Unseekable period's target to zero. Every byte request
 * stays at zero; later user seeks always negotiate a new timed stream in the Activity.
 */
@UnstableApi
internal class PrerollSeekMap(private val duration: Long) : SeekMap {
    override fun isSeekable() = true
    override fun getDurationUs() = duration
    override fun getSeekPoints(timeUs: Long) = SeekMap.SeekPoints(SeekPoint.START)
}
