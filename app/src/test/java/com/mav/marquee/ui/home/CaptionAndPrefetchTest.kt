package com.mav.marquee.ui.home

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.unit.Constraints
import com.mav.marquee.data.Item
import com.mav.marquee.ui.components.BoundedNestedPrefetch
import com.mav.marquee.ui.components.CardCaptionLayouts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-television-xhdpi-notouch-dpad", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CaptionAndPrefetchTest {
    @Test fun captionsCacheStringsAndPixelLayoutsButInvalidateForDensity() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        CardCaptionLayouts.init(app)
        val episode = Item("caption-episode", "Episode 11", "Episode", seriesName = "The Midnight Orchard Society",
            parentIndexNumber = 1, indexNumber = 11, runTimeTicks = 34_000_000_000)
        val caption = CardCaptionLayouts.caption(episode, 208f)
        assertEquals("The Midnight Orchard Society  ·  S1 · E11", caption.eyebrow)
        assertSame(caption, CardCaptionLayouts.caption(episode.copy(), 208f))
        val layouts = CardCaptionLayouts.load(caption)
        assertSame(layouts, CardCaptionLayouts.load(caption))
        assertEquals(416, layouts.title.size.width)
        assertNotSame(layouts, CardCaptionLayouts.load(CardCaptionLayouts.caption(episode, 128f)))
        val oldDensity = app.resources.displayMetrics.density
        try {
            app.resources.displayMetrics.density = 1.5f
            CardCaptionLayouts.init(app)
            assertNull(CardCaptionLayouts.cached(caption))
            assertEquals(312, CardCaptionLayouts.load(caption).title.size.width)
        } finally {
            app.resources.displayMetrics.density = oldDensity
            CardCaptionLayouts.init(app)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test fun nestedPrefetchOnlyPrecomposesTwoItemsAndNeverMeasures() {
        val indexes = mutableListOf<Int>()
        // The Kotlin scope is sealed. A JVM proxy observes calls without an implementation
        // of Compose internals or adding a mocking runtime to the production app.
        val scope = java.lang.reflect.Proxy.newProxyInstance(NestedPrefetchScope::class.java.classLoader,
            arrayOf(NestedPrefetchScope::class.java)) { _, method, args ->
            when {
                method.name == "schedulePrecomposition" -> indexes.add(args[0] as Int)
                method.name.startsWith("schedulePrecompositionAndPremeasure") -> fail("Nested prefetch must not measure")
                method.name == "getNestedPrefetchItemCount" -> 2
                else -> error("Unexpected nested-prefetch call ${method.name}")
            }
            null
        } as NestedPrefetchScope
        val strategy = BoundedNestedPrefetch(2)
        with(strategy) { scope.onNestedPrefetch(7) }
        assertEquals(listOf(7, 8), indexes)
    }
}
