package com.mav.marquee.ui.detail

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mav.marquee.offline.OfflineApplication
import com.mav.marquee.offline.OfflineFixtures
import com.mav.marquee.offline.OfflineServer
import com.mav.marquee.offline.OfflineTestState
import com.mav.marquee.ui.nav.LocalNavigator
import com.mav.marquee.ui.nav.Navigator
import com.mav.marquee.ui.nav.Route
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-television-xhdpi-notouch", application = OfflineApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var server: OfflineServer

    @Before fun setup() {
        compose.activity.setTheme(com.mav.marquee.R.style.Theme_Marquee)
        compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        server = OfflineServer(compose.activity)
        server.start()
    }
    @After fun cleanup() { compose.mainClock.autoAdvance = true; server.close() }

    private fun show(model: DetailModel) {
        val nav = Navigator(Route.Detail(OfflineFixtures.SERIES_ID))
        compose.setContent {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            CompositionLocalProvider(LocalNavigator provides nav) { DetailScreen(OfflineFixtures.SERIES_ID) }
        }
        compose.waitForIdle()
        compose.runOnIdle { model.focus.focus("play") }
    }

    @Test fun singleSeasonDownFocusesNextUpAndUpRestoresHeader() {
        val model = OfflineTestState.detail(OfflineFixtures.series.copy(imageTags = OfflineFixtures.series.imageTags?.minus("Logo")))
        Assert.assertEquals(1, model.seasons.size)
        show(model)
        compose.onNodeWithText("Seasons").assertDoesNotExist()
        compose.onNodeWithText("Season 1").assertDoesNotExist()
        compose.onNodeWithText("Episodes · Season 1").assertExists()
        val title = compose.onNodeWithText(OfflineFixtures.series.name).fetchSemanticsNode().boundsInRoot
        val metadata = compose.onNodeWithText("2026 – Present", substring = true).fetchSemanticsNode().boundsInRoot // header metadata only
        // Metadata is vertically centered beside its rating badge, with a 1px rounding allowance.
        Assert.assertEquals("Title-to-metadata gap is 8dp at xhdpi", 16f, metadata.top - title.bottom, 1f)
        compose.mainClock.autoAdvance = false
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        Assert.assertEquals("ep_${OfflineFixtures.nextEpisode.id}", model.focus.last)
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("detail/episode/${OfflineFixtures.nextEpisode.id}")), useUnmergedTree = true).assertExists()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        Assert.assertEquals("play", model.focus.last)
        Assert.assertEquals(0, model.list.firstVisibleItemIndex)
        Assert.assertEquals(0, model.list.firstVisibleItemScrollOffset)
    }

    @Test fun singleSeasonLoadingTargetHydratesToNextUpWithoutASelector() {
        val model = OfflineTestState.detail(OfflineFixtures.series)
        val season = requireNotNull(model.currentSeason)
        model.episodes.clear()
        show(model)
        compose.mainClock.autoAdvance = false
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        Assert.assertEquals("ep_loading_0", model.focus.last)
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("detail/episode-loading/0")), useUnmergedTree = true).assertExists()
        compose.runOnIdle { model.episodes[season.id] = OfflineFixtures.episodes }
        repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        Assert.assertEquals("ep_${OfflineFixtures.nextEpisode.id}", model.focus.last)
        compose.onAllNodes(isFocused(), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun shortFallbackTitleUsesBottomOfFixedSlot() {
        val item = OfflineFixtures.series.copy(name = "Orchard Society", imageTags = OfflineFixtures.series.imageTags?.minus("Logo"))
        show(OfflineTestState.detail(item))
        val title = compose.onNodeWithText(item.name).fetchSemanticsNode().boundsInRoot
        val metadata = compose.onNodeWithText("2026 – Present", substring = true).fetchSemanticsNode().boundsInRoot // header metadata only
        Assert.assertEquals("One-line title has the same 8dp metadata gap", 16f, metadata.top - title.bottom, 1f)
        Assert.assertEquals("Fixed title slot still ends at 27dp + 96dp", 246f, title.bottom, 0.5f)
    }

    @Test fun initialNextUpStartsAtSafeMarginUnlessRowEndsFirst() {
        val model = OfflineTestState.detail(OfflineFixtures.series)
        model.nextUp = OfflineFixtures.episodes[3]
        show(model)
        val row = model.episodeRow
        compose.runOnIdle {
            Assert.assertEquals(3, row.firstVisibleItemIndex)
            Assert.assertEquals(0, row.firstVisibleItemScrollOffset)
        }
        val card = compose.onNodeWithTag("detail/episode/${model.nextEpisode!!.id}").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals("Middle Next Up is the first card at 48dp", 96f, card.left, 1f)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        Assert.assertEquals("ep_${model.nextEpisode!!.id}", model.focus.last)
    }

    @Test fun terminalNextUpAlignsLastCardToSafeEndMargin() {
        val model = OfflineTestState.detail(OfflineFixtures.series)
        show(model)
        val card = compose.onNodeWithTag("detail/episode/${OfflineFixtures.nextEpisode.id}").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals("Last card ends 48dp before the viewport edge", 1824f, card.right, 1f)
        Assert.assertEquals("The whole Next Up card is visible", 416f, card.width, 1f)
    }
}
