package com.mav.marquee.player

import org.junit.Assert.*
import org.junit.Test

class SpeculativeSlotTest {
    private val disposed = mutableListOf<String>()
    private val slot = SpeculativeSlot<String> { disposed += it }
    @Test fun dwellAndDoubleFocusStartOnlyOneEncode() {
        val token = slot.focus("a", 0)
        assertNull(slot.start(token, 599, true) { "early" })
        assertEquals(token, slot.focus("a", 400))
        assertEquals("stream", slot.start(token, 600, true) { "stream" })
        assertNull(slot.start(token, 900, true) { "duplicate" })
        assertTrue(disposed.isEmpty())
    }
    @Test fun adoptKeepsExactStreamAcrossLaunchFocusLossAndTtl() {
        val token = slot.focus("a", 0)
        slot.start(token, 600, true) { "same-session" }
        slot.playbackStarting(); slot.blur("a"); slot.cancelUnlessPlaying()
        assertEquals("same-session", slot.adopt("a", 1000))
        slot.expire(60_000)
        assertTrue(disposed.isEmpty())
        val next = slot.focus("b", 70_000)
        assertNull(slot.start(next, 71_000, true) { "during-playback" })
    }
    @Test fun cancelClosesAndDisposesExactlyOnce() {
        val token = slot.focus("a", 0)
        slot.start(token, 600, true) { "a" }
        slot.blur("a"); slot.cancel(); slot.expire(60_000)
        assertEquals(listOf("a"), disposed)
        assertNull(slot.adopt("a", 1000))
    }
    @Test fun ttlIsMeasuredFromEncodeStartAndExpiresAt45Seconds() {
        val token = slot.focus("a", 0)
        slot.start(token, 600, true) { "a" }
        slot.expire(45_599); assertTrue(disposed.isEmpty())
        slot.expire(45_600); assertEquals(listOf("a"), disposed)
        assertNull(slot.adopt("a", 45_600))
    }
    @Test fun rapidNavigationRejectsStaleNegotiationAndStaleBlur() {
        val a = slot.focus("a", 0)
        val b = slot.focus("b", 200)
        slot.blur("a")
        assertNull(slot.start(a, 1000, true) { "stale" })
        assertEquals("b", slot.start(b, 1000, true) { "b" })
        slot.focus("c", 1100)
        assertEquals(listOf("b"), disposed)
        assertNull(slot.adopt("b", 1200))
    }
    @Test fun directPlayAndOtherPlaybackNeverStart() {
        val token = slot.focus("direct", 0)
        assertNull(slot.start(token, 600, false) { "direct" })
        slot.playbackStarting()
        assertNull(slot.start(token, 700, true) { "active" })
        slot.playbackEnded()
        val next = slot.focus("audio", 800)
        assertEquals("new", slot.start(next, 1400, true) { "new" })
    }
    @Test fun mismatchedAccountTracksOrResumeDisposeInsteadOfAdopting() {
        val token = slot.focus("account-a|tracks-0|resume-0", 0)
        slot.start(token, 600, true) { "a" }
        assertNull(slot.adopt("account-b|tracks-1|resume-120000", 1000))
        assertEquals(listOf("a"), disposed)
    }
    @Test fun failedOrDelayedLaunchReservationStillExpires() {
        val token = slot.focus("a", 0)
        slot.start(token, 600, true) { "a" }
        slot.playbackStarting(); slot.blur("a")
        slot.expire(45_600)
        assertEquals(listOf("a"), disposed)
        assertNull(slot.adopt("a", 46_000))
    }
}
