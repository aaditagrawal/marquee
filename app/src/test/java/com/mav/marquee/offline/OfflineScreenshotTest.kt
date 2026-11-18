package com.mav.marquee.offline

import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.mav.marquee.data.*
import com.mav.marquee.ui.home.*
import com.mav.marquee.ui.detail.DetailModel
import com.mav.marquee.ui.nav.*
import com.mav.marquee.ui.theme.MarqueeColors
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-television-xhdpi-notouch", application = OfflineApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class OfflineScreenshotTest {
    private companion object { const val SEARCH_TERM = "orchard" } // matches the detail series and its episodes
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var server: OfflineServer
    @Before fun setup() {
        compose.activity.setTheme(com.mav.marquee.R.style.Theme_Marquee)
        compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        compose.activity.window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val conf = compose.activity.resources.configuration
        Assert.assertEquals(320, conf.densityDpi)
        Assert.assertEquals(960, conf.screenWidthDp)
        Assert.assertEquals(540, conf.screenHeightDp)
        Assert.assertEquals(Configuration.UI_MODE_TYPE_TELEVISION, conf.uiMode and Configuration.UI_MODE_TYPE_MASK)
        server = OfflineServer(compose.activity); server.start()
        compose.runOnIdle { OfflineDeterminism.pinEndTime() }
    }
    @After fun cleanup() { server.close() }
    private fun show(route: Route) {
        val nav = Navigator(route)
        compose.activity.window.decorView.requestFocusFromTouch()
        compose.setContent {
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
            CompositionLocalProvider(LocalNavigator provides nav) {
                Box(Modifier.fillMaxSize().background(MarqueeColors.Background)) { MarqueeNavHost(nav) }
            }
        }
        compose.waitForIdle()
    }
    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        if (dialog) captureScreenRoboImage(OfflinePaths.golden(name))
        else {
            val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
            Assert.assertEquals(1920f, bounds.width, 0f); Assert.assertEquals(1080f, bounds.height, 0f)
            compose.onRoot().captureRoboImage(OfflinePaths.golden(name))
        }
    }
    private fun homeSeed(): HomeSnapshot {
        val snapshot = OfflineFixtures.home().copy(serverUrl = server.url)
        HomeController.snapshot.value = snapshot
        HomeController.hero.value = snapshot.rows.first().items.first()
        HomeController.heroArtwork.value = HomeController.hero.value
        HomeController.initialFocusPending = false
        HomeController.restoreHero = false
        return snapshot
    }
    private fun request(text: String, substring: Boolean = false) {
        compose.onAllNodesWithText(text, substring = substring).filter(hasSetTextAction().not()).onFirst()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
    }
    @Test fun homeTop() {
        val snapshot = homeSeed()
        // The review case is missing logo art, not the server's small synthetic logo text.
        val hero = requireNotNull(HomeController.hero.value).copy(
            imageTags = HomeController.hero.value?.imageTags?.minus("Logo"),
            parentLogoImageTag = null, parentLogoItemId = null)
        HomeController.hero.value = hero
        HomeController.heroArtwork.value = hero
        OfflineTestState.warm(snapshot.rows.first().items.take(6), snapshot.rows.first().items.first(), listOf(snapshot.rows.first().items.first()))
        HomeController.initialFocusPending = true
        // A cached shell is hydrated without unmounting Home. Retained Android Views
        // must render the replacement snapshot, not their factory's initial pool.
        HomeController.snapshot.value = snapshot.copy(rows = snapshot.rows.map { it.copy(items = emptyList()) })
        show(Route.Home)
        compose.runOnIdle { HomeController.snapshot.value = snapshot }
        compose.waitForIdle()
        // Short single-line fallback title: the hero episode's series name.
        val title = compose.onNodeWithText(requireNotNull(hero.seriesName)).fetchSemanticsNode().boundsInRoot
        val metadata = compose.onNodeWithText("★ 5.2", substring = true).fetchSemanticsNode().boundsInRoot
        Assert.assertEquals("Fallback ends 8dp above metadata", 16f, metadata.top - title.bottom, 1f)
        Assert.assertTrue("Display title is larger than body text", title.height > 60f)
        Assert.assertTrue("Fallback stays below avatar/nav", title.top >= 144f)
        Assert.assertTrue("Fallback fits 520dp", title.width <= 1040f)
        File(OfflinePaths.evidence, "home-snapshot.json").writeText(MarqueeJson.encodeToString(HomeSnapshot.serializer(), snapshot.copy(serverUrl = "")))
        capture("home-top")
        val longTitle = OfflineFixtures.series.name // 28 characters: wraps to two display lines
        compose.runOnIdle {
            HomeController.hero.value = hero.copy(seriesName = longTitle)
            HomeController.heroArtwork.value = HomeController.hero.value
        }
        val wrapped = compose.onNode(hasText(longTitle) and hasAnyAncestor(hasTestTag("home-billboard"))).fetchSemanticsNode().boundsInRoot
        val wrappedMetadata = compose.onNodeWithText("★ 5.2", substring = true).fetchSemanticsNode().boundsInRoot
        Assert.assertTrue("Long fallback uses two display lines", wrapped.height > 100f)
        Assert.assertTrue("Both lines stay below avatar/nav", wrapped.top >= 144f)
        Assert.assertEquals("Wrapping preserves the metadata gap", 16f, wrappedMetadata.top - wrapped.bottom, 1f)
    }
    @Test fun homeCollapsedRow2() {
        val snapshot = homeSeed(); val row = snapshot.rows[1]
        OfflineTestState.warm(row.items.take(6) + snapshot.rows[2].items.take(8))
        HomeController.collapsed.value = true; HomeController.context.value = row.items.first()
        compose.runOnIdle { HomeController.column.requestScrollToItem(1) }
        HomeController.restore = row.key to row.items.first().id
        show(Route.Home)
        capture("home-row2")
    }
    /** Focus on the "Korean Shows & Movies" shelf: invented Hangul titles exercise non-Latin card text. */
    @Test fun homeNonLatinRowFocused() {
        val snapshot = homeSeed(); val index = snapshot.rows.indexOfFirst { it.title.contains("Korean") }; Assert.assertTrue(index > 0)
        val row = snapshot.rows[index]
        OfflineTestState.warm(row.items.take(8) + snapshot.rows[index + 1].items.take(8))
        HomeController.collapsed.value = true; HomeController.context.value = row.items.first()
        compose.runOnIdle { HomeController.column.requestScrollToItem(index) }
        HomeController.restore = row.key to row.items.first().id
        show(Route.Home)
        capture("home-nonlatin-row")
    }
    @Test fun movieDetail() {
        val item = OfflineFixtures.movie; OfflineTestState.detail(item)
        OfflineTestState.warm(emptyList(), item, listOf(item))
        show(Route.Detail(item.id)); request("Play")
        capture("detail-movie")
    }
    @Test fun seriesDetail() {
        // Exercise the no-logo title fallback from the ORCH 15:15 finding.
        val item = OfflineFixtures.series.copy(imageTags = OfflineFixtures.series.imageTags?.minus("Logo"))
        val model = OfflineTestState.detail(item)
        OfflineTestState.warm(model.currentEpisodes.orEmpty(), item)
        show(Route.Detail(item.id)); request("Play S1", substring = true)
        capture("detail-series")
    }
    @Test fun seriesEpisodes() {
        val item = OfflineFixtures.series; val model = OfflineTestState.detail(item)
        OfflineTestState.warm(model.currentEpisodes.orEmpty() + item, backdrop = item)
        show(Route.Detail(item.id))
        compose.runOnIdle { model.list.requestScrollToItem(if (model.seasons.size == 1) 1 else 2) }
        compose.runOnIdle {
            model.focus.focus("ep_${OfflineFixtures.nextEpisode.id}")
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        capture("detail-series-episodes")
    }
    @Test fun seriesMore() {
        val item = OfflineFixtures.series; OfflineTestState.detail(item)
        show(Route.Detail(item.id))
        compose.onNodeWithText("More").performClick()
        capture("detail-series-more", dialog = true)
    }
    @Test fun libraryGrid() {
        val view = OfflineFixtures.home().views.first { it.collectionType == "movies" }
        OfflineTestState.library(view.id)
        OfflineTestState.warm(OfflineFixtures.catalog.filter { it.type == "Movie" }.take(18))
        show(Route.Library(view.id, "Movies", "movies"))
        capture("library-az")
    }
    @Test fun searchResults() {
        OfflineTestState.warm(listOf(OfflineFixtures.series) + OfflineFixtures.episodes.take(6))
        show(Route.Search)
        compose.onNode(hasSetTextAction()).performTextInput(SEARCH_TERM)
        // Read search completion on the UI thread without fetching semantics on each clock tick.
        compose.waitUntil(15_000) {
            compose.runOnUiThread { OfflineDeterminism.searchSettled(SEARCH_TERM) }
        }
        compose.waitForIdle()
        compose.onNodeWithText("TV shows", substring = true).assertExists()
        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionDown) }
        capture("search-results")
    }
    @Test fun settings() {
        show(Route.Settings)
        // Public info can arrive before the paired user request finishes on the IO dispatcher.
        // Capture the settled content state, rather than racing the "Checking server" indicator.
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Offline fixture").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Checking server…").fetchSemanticsNodes().isEmpty()
        }
        // Seed the golden's explicit focus target after asynchronous account hydration.
        request("Switch user")
        capture("settings")
    }
    @Test fun homeSkeleton() {
        server.holdData = true
        show(Route.Home)
        capture("home-skeleton")
    }
    @Test fun homeCachedError() {
        val snapshot = homeSeed(); server.fail = true
        HomeController.error.value = "The server is unavailable. Showing your saved library."
        OfflineTestState.warm(snapshot.rows.first().items.take(6), snapshot.rows.first().items.first(), listOf(snapshot.rows.first().items.first()))
        show(Route.Home); capture("home-cached-error")
    }
    @Test fun detailLoading() {
        val model = DetailModel.get("offline-loading")
        OfflineTestState.field(model, "loaded", true); OfflineTestState.state(model, "loading", true)
        show(Route.Detail("offline-loading")); capture("detail-loading")
    }
    @Test fun detailError() {
        val model = DetailModel.get("offline-error")
        OfflineTestState.field(model, "loaded", true)
        model.error = "The server is unreachable. Check the connection and try again."
        show(Route.Detail("offline-error")); capture("detail-error")
    }
    @Test fun searchLoading() {
        server.holdData = true
        show(Route.Search); compose.onNode(hasSetTextAction()).performTextInput(SEARCH_TERM)
        capture("search-loading")
    }
    @Test fun searchEmpty() {
        show(Route.Search); compose.onNode(hasSetTextAction()).performTextInput("zzzzz")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("No matches", substring = true).fetchSemanticsNodes().isNotEmpty() }
        capture("search-empty")
    }
    @Test fun searchError() {
        server.fail = true
        show(Route.Search); compose.onNode(hasSetTextAction()).performTextInput(SEARCH_TERM)
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Search couldn't reach", substring = true).fetchSemanticsNodes().isNotEmpty() }
        capture("search-error")
    }
    @Test fun librarySkeleton() {
        server.holdData = true
        val view = OfflineFixtures.home().views.first { it.collectionType == "movies" }
        show(Route.Library(view.id, "Movies", "movies")); capture("library-skeleton")
    }
    @Test fun libraryError() {
        val view = OfflineFixtures.home().views.first { it.collectionType == "movies" }
        val model = OfflineTestState.library(view.id)
        model.error = "The server is unreachable. Retry to load your library."
        show(Route.Library(view.id, "Movies", "movies")); capture("library-error")
    }
    @Test fun homeArtworkFailure() {
        val snapshot = homeSeed(); server.failImages = true
        HomeController.heroArtwork.value = null
        show(Route.Home)
        // Requests beginning does not establish completion. Wait for the visible cards'
        // terminal error state before comparing their exact fallback pixels.
        val first = snapshot.rows.first()
        val failed = androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Artwork unavailable")
        compose.waitUntil(10_000) {
            first.items.take(4).all { item ->
                val card = hasTestTag("home/${first.key}/${item.id}")
                compose.onAllNodes(failed and (card or hasAnyAncestor(card)), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        capture("home-artwork-failure")
    }

}
