// SPDX-License-Identifier: GPL-3.0-or-later
package com.mav.marquee.offline

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.mav.marquee.ui.nav.LocalNavigator
import com.mav.marquee.ui.nav.MarqueeNavHost
import com.mav.marquee.ui.nav.Navigator
import com.mav.marquee.ui.nav.Route
import com.mav.marquee.ui.settings.Licences
import com.mav.marquee.ui.theme.MarqueeColors
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings reached by D-pad to the end of its list, the open-source licences list and a licence text.
 * Goldens: settings-end, settings-licences, settings-licence-text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-television-xhdpi-notouch", application = OfflineApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsLicencesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var server: OfflineServer

    @Before fun setup() {
        resetSettingsMemory()
        compose.activity.setTheme(com.mav.marquee.R.style.Theme_Marquee)
        compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        compose.activity.window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        server = OfflineServer(compose.activity); server.start()
        compose.runOnIdle { OfflineDeterminism.pinEndTime() }
    }

    @After fun cleanup() {
        server.close()
        // Settings keeps its list position and focus in a process-wide object; do not leak it into other tests.
        resetSettingsMemory()
    }

    private fun resetSettingsMemory() {
        val type = Class.forName("com.mav.marquee.ui.settings.SettingsMemory")
        val memory = type.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        val list = type.getDeclaredMethod("getList").apply { isAccessible = true }.invoke(memory) as androidx.compose.foundation.lazy.LazyListState
        val focus = type.getDeclaredMethod("getFocus").apply { isAccessible = true }.invoke(memory) as com.mav.marquee.ui.library.kit.FocusMemory
        list.requestScrollToItem(0)
        focus.last = null
        focus.pending = null
    }

    private fun showSettings() {
        val nav = Navigator(Route.Settings)
        compose.activity.window.decorView.requestFocusFromTouch()
        compose.setContent {
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
            CompositionLocalProvider(LocalNavigator provides nav) {
                Box(Modifier.fillMaxSize().background(MarqueeColors.Background)) { MarqueeNavHost(nav) }
            }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Offline fixture").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Checking server…").fetchSemanticsNodes().isEmpty()
        }
        compose.onAllNodesWithText("Switch user").onFirst().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
    }

    private fun key(k: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(k) }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        Assert.assertEquals(1920f, bounds.width, 0f); Assert.assertEquals(1080f, bounds.height, 0f)
        compose.onRoot().captureRoboImage(OfflinePaths.golden(name))
    }

    /** More Down presses than Settings has rows: focus stops on the last row. */
    private fun focusLastRow() = key(Key.DirectionDown, 20)

    @Test fun settingsListEnd() {
        showSettings()
        focusLastRow()
        capture("settings-end")
    }

    @Test fun licencesRowOpensListAndTextAndBackReturns() {
        showSettings()
        focusLastRow()
        compose.onNode(isFocused()).assertTextContains("Open-source licences")
        compose.onNodeWithText("free software", substring = true).assertExists()
        compose.onNodeWithText(compose.activity.getString(com.mav.marquee.R.string.source_repository_url), substring = true).assertExists()

        key(Key.DirectionCenter)
        val files = Licences.list(compose.activity.assets)
        Assert.assertTrue(files.isNotEmpty())
        compose.waitUntil(5_000) { compose.onAllNodesWithText(files.first().title).fetchSemanticsNodes().isNotEmpty() }
        files.forEach { compose.onNodeWithText(it.title).assertExists() }
        compose.onNode(isFocused()).assertTextContains(files.first().title)
        capture("settings-licences")

        // D-pad reaches every entry, and stops on the last one.
        for (i in 1..files.lastIndex) {
            key(Key.DirectionDown)
            compose.onNode(isFocused()).assertTextContains(files[i].title)
        }
        key(Key.DirectionDown)
        compose.onNode(isFocused()).assertTextContains(files.last().title)
        key(Key.DirectionUp, files.lastIndex)
        compose.onNode(isFocused()).assertTextContains(files.first().title)

        // GPL text: loaded off the main thread, shown in the kit text sheet with Close focused.
        key(Key.DirectionCenter)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("GNU GENERAL PUBLIC LICENSE", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(isFocused()).assertTextContains("Close")
        capture("settings-licence-text")
        compose.onAllNodesWithText("TERMS AND CONDITIONS", substring = true).assertCountEquals(0)
        // Down scrolls the text a fixed step per press (no animation) until the terms heading is on screen.
        var presses = 0
        while (compose.onAllNodesWithText("TERMS AND CONDITIONS", substring = true).fetchSemanticsNodes().isEmpty()) {
            Assert.assertTrue("terms heading not reached by D-pad scrolling", presses++ < 60)
            key(Key.DirectionDown)
        }
        Assert.assertTrue(presses > 1)
        compose.onNode(isFocused()).assertTextContains("Close")
        compose.onAllNodesWithText("GNU GENERAL PUBLIC LICENSE", substring = true).assertCountEquals(0)

        // Back closes the text and returns to the list on the same entry; Back again returns to the row.
        back()
        compose.onAllNodesWithText("GNU GENERAL PUBLIC LICENSE", substring = true).assertCountEquals(0)
        compose.onNode(isFocused()).assertTextContains(files.first().title)
        back()
        compose.onAllNodesWithText(files.first().title).assertCountEquals(0)
        compose.onNode(isFocused()).assertTextContains("Open-source licences")
    }

    @Test fun largestLicenceTextIsChunked() {
        val assets = compose.activity.assets
        val files = Licences.list(assets)
        Assert.assertTrue(files.any { it.path.endsWith("GPL-3.0.txt") })
        Assert.assertTrue(files.any { it.path.endsWith("THIRD_PARTY_NOTICES.md") })
        for (file in files) {
            val chunks = Licences.read(assets, file)
            Assert.assertTrue(file.path, chunks.isNotEmpty())
            Assert.assertTrue(file.path, chunks.all { it.text.lines().size <= Licences.MAX_CHUNK_LINES })
        }
        // Text is shown verbatim: line breaks are kept, and the long dependency table is split into
        // continuation chunks (no paragraph gap) rather than one huge item.
        val notices = Licences.read(assets, files.first { it.path.endsWith("THIRD_PARTY_NOTICES.md") })
        Assert.assertTrue(notices.any { !it.startsParagraph && it.text.startsWith("|") })
        val gpl = Licences.read(assets, files.first { it.path.endsWith("GPL-3.0.txt") })
        Assert.assertTrue(gpl.any { it.text.contains("Everyone is permitted to copy and distribute verbatim copies\n") })
        val sample = Licences.chunks("a\r\nb\n\n\n" + (1..30).joinToString("\n") { "row $it" })
        Assert.assertEquals(listOf("a\nb", true), listOf(sample[0].text, sample[0].startsParagraph))
        Assert.assertEquals(listOf(true, false, false), sample.drop(1).map { it.startsParagraph })
    }
}
