package com.mav.marquee.login

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.mav.marquee.data.ImageUrls
import com.mav.marquee.data.Session
import com.mav.marquee.image.MarqueeImages
import com.mav.marquee.ui.components.CardCaptionLayouts
import com.mav.marquee.ui.nav.LocalNavigator
import com.mav.marquee.ui.nav.MarqueeNavHost
import com.mav.marquee.ui.nav.Navigator
import com.mav.marquee.ui.nav.Route
import com.mav.marquee.ui.theme.MarqueeColors
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Network-free startup: no Home preload, no server. */
class LoginRenderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Session.init(this)
        ImageUrls.init(this)
        MarqueeImages.init(this)
        CardCaptionLayouts.init(this)
    }
}

/**
 * Offline render of the first-run login screen at the reference 960x540 dp / xhdpi canvas.
 * Writes docs/media/login-<phase>.png only when -Pmarquee.loginRender=<phase> is passed, so ordinary
 * test runs never touch docs. Synthetic state only: no server is contacted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-television-xhdpi-notouch", application = LoginRenderApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoginRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun setup() {
        compose.activity.setTheme(com.mav.marquee.R.style.Theme_Marquee)
        compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    @Test fun firstRunServerStep() {
        val phase = System.getProperty("marquee.loginRender").orEmpty()
        assumeTrue("render only on request", phase.isNotBlank())
        // The baseline pre-filled a private LAN address on first run; its "before" capture substitutes
        // a synthetic placeholder host so no private address is published.
        if (phase == "before") Session.setServer("http://jellyfin.example:8096")
        val nav = Navigator(Route.Login)
        compose.setContent {
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
            CompositionLocalProvider(LocalNavigator provides nav) {
                Box(Modifier.fillMaxSize().background(MarqueeColors.Background)) { MarqueeNavHost(nav) }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(1920f, bounds.width, 0f)
        assertEquals(1080f, bounds.height, 0f)
        val root = File(System.getProperty("marquee.projectRoot") ?: "..")
        val out = File(root, "docs/media/login-$phase.png").apply { parentFile?.mkdirs() }
        compose.onRoot().captureRoboImage(out.absolutePath, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }
}
