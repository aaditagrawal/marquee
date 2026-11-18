package com.mav.marquee.ui.home

import android.app.Application
import android.os.SystemClock
import android.view.KeyEvent
import android.view.InputDevice
import android.view.KeyCharacterMap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.mav.marquee.data.HomeSnapshot
import com.mav.marquee.data.MarqueeJson
import com.mav.marquee.data.Session
import com.mav.marquee.offline.OfflineFixtures
import com.mav.marquee.offline.OfflinePaths
import com.mav.marquee.ui.components.CardCaptionLayouts
import com.mav.marquee.ui.components.NavWorkTrace
import com.mav.marquee.ui.nav.LocalNavigator
import com.mav.marquee.ui.nav.Navigator
import com.mav.marquee.ui.nav.Route
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestName
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real Home + Android focus/key dispatch; deterministic JVM clock, never a device or server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-television-xhdpi-notouch-dpad", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = TestName()
    private val snapshot by lazy {
        if (testName.methodName == "largeCachedHomeUpdatesFocusAndContextInOneFrame") OfflineFixtures.largeHome()
        else MarqueeJson.decodeFromString(HomeSnapshot.serializer(), OfflinePaths.resource("home-navigation.json"))
    }
    private val capture = System.getProperty("marquee.navCapture", "verify")
    private val output = OfflinePaths.evidence
    private val counts = Counts()
    private val navigator = Navigator(Route.Home)

    class Counts : NavWorkTrace.Observer {
        var keyWorkNanos = 0L
        override fun keyWork(nanos: Long) { keyWorkNanos += nanos }
        val compositions = linkedMapOf<String, Int>()
        val measures = linkedMapOf<String, Int>()
        val placements = linkedMapOf<String, Int>()
        override fun composed(scope: String) { compositions[scope] = (compositions[scope] ?: 0) + 1 }
        override fun measured(scope: String) { measures[scope] = (measures[scope] ?: 0) + 1 }
        override fun placed(scope: String) { placements[scope] = (placements[scope] ?: 0) + 1 }
        fun reset() { keyWorkNanos = 0L; compositions.clear(); measures.clear(); placements.clear() }
    }

    @Before fun fixture() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Session.init(app)
        Session.logout()
        Session.setServer("") // ensureUser cannot fetch. Fixture image tags are absent; no image requests.
        CardCaptionLayouts.init(app)
        CardCaptionLayouts.setEnabled(System.getProperty("marquee.navCaptionLayouts", "true") == "true")
        HomeController.reset()
        HomeController.setContextArtEnabled(false)
        // Detail prewarming also stays offline, even when the 250ms dwell elapses.
        snapshot.rows.flatMap { it.items }.forEach {
            com.mav.marquee.ui.library.kit.ItemCache.put(it.copy(mediaStreams = emptyList()))
        }
        HomeController.snapshot.value = snapshot
        HomeController.hero.value = snapshot.rows.first().items.first()
        HomeController.restore = snapshot.rows.first().let { it.key to it.items.first().id }
        HomeController.restoreHero = false
        HomeController.initialFocusPending = false
        val cpu = java.lang.management.ManagementFactory.getThreadMXBean()
        assertTrue(cpu.isCurrentThreadCpuTimeSupported)
        if (!cpu.isThreadCpuTimeEnabled) cpu.isThreadCpuTimeEnabled = true
        NavWorkTrace.clock = { cpu.currentThreadCpuTime }
        NavWorkTrace.observer = counts
        compose.setContent {
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
            CompositionLocalProvider(LocalNavigator provides navigator) { com.mav.marquee.ui.nav.MarqueeNavHost(navigator) }
        }
        compose.runOnIdle { compose.activity.window.decorView.dispatchWindowFocusChanged(true) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle()
        val first = snapshot.rows.first()
        compose.onNode(hasAnyAncestor(hasTestTag("home/${first.key}/${first.items.first().id}")) and isFocusable(), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        settle()
        compose.onNode(isFocused(), useUnmergedTree = true).assertExists()
        assertNotNull(HomeController.restore)
    }

    @After fun clean() {
        NavWorkTrace.observer = null
        NavWorkTrace.clock = null
        HomeController.cancelRowScroll()
        HomeController.cancelContextArt()
        CardCaptionLayouts.setEnabled(true)
        compose.mainClock.autoAdvance = true
    }

    private fun settle() {
        repeat(24) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    }

    private fun focused() = HomeController.restore

    private fun dispatch(code: Int, repeat: Int = 0, release: Boolean = true) {
        compose.runOnIdle {
            val now = SystemClock.uptimeMillis()
            fun event(action: Int, repeats: Int) = KeyEvent(now, now, action, code, repeats, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD)
            compose.activity.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN, repeat))
            if (release) compose.activity.dispatchKeyEvent(event(KeyEvent.ACTION_UP, 0))
            // Deliver Android snapshot invalidations before the next controlled frame.
            // Robolectric does not drive GlobalSnapshotManager's Choreographer loop.
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
    }

    @Test fun dpadJourneyBudgets() {
        val events = mutableListOf<JsonObject>()
        // Horizontal, vertical, held-repeat and reversal. Each key-down gets one frame.
        val journey = listOf(
            KeyEvent.KEYCODE_DPAD_RIGHT to 0, KeyEvent.KEYCODE_DPAD_RIGHT to 0,
            KeyEvent.KEYCODE_DPAD_LEFT to 0, KeyEvent.KEYCODE_DPAD_DOWN to 0,
            KeyEvent.KEYCODE_DPAD_RIGHT to 1, KeyEvent.KEYCODE_DPAD_RIGHT to 2,
            KeyEvent.KEYCODE_DPAD_DOWN to 3, KeyEvent.KEYCODE_DPAD_DOWN to 4,
            KeyEvent.KEYCODE_DPAD_UP to 5, KeyEvent.KEYCODE_DPAD_RIGHT to 6)
        journey.forEachIndexed { index, (code, repeat) ->
            if (repeat == 0) settle()
            counts.reset()
            val before = focused()
            dispatch(code, repeat, release = false)
            // Exactly one observed UI frame after key-down, without waiting on a tween.
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            val ticks = 1
            val firstFrameMeasures = counts.measures.values.sum()
            assertEquals("Home row container must never measure during navigation", 0,
                counts.measures["Marquee:HomeContainer:measure"] ?: 0)
            assertEquals("Home row container must never place during navigation", 0,
                counts.placements["Marquee:HomeContainer:measure"] ?: 0)
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                assertEquals("Home rail container must not measure when moving within its retained slots", 0,
                    counts.measures["Marquee:HomeRail:measure"] ?: 0)
                assertEquals("Home rows must not measure on horizontal moves", 0,
                    counts.measures["Marquee:HomeRow:measure"] ?: 0)
            }
            val selected = requireNotNull(focused())
            assertNotEquals("key $index must move focus on key-down", before, selected)
            assertFinalAnchor(selected)
            assertFalse("key $index mainClock must have no running animations", InstantFrameAssertions.hasRunningAnimations(compose))
            val rowIndex = snapshot.rows.indexOfFirst { it.key == selected.first }
            val itemIndex = snapshot.rows[rowIndex].items.indexOfFirst { it.id == selected.second }
            val beforeRow = snapshot.rows.indexOfFirst { it.key == before?.first }
            val beforeItem = snapshot.rows[beforeRow].items.indexOfFirst { it.id == before?.second }
            when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> assertEquals("each repeat moves exactly one card", beforeItem + 1, itemIndex)
                KeyEvent.KEYCODE_DPAD_LEFT -> assertEquals(beforeItem - 1, itemIndex)
                KeyEvent.KEYCODE_DPAD_DOWN -> assertEquals("each repeat moves exactly one row", beforeRow + 1, rowIndex)
                KeyEvent.KEYCODE_DPAD_UP -> assertEquals(beforeRow - 1, rowIndex)
            }
            if (HomeController.collapsed.value) {
                val item = snapshot.rows[rowIndex].items[itemIndex]
                assertEquals(item.id, HomeController.context.value?.id)
                compose.onNode(hasAnyAncestor(hasTestTag("home-context")) and hasText(item.seriesName ?: item.name),
                    useUnmergedTree = true).assertExists()
            }
            compose.onAllNodes(isFocused(), useUnmergedTree = true).assertCountEquals(1)
            compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("home/${selected.first}/${selected.second}")),
                useUnmergedTree = true).assertExists()
            val textTicks = 1
            val keyDownNanos = counts.keyWorkNanos
            // Key-up cannot move focus or trigger a second action.
            compose.runOnIdle { compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)) }
            assertEquals(selected, focused())
            // Extra ticks expose hidden animations or a delayed scroll retarget.
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            assertFinalAnchor(selected)
            assertFalse(InstantFrameAssertions.hasRunningAnimations(compose))
            val event = buildJsonObject {
                put("appKeyWorkNanos", keyDownNanos)
                put("event", index); put("keyCode", code); put("repeat", repeat)
                put("focusTicks", ticks); put("contextTicks", textTicks); put("scrollTicks", 1); put("pendingAnimations", 0)
                put("firstFrameMeasures", firstFrameMeasures)
                put("recompositions", counts.compositions.values.sum())
                put("measures", counts.measures.values.sum()); put("placements", counts.placements.values.sum())
                put("compositionScopes", buildJsonObject { counts.compositions.forEach { (k,v) -> put(k,v) } })
                put("measureScopes", buildJsonObject { counts.measures.forEach { (k,v) -> put(k,v) } })
            }
            events += event
            println("NAV_EVENT $event")
        }
        output.mkdirs()
        val report = buildJsonObject {
            put("fixture", "home-navigation.json"); put("sdk", 30); put("clockFrameMs", 16)
            put("events", JsonArray(events))
        }
        File(output, "nav-$capture.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n")
        if (capture == "before") return
        val budget = Json.parseToJsonElement(OfflinePaths.budget()).jsonObject
        val limits = budget.getValue("events").jsonArray
        events.forEachIndexed { index, event ->
            val nanos = event.getValue("appKeyWorkNanos").jsonPrimitive.long
            assertTrue("event $index Home app focus callback ${nanos / 1_000_000.0}ms exceeds 4ms JVM budget",
                nanos < budget.getValue("mainThreadKeyBudgetMs").jsonPrimitive.long * 1_000_000L)
            for (metric in listOf("focusTicks", "contextTicks", "recompositions", "measures", "placements", "firstFrameMeasures", "scrollTicks", "pendingAnimations")) {
                val actual = event.getValue(metric).jsonPrimitive.int
                val limit = limits[index].jsonObject.getValue(metric).jsonPrimitive.int
                assertTrue("event $index $metric=$actual exceeds $limit", actual <= limit)
            }
        }
    }

    @Test fun largeCachedHomeUpdatesFocusAndContextInOneFrame() {
        // Synthetic cached Home at catalog scale: Continue Watching/Next Up plus every catalog-classified shelf.
        dispatch(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        assertEquals(snapshot.rows[1].key, focused()?.first)
        val selected = requireNotNull(focused())
        val item = snapshot.rows[1].items.first { it.id == selected.second }
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("home/${selected.first}/${selected.second}")),
            useUnmergedTree = true).assertExists()
        compose.onNode(hasAnyAncestor(hasTestTag("home-context")) and hasText(item.seriesName ?: item.name),
            useUnmergedTree = true).assertExists()
        val before = focused()
        dispatch(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 1)
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        assertNotEquals(before, focused())
        assertEquals(focused()?.second, HomeController.context.value?.id)
    }

    @Test fun repeatInterruptsScrollAndBackFocusesFirstRowWithinOneFrame() {
        dispatch(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        val firstDestination = requireNotNull(focused())
        assertEquals(snapshot.rows[1].key, firstDestination.first)
        // Inject the next native repeat with no settling frames between key events.
        dispatch(KeyEvent.KEYCODE_DPAD_DOWN, repeat = 1)
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        assertTrue(HomeController.snapNavigation)
        assertEquals(snapshot.rows[2].key, focused()?.first)
        assertEquals(2, HomeController.column.firstVisibleItemIndex)
        assertEquals(0, HomeController.column.firstVisibleItemScrollOffset)
        assertFalse("held input must snap, not queue an animation", HomeController.column.isScrollInProgress)
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        val first = snapshot.rows.first()
        assertEquals(first.key to first.items.first().id, focused())
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("home/${first.key}/${first.items.first().id}")),
            useUnmergedTree = true).assertExists()
        assertFalse(HomeController.collapsed.value)
        assertEquals(0, HomeController.column.firstVisibleItemIndex)
    }

    @Test fun navHostShowsKnownDestinationOnFirstFrameAndRestoresHomeWithoutTransitions() {
        compose.runOnIdle {
            navigator.push(Route.Login)
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.onNodeWithText("Connect to Jellyfin").assertExists()
        val first = snapshot.rows.first()
        compose.onNodeWithTag("home/${first.key}/${first.items.first().id}").assertDoesNotExist()
        compose.runOnIdle {
            navigator.pop()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.onNodeWithTag("home/${first.key}/${first.items.first().id}").assertExists()
        assertFinalAnchor(requireNotNull(focused()))
        assertFalse(InstantFrameAssertions.hasRunningAnimations(compose))
    }

    private fun assertFinalAnchor(selected: Pair<String, String>) {
        val rowIndex = snapshot.rows.indexOfFirst { it.key == selected.first }
        val itemIndex = snapshot.rows[rowIndex].items.indexOfFirst { it.id == selected.second }
        assertEquals("row anchor must snap in first frame", rowIndex, HomeController.column.firstVisibleItemIndex)
        assertEquals(0, HomeController.column.firstVisibleItemScrollOffset)
        val row = HomeController.rowState(selected.first)
        assertEquals("card anchor must snap in first frame", itemIndex, row.firstVisibleItemIndex)
        assertEquals(0, row.firstVisibleItemScrollOffset)
        assertFalse(HomeController.column.isScrollInProgress)
        assertFalse(row.isScrollInProgress)
    }

    @Test fun retainedRowsCrossWindowReverseAndRestoreBackInOneFrame() {
        fun step(code: Int, row: Int, repeat: Int) {
            counts.reset()
            dispatch(code, repeat)
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            val selected = requireNotNull(focused())
            assertEquals(snapshot.rows[row].key, selected.first)
            assertFinalAnchor(selected)
            compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("home/${selected.first}/${selected.second}")),
                useUnmergedTree = true).assertExists()
            val bounds = compose.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Selected row stays inside the screen", bounds.top >= 0f && bounds.bottom <= 1080f)
            assertEquals(0, counts.measures["Marquee:HomeContainer:measure"] ?: 0)
            assertEquals(0, counts.placements["Marquee:HomeContainer:measure"] ?: 0)
            assertTrue("Retained row dispatch stays under4ms CPU", counts.keyWorkNanos < 4_000_000)
            assertFalse(InstantFrameAssertions.hasRunningAnimations(compose))
        }
        for (row in 1..snapshot.rows.lastIndex) step(KeyEvent.KEYCODE_DPAD_DOWN, row, row)
        for (row in snapshot.rows.lastIndex - 1 downTo 0) step(KeyEvent.KEYCODE_DPAD_UP, row, snapshot.rows.size - row)
        for (row in 1..snapshot.rows.lastIndex) step(KeyEvent.KEYCODE_DPAD_DOWN, row, row)
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        assertFinalAnchor(requireNotNull(focused()))
        assertEquals(snapshot.rows.first().key, focused()?.first)
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("home/${focused()!!.first}/${focused()!!.second}")),
            useUnmergedTree = true).assertExists()
    }

    @Test fun isolatedScrollFinishesInExactlyOneFrameWithNoAnimationWork() {
        dispatch(KeyEvent.KEYCODE_DPAD_DOWN, release = false)
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        assertFinalAnchor(requireNotNull(focused()))
        assertFalse(InstantFrameAssertions.hasRunningAnimations(compose))
    }
}
