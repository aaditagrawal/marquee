package com.mav.marquee.image

import com.mav.marquee.data.ImageUrls
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Image budgets and request sizes derived from the device, unchanged on the reference TV. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DeviceSizingTest {
    private val mib = 1024 * 1024

    @Test fun memoryBudgetKeepsReferenceValue() {
        assertEquals(40 * mib, MarqueeImages.memoryBudget(192, lowRam = false))
        assertEquals(40 * mib, MarqueeImages.memoryBudget(512, lowRam = false))
        assertEquals(20 * mib, MarqueeImages.memoryBudget(96, lowRam = false))
        assertEquals(10 * mib, MarqueeImages.memoryBudget(96, lowRam = true))
        assertEquals(8 * mib, MarqueeImages.memoryBudget(32, lowRam = true))
        assertEquals(40 * mib, MarqueeImages.memoryBudget(0, lowRam = false))
    }

    @Test fun imageScaleFollowsDensityUpToReference() {
        assertEquals(1f, ImageUrls.scaleFor(2f), 0f)      // 1080p at 320 dpi (reference)
        assertEquals(1f, ImageUrls.scaleFor(4f), 0f)      // 4K UI at 640 dpi: capped
        assertEquals(0.665f, ImageUrls.scaleFor(1.33f), 0.001f) // 720p at tvdpi
        assertEquals(0.5f, ImageUrls.scaleFor(1f), 0f)
    }
}
