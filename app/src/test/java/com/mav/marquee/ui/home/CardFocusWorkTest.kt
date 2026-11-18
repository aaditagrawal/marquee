package com.mav.marquee.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mav.marquee.data.Item
import com.mav.marquee.ui.components.CardCaptionLayouts
import com.mav.marquee.ui.components.NavWorkTrace
import com.mav.marquee.ui.components.PosterCard
import com.mav.marquee.ui.components.FocusAnim
import com.mav.marquee.ui.components.focusFill
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-television-xhdpi-notouch-dpad", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardFocusWorkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun cleanup() { NavWorkTrace.observer = null; compose.mainClock.autoAdvance = true }

    @Test fun fixedCardFocusDoesNotScaleRecomposeOrMeasure() {
        val counts = HomeNavigationTest.Counts()
        CardCaptionLayouts.init(compose.activity)
        CardCaptionLayouts.setEnabled(true)
        val cards = listOf(Item("fixed-first", "Driftwood", "Series", productionYear = 2021, childCount = 3),
            Item("fixed-second", "The Paper Lantern Court", "Series", productionYear = 2025, childCount = 1))
        NavWorkTrace.observer = counts
        compose.setContent {
            val input = LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                cards.forEach { PosterCard(it, {}, Modifier.testTag(it.id)) }
            }
        }
        compose.runOnIdle { compose.activity.window.decorView.dispatchWindowFocusChanged(true) }
        compose.onNode(isFocusable() and hasAnyAncestor(hasTestTag(cards[0].id)), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        counts.reset()
        val initialBounds = compose.onNodeWithTag(cards[1].id).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        repeat(12) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag(cards[1].id)), useUnmergedTree = true).assertExists()
        Assert.assertEquals(initialBounds, compose.onNodeWithTag(cards[1].id).fetchSemanticsNode().boundsInRoot)
        Assert.assertEquals("Scale/ring must stay in layer/draw phase", 0, counts.compositions.values.sum())
        Assert.assertEquals("Fixed card dimensions must not be remeasured for focus", 0, counts.measures.values.sum())
        Assert.assertEquals(0, counts.placements.values.sum())
    }

    @Test fun buttonFillAndCueMoveSynchronously() {
        val first = FocusAnim()
        val second = FocusAnim()
        lateinit var scope: CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            val input = LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).testTag("first").focusFill(first, Color.DarkGray, onClick = {}))
                Box(Modifier.size(44.dp).testTag("second").focusFill(second, Color.DarkGray, onClick = {}))
            }
        }
        compose.runOnIdle { compose.activity.window.decorView.dispatchWindowFocusChanged(true) }
        compose.onNodeWithTag("first").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        Assert.assertTrue(second.focused.value)
        Assert.assertFalse(first.focused.value)
        Assert.assertEquals(1f, second.value.value, 0f)
        Assert.assertEquals(0f, first.value.value, 0f)
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.onNode(isFocused() and hasTestTag("second"), useUnmergedTree = true).assertExists()
    }
    @Test fun instantClockAssertionDetectsAnActualAnimation() {
        lateinit var scope: CoroutineScope
        val motion = androidx.compose.animation.core.Animatable(0f)
        compose.setContent { scope = rememberCoroutineScope(); Box(Modifier.size(44.dp)) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { scope.launch { motion.animateTo(1f, androidx.compose.animation.core.tween(160)) } }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        Assert.assertTrue("Negative control: a running tween must trip the clock gate",
            InstantFrameAssertions.hasRunningAnimations(compose))
        repeat(12) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        Assert.assertFalse(InstantFrameAssertions.hasRunningAnimations(compose))
    }

    @Test fun sharedActivationRunsOnKeyDownWithoutAnotherClickOnKeyUp() {
        var clicks = 0
        val state = FocusAnim()
        compose.setContent {
            val input = LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
            Box(Modifier.size(44.dp).testTag("activate").focusFill(state, Color.DarkGray, onClick = { clicks++ }))
        }
        compose.runOnIdle { compose.activity.window.decorView.dispatchWindowFocusChanged(true) }
        compose.onNodeWithTag("activate").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        compose.onNodeWithTag("activate").assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER))
            Assert.assertEquals("Activation cannot await release or an animation frame", 1, clicks)
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER))
            Assert.assertEquals("Release cannot activate twice", 1, clicks)
        }
    }

}
