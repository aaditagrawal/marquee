package com.mav.marquee.ui.library

import android.view.WindowManager
import android.view.KeyEvent
import android.os.SystemClock
import java.lang.management.ManagementFactory
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mav.marquee.offline.OfflineApplication
import com.mav.marquee.offline.OfflineFixtures
import com.mav.marquee.offline.OfflinePaths
import com.mav.marquee.offline.OfflineServer
import com.mav.marquee.offline.OfflineTestState
import com.mav.marquee.ui.components.CardCaptionLayouts
import com.mav.marquee.ui.components.NavWorkTrace
import com.mav.marquee.ui.detail.DetailModel
import com.mav.marquee.ui.detail.DetailScreen
import com.mav.marquee.ui.home.InstantFrameAssertions
import com.mav.marquee.ui.home.HomeNavigationTest
import com.mav.marquee.ui.nav.LocalNavigator
import com.mav.marquee.ui.nav.Navigator
import com.mav.marquee.ui.nav.Route
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Production grids, cells, focus handlers and Android DPAD dispatch against QA's localhost fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-television-xhdpi-notouch", application = OfflineApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var server: OfflineServer
    private val counts = HomeNavigationTest.Counts()
    private var appKeyCpuNanos = 0L
    private val capture = requireNotNull(System.getProperty("marquee.navCapture", "verify"))
    private val output = OfflinePaths.evidence

    @Before fun setup() {
        compose.activity.setTheme(com.mav.marquee.R.style.Theme_Marquee)
        compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        compose.activity.window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        server = OfflineServer(compose.activity)
        server.start()
        CardCaptionLayouts.setEnabled(capture != "screens-before")
        com.mav.marquee.ui.library.kit.ScreenCaptionPolicy.cached = capture != "screens-before"
        NavWorkTrace.observer = counts
        val clock = ManagementFactory.getThreadMXBean()
        Assert.assertTrue(clock.isCurrentThreadCpuTimeSupported)
        if (!clock.isThreadCpuTimeEnabled) clock.isThreadCpuTimeEnabled = true
        com.mav.marquee.ui.library.kit.ScreenKeyCpuTrace.clock = { clock.currentThreadCpuTime }
        com.mav.marquee.ui.library.kit.ScreenKeyCpuTrace.work = { appKeyCpuNanos += it }
    }
    @After fun cleanup() {
        NavWorkTrace.observer = null
        com.mav.marquee.ui.library.kit.ScreenKeyCpuTrace.clear()
        compose.mainClock.autoAdvance = true
        server.close()
    }
    @androidx.compose.runtime.Composable
    private fun keyboardMode() {
        val mode = androidx.compose.ui.platform.LocalInputModeManager.current
        androidx.compose.runtime.SideEffect { mode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
    }
    private fun settle() { repeat(14) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() } }

    private fun journey(screen: String, keys: List<Key>, scrollPosition: () -> String, scrolling: () -> Boolean, focused: () -> String?) {
        compose.mainClock.autoAdvance = false
        settle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); settle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); settle()
        val events = mutableListOf<JsonObject>()
        keys.forEachIndexed { index, key ->
            // Last four keys simulate held input and direction reversal during scroll.
            if (index < keys.size - 4) settle()
            compose.runOnIdle { counts.reset(); appKeyCpuNanos = 0L }
            val before = focused()
            var dispatchNanos = 0L
            var cpuNanos = 0L
            var keyCpuNanos = 0L
            compose.runOnIdle {
                val bean = ManagementFactory.getThreadMXBean()
                val cpu = bean.currentThreadCpuTime
                val start = System.nanoTime()
                val now = SystemClock.uptimeMillis()
                compose.activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key.nativeKeyCode,
                    if (index >= keys.size - 4) index + 1 else 0))
                dispatchNanos = System.nanoTime() - start
                cpuNanos = bean.currentThreadCpuTime - cpu
                keyCpuNanos = appKeyCpuNanos
                compose.activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key.nativeKeyCode, 0))
            }
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            val ticks = 1
            Assert.assertNotEquals("$screen key $index must move focus on key-down. Tree: " + compose.onRoot(useUnmergedTree = true).printToString(), before, focused())
            compose.onAllNodes(isFocused(), useUnmergedTree = true).assertCountEquals(1)
            if (screen == "library") {
                val expected = listOf(1, 2, 1, 7, 13, 7, 8, 9, 8, 2)[index]
                Assert.assertEquals("Each key and held repeat must move exactly one cell", expected.toString(), focused()?.substringBefore('/'))
            }
            val firstScroll = scrollPosition()
            val pendingAnimations = if (InstantFrameAssertions.hasRunningAnimations(compose)) 1 else 0
            if (!capture.endsWith("before")) {
                Assert.assertFalse("$screen event $index must finish scroll on its first frame", scrolling())
                Assert.assertEquals("$screen event $index mainClock has a running animation", 0, pendingAnimations)
            }
            if (screen == "series") {
                for (container in listOf("Marquee:DetailSections:container", "Marquee:EpisodesRail:container")) {
                    Assert.assertEquals("$container must not measure during in-window moves", 0, counts.measures[container] ?: 0)
                    Assert.assertEquals("$container must not place during in-window moves", 0, counts.placements[container] ?: 0)
                }
            }
            val firstMeasures = counts.measures.values.sum()
            val firstCompositions = counts.compositions.values.sum()
            if (index < keys.size - 4) repeat(11) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            if (!capture.endsWith("before")) Assert.assertEquals("$screen event $index scroll must be final after exactly one frame", firstScroll, scrollPosition())
            events += buildJsonObject {
                put("event", index); put("key", key.toString()); put("focusTicks", ticks)
                put("appKeyWorkNanos", keyCpuNanos)
                put("appKeyWallNanos", counts.keyWorkNanos)
                put("dispatchNanos", dispatchNanos); put("dispatchCpuNanos", cpuNanos)
                put("scrollTicks", 1); put("pendingAnimations", pendingAnimations)
                put("firstFrameScroll", firstScroll); put("settledScroll", scrollPosition())
                put("focused", focused())
                put("firstFrameMeasures", firstMeasures); put("firstFrameRecompositions", firstCompositions)
                put("recompositions", counts.compositions.values.sum()); put("measures", counts.measures.values.sum())
                put("placements", counts.placements.values.sum())
                put("compositionScopes", buildJsonObject { counts.compositions.forEach { (k,v) -> put(k,v) } })
                put("measureScopes", buildJsonObject { counts.measures.forEach { (k,v) -> put(k,v) } })
            }
        }
        output.mkdirs()
        val report = buildJsonObject {
            put("screen", screen); put("fixture", "QA OfflineFixtures/OfflineServer")
            put("sdk", 30); put("densityDpi", 320); put("clockFrameMs", 16)
            put("control", if (capture == "instant-control-before") "old tween/section-focus controller, same shared components and one vertical warmup" else "instant controller, preloaded fixtures, cached captions and one vertical warmup")
            put("timing", "ThreadMXBean CPU nanos for app key-handler bodies and synchronous callbacks; app wall duration and full Android dispatch CPU recorded separately. Not A55 hardware timing.")
            put("events", JsonArray(events))
        }
        File(output, "$screen-$capture.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n")
        if (capture.startsWith("screens-") || capture.startsWith("instant-")) return
        val budget = Json.parseToJsonElement(OfflinePaths.budget()).jsonObject
        val limits = budget.getValue("screens").jsonObject.getValue(screen).jsonArray
        val nanosLimit = (budget.getValue("mainThreadKeyBudgetMs").jsonPrimitive.double * 1_000_000).toLong()
        events.forEachIndexed { index, event ->
            Assert.assertTrue("$screen event $index app key work must stay under $nanosLimit ns", event.getValue("appKeyWorkNanos").jsonPrimitive.long < nanosLimit)
            for (metric in listOf("focusTicks", "scrollTicks", "pendingAnimations", "firstFrameMeasures", "firstFrameRecompositions", "recompositions", "measures", "placements")) {
                val value = event.getValue(metric).jsonPrimitive.int
                val limit = limits[index].jsonObject.getValue(metric).jsonPrimitive.int
                Assert.assertTrue("$screen event $index $metric=$value > $limit", value <= limit)
            }
        }
    }

    @Test fun seasonHighlightDoesNotWaitForRequestDebounce() {
        val model = DetailModel.get(OfflineFixtures.SERIES_ID)
        model.item = OfflineFixtures.series
        val season = OfflineFixtures.result("series-seasons").items.first()
        model.seasons = listOf(season, season.copy(id = "offline-season-2", name = "Season 2"))
        compose.runOnIdle {
            model.selectSeason(1)
            Assert.assertEquals(1, model.seasonIndex)
            Assert.assertEquals("offline-season-2", model.currentSeason?.id)
            Assert.assertNull(model.currentEpisodes)
            Assert.assertFalse(server.requests.any { it.endsWith("/Episodes") })
        }
    }

    @Test fun searchTypingAndDownAreImmediateDuringDebounce() {
        val nav = Navigator(Route.Search)
        compose.setContent { CompositionLocalProvider(LocalNavigator provides nav) {
            keyboardMode(); com.mav.marquee.ui.search.SearchScreen()
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNode(hasSetTextAction()).performTextInput("orchard")
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertTextContains("orchard")
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.onNode(isFocused(), useUnmergedTree = true).assertExists()
        // No query HTTP request has passed its 250ms dwell yet.
        Assert.assertFalse(server.requests.any { it == "/Items" })
    }

    @Test fun nonVisiblePageArrivalDoesNotRemeasureVisibleCells() = invisiblePageArrival(false)

    @Test fun rowGridPageArrivalDoesNotRemeasureVisibleCells() = invisiblePageArrival(true)

    private fun invisiblePageArrival(rowGrid: Boolean) {
        compose.activity.intent.putExtra("rowGrid", rowGrid)
        val model = OfflineTestState.library("offline-movies")
        OfflineTestState.warm(OfflineFixtures.catalog.filter { it.type == "Movie" }.take(36))
        val nav = Navigator(Route.Library("offline-movies", "Movies", "movies"))
        compose.setContent { CompositionLocalProvider(LocalNavigator provides nav) {
            keyboardMode(); LibraryScreen("offline-movies", "Movies", "movies")
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle()
        compose.runOnIdle { counts.reset(); model.pages[9] = OfflineFixtures.catalog.filter { it.type == "Movie" }.take(6) }
        settle()
        val visibleCellMeasures = counts.measures["LibraryCell"] ?: 0
        output.mkdirs()
        File(output, "library-paging.json").writeText(buildJsonObject {
            put("visibleCellMeasures", visibleCellMeasures)
            put("measureScopes", buildJsonObject { counts.measures.forEach { (k,v) -> put(k,v) } })
            put("compositionScopes", buildJsonObject { counts.compositions.forEach { (k,v) -> put(k,v) } })
        }.toString() + "\n")
        Assert.assertEquals("Unchanged visible cells must keep their measured bounds when another page arrives", 0, visibleCellMeasures)
    }

    @Test fun seriesDownDoesNotWaitForEpisodes() {
        val model = OfflineTestState.detail(OfflineFixtures.series)
        val season = model.seasons.first()
        model.seasons = model.seasons + season.copy(id = "offline-loading-season", name = "Season 2")
        model.seasonIndex = model.seasons.lastIndex
        val nav = Navigator(Route.Detail(OfflineFixtures.SERIES_ID))
        compose.setContent { CompositionLocalProvider(LocalNavigator provides nav) {
            keyboardMode(); DetailScreen(OfflineFixtures.SERIES_ID)
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle()
        compose.runOnIdle { model.focus.focus("season_offline-loading-season") }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        Assert.assertEquals("ep_loading_0", model.focus.last)
        Assert.assertFalse("Repeated section handoff snaps the ongoing scroll", model.list.isScrollInProgress)
        Assert.assertNull(model.currentEpisodes)
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("detail/episode-loading/0")), useUnmergedTree = true).assertExists()
    }

    @Test fun libraryFocusDoesNotWaitForDataOrJumpBackAfterHydration() {
        server.holdData = true
        val model = LibraryModel.get("offline-cold", "movies")
        val nav = Navigator(Route.Library("offline-cold", "Movies", "movies"))
        compose.setContent { CompositionLocalProvider(LocalNavigator provides nav) {
            keyboardMode(); LibraryScreen("offline-cold", "Movies", "movies")
        } }
        compose.waitForIdle()
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("library/0")), useUnmergedTree = true).assertExists()
        Assert.assertEquals(-1, model.total)
        compose.mainClock.autoAdvance = false
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("library/1")), useUnmergedTree = true).assertExists()
        val movies = OfflineFixtures.catalog.filter { it.type == "Movie" }
        compose.runOnIdle { model.pages[0] = movies.take(100); model.total = movies.size }
        settle()
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("library/1")), useUnmergedTree = true).assertExists()
        Assert.assertEquals(movies[1].id, model.focused?.id)
        compose.runOnIdle { model.pages.clear(); model.total = -1 }
        settle()
        // Move away before metadata arrives; hydration must not steal the control's focus.
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.onNode(isFocused()).assertTextContains("Sort: Name ↑")
        compose.runOnIdle {
            model.pages[0] = movies.take(100); model.total = movies.size
        }
        settle()
        compose.onNode(isFocused()).assertTextContains("Sort: Name ↑")
    }

    private fun instantKey(key: Key, repeat: Int = 0) {
        compose.runOnIdle {
            val now = SystemClock.uptimeMillis()
            compose.activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key.nativeKeyCode, repeat))
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        Assert.assertFalse("No animation may remain after the key's first frame", InstantFrameAssertions.hasRunningAnimations(compose))
        compose.onAllNodes(isFocused(), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun libraryFilterSheetMovesOncePerHeldRepeat() = libraryFilterJourney(false)

    @Test fun rowGridFilterSheetMovesOncePerHeldRepeat() = libraryFilterJourney(true)

    private fun libraryFilterJourney(rowGrid: Boolean) {
        compose.activity.intent.putExtra("rowGrid", rowGrid)
        val model = OfflineTestState.library("offline-filter")
        val nav = Navigator(Route.Library("offline-filter", "Movies", "movies"))
        compose.setContent { CompositionLocalProvider(LocalNavigator provides nav) {
            keyboardMode(); LibraryScreen("offline-filter", "Movies", "movies")
        } }
        compose.waitForIdle()
        compose.onNodeWithText("Sort: Name ↑").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.mainClock.autoAdvance = false
        instantKey(Key.DirectionCenter)
        compose.onNode(isFocused()).assertTextContains(LibSort.entries.first().label)
        LibSort.entries.drop(1).forEachIndexed { i, sort ->
            instantKey(Key.DirectionDown, i + 1)
            compose.onNode(isFocused()).assertTextContains(sort.label)
        }
        instantKey(Key.DirectionDown, 9)
        compose.onNode(isFocused()).assertTextContains("Unwatched only")
        instantKey(Key.DirectionDown, 10)
        compose.onNode(isFocused()).assertTextContains("Favorites only")
    }

    @Test fun settingsListAndPickerRepeatAndDismissInOneFrame() {
        val nav = Navigator(Route.Settings)
        compose.setContent { CompositionLocalProvider(LocalNavigator provides nav) {
            keyboardMode(); com.mav.marquee.ui.settings.SettingsScreen()
        } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Checking server…").fetchSemanticsNodes().isEmpty() }
        compose.mainClock.autoAdvance = false
        settle()
        compose.onNode(isFocused()).assertTextContains("Switch user")
        instantKey(Key.DirectionDown)
        compose.onNode(isFocused()).assertTextContains("Maximum bitrate")
        instantKey(Key.DirectionCenter)
        compose.onNode(isFocused()).assertTextContains("Auto")
        for (i in 1..9) {
            instantKey(Key.DirectionDown, i)
            compose.onNode(isFocused()).assertTextContains(listOf("120", "80", "60", "40", "20", "15", "10", "8", "4")[i - 1] + " Mbps")
        }
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.onNode(isFocused()).assertTextContains("Maximum bitrate")
        Assert.assertFalse(InstantFrameAssertions.hasRunningAnimations(compose))
        for (label in listOf("Audio language", "Subtitle language", "Subtitles", "Skip intro", "Skip credits",
            "Autoplay next episode", "Playback diagnostics", "Image quality", "Clear image cache", "About Marquee", "Open-source licences")) {
            instantKey(Key.DirectionDown, 1)
            compose.onNode(isFocused()).assertTextContains(label)
        }
    }

    @Test fun searchResultRowsFinishEveryRepeatInOneFrame() {
        OfflineTestState.warm(listOf(OfflineFixtures.series) + OfflineFixtures.episodes.take(6))
        val nav = Navigator(Route.Search)
        compose.setContent { CompositionLocalProvider(LocalNavigator provides nav) {
            keyboardMode(); com.mav.marquee.ui.search.SearchScreen()
        } }
        compose.onNode(hasSetTextAction()).performTextInput("orchard")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("TV shows", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.autoAdvance = false
        instantKey(Key.DirectionDown)
        instantKey(Key.DirectionDown)
        val first = compose.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode().id
        instantKey(Key.DirectionRight, 1)
        val second = compose.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode().id
        Assert.assertNotEquals(first, second)
        instantKey(Key.DirectionRight, 2)
        Assert.assertNotEquals(second, compose.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode().id)
        instantKey(Key.DirectionLeft, 3)
        Assert.assertEquals(second, compose.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode().id)
    }

    @Test fun moreDialogOpensReadsAndClosesInOneFrame() {
        val model = OfflineTestState.detail(OfflineFixtures.series)
        model.item = OfflineFixtures.series.copy(overview = OfflineFixtures.series.overview.orEmpty().repeat(12))
        val nav = Navigator(Route.Detail(OfflineFixtures.SERIES_ID))
        compose.setContent { CompositionLocalProvider(LocalNavigator provides nav) {
            keyboardMode(); DetailScreen(OfflineFixtures.SERIES_ID)
        } }
        compose.waitForIdle()
        compose.runOnIdle { model.focus.focus("more") }
        compose.waitForIdle()
        compose.onNode(isFocused()).assertTextContains("More")
        compose.mainClock.autoAdvance = false
        instantKey(Key.DirectionCenter)
        compose.onNodeWithText("Close").assertExists()
        // Compose dialogs use their own window key dispatcher.
        compose.onNodeWithText("Close").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        val list = compose.onNodeWithTag("synopsis-overview")
        fun position() = list.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        repeat(5) {
            val before = position()
            compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
            val afterKey = position()
            Assert.assertTrue("Reading scroll updates during key dispatch", afterKey > before)
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            Assert.assertEquals(afterKey, position())
            Assert.assertFalse(InstantFrameAssertions.hasRunningAnimations(compose))
        }
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.onAllNodesWithText("← Synopsis   ·   Audio & subtitles →   ·   Back to close").assertCountEquals(0)
        Assert.assertFalse(InstantFrameAssertions.hasRunningAnimations(compose))
    }

    @Test fun libraryGridDpadBudget() = libraryGridJourney(false)

    @Test fun rowGridDpadBudget() = libraryGridJourney(true)

    private fun libraryGridJourney(rowGrid: Boolean) {
        compose.activity.intent.putExtra("rowGrid", rowGrid)
        val model = OfflineTestState.library("offline-movies")
        OfflineTestState.warm(OfflineFixtures.catalog.filter { it.type == "Movie" }.take(36))
        val nav = Navigator(Route.Library("offline-movies", "Movies", "movies"))
        compose.setContent { CompositionLocalProvider(LocalNavigator provides nav) { keyboardMode(); LibraryScreen("offline-movies", "Movies", "movies") } }
        compose.waitUntil(20_000) { model.total > 12 && model.itemAt(0) != null }
        compose.waitForIdle()
        compose.onNode(hasAnyAncestor(hasTestTag("library/0")) and isFocusable(), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        journey("library", listOf(Key.DirectionRight, Key.DirectionRight, Key.DirectionLeft,
            Key.DirectionDown, Key.DirectionDown, Key.DirectionUp, Key.DirectionRight, Key.DirectionRight,
            Key.DirectionLeft, Key.DirectionUp), { "${model.scrollIndex}/${model.scrollOffset}" }, { false }) { model.focused?.let { "${model.lastFocusedIndex}/${it.id}" } }
    }

    @Test fun rowGridCrossesPoolBoundaryAndReversesInOneFrame() {
        compose.activity.intent.putExtra("rowGrid", true)
        val model = OfflineTestState.library("offline-movies")
        OfflineTestState.warm(OfflineFixtures.catalog.filter { it.type == "Movie" }.take(60))
        val nav = Navigator(Route.Library("offline-movies", "Movies", "movies"))
        compose.setContent { CompositionLocalProvider(LocalNavigator provides nav) {
            keyboardMode(); LibraryScreen("offline-movies", "Movies", "movies")
        } }
        compose.waitUntil(20_000) { model.total > 54 && model.itemAt(48) != null }
        compose.onNode(hasAnyAncestor(hasTestTag("library/0")) and isFocusable(), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        for (step in 1..8) {
            compose.runOnIdle { counts.reset(); appKeyCpuNanos = 0 }
            instantKey(Key.DirectionDown, step)
            Assert.assertEquals(step * 6, model.lastFocusedIndex)
            compose.onNode(hasAnyAncestor(hasTestTag("library/${step * 6}")) and isFocused(), useUnmergedTree = true).assertExists()
            Assert.assertTrue("Pooled key work remains under4ms", appKeyCpuNanos < 4_000_000)
            val bounds = compose.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            Assert.assertTrue("Focused image fits the TV", bounds.top >= 0f && bounds.bottom <= 1080f)
        }
        for (step in 7 downTo 0) {
            compose.runOnIdle { counts.reset(); appKeyCpuNanos = 0 }
            instantKey(Key.DirectionUp, 8 - step)
            Assert.assertEquals(step * 6, model.lastFocusedIndex)
            compose.onNode(hasAnyAncestor(hasTestTag("library/${step * 6}")) and isFocused(), useUnmergedTree = true).assertExists()
            Assert.assertTrue("Reverse key work remains under4ms", appKeyCpuNanos < 4_000_000)
        }
    }

    @Test fun detailSectionsCrossRetainedWindowAndReverseInOneFrame() {
        val model = OfflineTestState.detail(OfflineFixtures.series)
        model.children = OfflineFixtures.episodes.take(1)
        model.extras = listOf(OfflineFixtures.movie)
        val nav = Navigator(Route.Detail(OfflineFixtures.SERIES_ID))
        compose.setContent { CompositionLocalProvider(LocalNavigator provides nav) {
            keyboardMode(); DetailScreen(OfflineFixtures.SERIES_ID)
        } }
        compose.waitForIdle()
        compose.runOnIdle { model.focus.focus("play") }
        compose.mainClock.autoAdvance = false
        val forward = listOf("ep_", "child_", "cast_", "sim_", "extra_")
        for ((i, prefix) in forward.withIndex()) {
            instantKey(Key.DirectionDown, i + 1)
            Assert.assertTrue("New section must focus in one frame: ${model.focus.last}", model.focus.last?.startsWith(prefix) == true)
            compose.onAllNodes(isFocused(), useUnmergedTree = true).assertCountEquals(1)
            Assert.assertFalse(InstantFrameAssertions.hasRunningAnimations(compose))
        }
        for ((i, prefix) in listOf("sim_", "cast_", "child_", "ep_", "play").withIndex()) {
            instantKey(Key.DirectionUp, i + 1)
            Assert.assertTrue("Reverse section must focus in one frame: ${model.focus.last}", model.focus.last?.startsWith(prefix) == true)
            compose.onAllNodes(isFocused(), useUnmergedTree = true).assertCountEquals(1)
            Assert.assertFalse(InstantFrameAssertions.hasRunningAnimations(compose))
        }
    }

    @Test fun seriesDetailDpadBudget() {
        val model = OfflineTestState.detail(OfflineFixtures.series)
        // Keep this existing budget journey on the two-section, multi-season layout.
        model.seasons = model.seasons + model.seasons.first().copy(id = "offline-season-2", name = "Season 2")
        OfflineTestState.warm(OfflineFixtures.episodes + model.similar, backdrop = OfflineFixtures.series, logos = listOf(OfflineFixtures.series))
        val nav = Navigator(Route.Detail(OfflineFixtures.SERIES_ID))
        compose.setContent { CompositionLocalProvider(LocalNavigator provides nav) { keyboardMode(); DetailScreen(OfflineFixtures.SERIES_ID) } }
        compose.waitUntil(20_000) { model.currentEpisodes?.isNotEmpty() == true && model.nextUp != null }
        compose.runOnIdle { model.focus.focus("play") }
        journey("series", listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionLeft,
            Key.DirectionRight, Key.DirectionDown, Key.DirectionRight, Key.DirectionLeft,
            Key.DirectionUp, Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft,
            Key.DirectionRight, Key.DirectionUp), { "${model.list.firstVisibleItemIndex}/${model.list.firstVisibleItemScrollOffset};${model.episodeRow.firstVisibleItemIndex}/${model.episodeRow.firstVisibleItemScrollOffset};${model.seasonRow.firstVisibleItemIndex}/${model.seasonRow.firstVisibleItemScrollOffset}" }, { model.list.isScrollInProgress || model.episodeRow.isScrollInProgress || model.seasonRow.isScrollInProgress }) { model.focus.last }
    }
}
