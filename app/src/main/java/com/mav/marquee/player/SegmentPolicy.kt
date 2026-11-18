package com.mav.marquee.player

import kotlinx.serialization.json.JsonArray

internal data class MediaSegment(val type: String, val startMs: Long, val endMs: Long)

internal fun parseMediaSegments(itemId: String, records: JsonArray): List<MediaSegment> = records.objs().mapNotNull { record ->
    val type = record.str("Type") ?: return@mapNotNull null
    if (type !in setOf("Intro", "Outro", "Recap", "Preview")) return@mapNotNull null
    if (record.str("ItemId")?.let { it != itemId } == true) return@mapNotNull null
    val start = record.long("StartTicks") ?: return@mapNotNull null
    val end = record.long("EndTicks") ?: return@mapNotNull null
    if (start < 0 || end <= start || end / 10_000 <= start / 10_000) return@mapNotNull null
    MediaSegment(type, start / 10_000, end / 10_000)
}.distinct().sortedWith(compareBy<MediaSegment> { it.startMs }.thenBy { it.endMs }.thenBy { it.type })

internal enum class NextEpisodeAction { None, Countdown, Immediate }
internal data class SegmentDecision(
    val skip: MediaSegment? = null,
    val autoSeek: MediaSegment? = null,
    val next: NextEpisodeAction = NextEpisodeAction.None,
)

/** Pure segment policy. Ranges include start and exclude end, including an intro starting at zero. */
internal object SegmentPolicy {
    fun evaluate(
        positionMs: Long, durationMs: Long, segments: List<MediaSegment>,
        introMode: String, creditsMode: String, autoplayNext: Boolean, hasNextEpisode: Boolean,
        dismissedNext: Boolean, skipped: Set<MediaSegment>, playing: Boolean,
    ): SegmentDecision {
        fun policy(segment: MediaSegment) = if (segment.type == "Outro") creditsMode else introMode
        val active = segments.filter { positionMs >= it.startMs && positionMs < it.endMs &&
            it.type in setOf("Intro", "Recap", "Outro") && policy(it) != "off" && !(policy(it) == "auto" && it in skipped) }
        // A credits action wins a contradictory overlap; otherwise choose the furthest end so an
        // auto-skip never lands inside a second overlapping opening/recap segment.
        val segment = active.sortedWith(compareByDescending<MediaSegment> { it.type == "Outro" }
            .thenByDescending { it.endMs }.thenBy { it.startMs }.thenBy { it.type }).firstOrNull()
        val nextAllowed = hasNextEpisode && autoplayNext && !dismissedNext && creditsMode != "off"
        if (segment != null) {
            if (policy(segment) == "auto" && playing) {
                if (segment.type == "Outro" && nextAllowed) return SegmentDecision(next = NextEpisodeAction.Immediate)
                return SegmentDecision(autoSeek = segment)
            }
            if (segment.type == "Outro" && nextAllowed) return SegmentDecision(next = NextEpisodeAction.Countdown)
            return SegmentDecision(skip = segment)
        }
        // Missing Outro detection never invents a Skip pill. The ordinary episode end countdown
        // remains available, with the same autoplay/off/dismiss controls.
        if (nextAllowed && segments.none { it.type == "Outro" } && durationMs > 60_000 &&
            durationMs - positionMs in 1..30_000) return SegmentDecision(next = NextEpisodeAction.Countdown)
        return SegmentDecision()
    }
}

/** Countdown consumes playing time only, so pause/rebuffer never changes episodes behind the user. */
internal class NextEpisodeCountdown(seconds: Int) {
    private var remainingMs = seconds.coerceAtLeast(1) * 1000L
    val secondsLeft: Int get() = ((remainingMs + 999) / 1000).toInt()
    val finished: Boolean get() = remainingMs == 0L
    fun advance(elapsedMs: Long, playing: Boolean) {
        if (playing) remainingMs = (remainingMs - elapsedMs.coerceAtLeast(0)).coerceAtLeast(0)
    }
}
