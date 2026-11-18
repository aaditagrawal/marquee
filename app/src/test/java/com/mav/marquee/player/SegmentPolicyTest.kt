package com.mav.marquee.player

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SegmentPolicyTest {
    private val intro = MediaSegment("Intro", 0, 48_048)
    private val outro = MediaSegment("Outro", 100_000, 120_000)
    private fun evaluate(position: Long, ranges: List<MediaSegment> = listOf(intro, outro), introMode: String = "button",
                         creditsMode: String = "button", autoplay: Boolean = true, next: Boolean = true,
                         dismissed: Boolean = false, skipped: Set<MediaSegment> = emptySet(), playing: Boolean = true,
                         duration: Long = 180_000): SegmentDecision =
        SegmentPolicy.evaluate(position, duration, ranges, introMode, creditsMode, autoplay, next, dismissed, skipped, playing)

    @Test fun introStartsAtZeroAndHidesAtItsExclusiveEnd() {
        assertNull(evaluate(-1).skip)
        assertEquals(intro, evaluate(0).skip)
        assertEquals(intro, evaluate(48_047).skip)
        assertNull(evaluate(48_048).skip)
        assertNull(evaluate(50_000).skip)
        assertEquals(intro, evaluate(0, playing = false).skip)
    }
    @Test fun autoAndOffPreferencesApplyWithoutRepeatedSkips() {
        assertEquals(intro, evaluate(0, introMode = "auto").autoSeek)
        assertNull(evaluate(0, introMode = "off").skip)
        assertNull(evaluate(0, introMode = "auto", skipped = setOf(intro)).autoSeek)
        assertNull(evaluate(0, introMode = "auto", skipped = setOf(intro)).skip)
        // An intentional rewind may offer the manual pill again, while auto-skip stays once-only.
        assertEquals(intro, evaluate(0, skipped = setOf(intro)).skip)
        assertNull(evaluate(0, introMode = "auto", playing = false).autoSeek)
        assertEquals(intro, evaluate(0, introMode = "auto", playing = false).skip)
    }
    @Test fun creditsOfferCountdownAndHonorAutoplayOffDismissAndAuto() {
        assertEquals(NextEpisodeAction.Countdown, evaluate(100_000).next)
        assertNull(evaluate(100_000).skip)
        assertEquals(NextEpisodeAction.None, evaluate(120_000).next)
        assertEquals(NextEpisodeAction.Immediate, evaluate(100_000, creditsMode = "auto").next)
        assertEquals(outro, evaluate(100_000, next = false).skip)
        assertEquals(outro, evaluate(100_000, autoplay = false).skip)
        assertEquals(outro, evaluate(100_000, dismissed = true).skip)
        assertEquals(outro, evaluate(100_000, creditsMode = "auto", autoplay = false).autoSeek)
        assertEquals(NextEpisodeAction.None, evaluate(100_000, creditsMode = "off").next)
        assertNull(evaluate(100_000, creditsMode = "off").skip)
    }
    @Test fun overlapsAreDeterministicAndDisabledIntroDoesNotMaskCredits() {
        val short = MediaSegment("Recap", 5_000, 20_000)
        val long = MediaSegment("Intro", 0, 48_048)
        for (ranges in listOf(listOf(short, long), listOf(long, short))) {
            assertEquals(long, evaluate(10_000, ranges, introMode = "auto").autoSeek)
        }
        val contradictory = MediaSegment("Intro", 99_000, 125_000)
        assertEquals(NextEpisodeAction.Countdown, evaluate(100_000, listOf(contradictory, outro), introMode = "off").next)
        assertEquals(NextEpisodeAction.Countdown, evaluate(100_000, listOf(outro, contradictory)).next)
    }
    @Test fun previewAndMissingSegmentsNeverLeaveAStalePill() {
        val preview = MediaSegment("Preview", 48_048, 60_000)
        assertEquals(intro, evaluate(0, listOf(intro, preview)).skip)
        assertNull(evaluate(48_048, listOf(intro, preview)).skip)
        assertNull(evaluate(0, emptyList()).skip)
        assertNull(evaluate(152_000, emptyList()).skip)
        assertEquals(NextEpisodeAction.Countdown, evaluate(152_000, emptyList()).next)
        assertEquals(NextEpisodeAction.None, evaluate(152_000, emptyList(), next = false).next)
        assertEquals(NextEpisodeAction.None, evaluate(152_000, emptyList(), creditsMode = "off").next)
        assertEquals(NextEpisodeAction.None, evaluate(152_000, emptyList(), dismissed = true).next)
    }
    @Test fun countdownUsesOnlyPlayingTimeAndCannotRunBackward() {
        val countdown = NextEpisodeCountdown(10)
        countdown.advance(30_000, playing = false)
        assertEquals(10, countdown.secondsLeft)
        countdown.advance(-1_000, playing = true)
        assertEquals(10, countdown.secondsLeft)
        countdown.advance(1_000, playing = true)
        assertEquals(9, countdown.secondsLeft)
        countdown.advance(8_500, playing = true)
        assertEquals(1, countdown.secondsLeft)
        assertFalse(countdown.finished)
        countdown.advance(500, playing = true)
        assertEquals(0, countdown.secondsLeft)
        assertTrue(countdown.finished)
    }
    @Test fun rejectsForeignInvalidAndSubmillisecondRecordsAndDeduplicates() {
        val records = Json.parseToJsonElement("""[{"ItemId":"ours","Type":"Intro","StartTicks":0,"EndTicks":480480000},
            {"ItemId":"ours","Type":"Intro","StartTicks":0,"EndTicks":480480000},
            {"ItemId":"theirs","Type":"Outro","StartTicks":0,"EndTicks":480480000},
            {"Type":"Intro","StartTicks":-1,"EndTicks":480480000},
            {"Type":"Intro","StartTicks":2,"EndTicks":1},
            {"Type":"Intro","StartTicks":0,"EndTicks":1}, {"Type":"Unknown","StartTicks":0,"EndTicks":480480000}]""").jsonArray
        assertEquals(listOf(intro), parseMediaSegments("ours", records))
    }
    /** Recorded intro/credits timings of an 11-episode season (identity replaced by synthetic IDs). */
    @Test fun validatesEveryRecordedSeriesRangeAndPreference() {
        val episodes = Json.parseToJsonElement(com.mav.marquee.offline.OfflinePaths.resource("series-segments.json")).jsonArray.objs()
        assertEquals(11, episodes.size)
        val nextEpisodes = Json.parseToJsonElement(com.mav.marquee.offline.OfflinePaths.resource("series-next-episodes.json"))
            .jsonArray.objs().associateBy { it.str("itemId") }
        var intros = 0; var outros = 0
        for (episode in episodes) {
            val segments = parseMediaSegments(episode.str("item")!!, episode.arr("segments"))
            val hasNext = nextEpisodes[episode.str("item")]?.str("nextItemId") != null
            for (segment in segments) {
                if (segment.type == "Intro") intros++ else if (segment.type == "Outro") outros++
                val atStart = evaluate(segment.startMs, segments, next = hasNext, duration = episode.long("runtime_ms")!!)
                if (segment.type == "Intro") assertEquals(segment, atStart.skip)
                if (segment.type == "Outro") {
                    assertEquals(if (hasNext) NextEpisodeAction.Countdown else NextEpisodeAction.None, atStart.next)
                    if (!hasNext) assertEquals(segment, atStart.skip)
                }
                assertNull(evaluate(segment.endMs, segments).skip)
                val automatic = evaluate(segment.startMs, segments, introMode = "auto", creditsMode = "auto", next = hasNext)
                if (segment.type == "Intro") assertEquals(segment, automatic.autoSeek)
                else if (hasNext) assertEquals(NextEpisodeAction.Immediate, automatic.next)
                else assertEquals(segment, automatic.autoSeek)
                val off = evaluate(segment.startMs, segments, introMode = "off", creditsMode = "off")
                assertEquals(SegmentDecision(), off)
            }
        }
        assertEquals(11, intros); assertEquals(10, outros)
    }
}
