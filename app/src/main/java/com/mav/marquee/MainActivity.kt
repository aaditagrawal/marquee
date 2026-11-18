package com.mav.marquee

import android.os.Bundle
import android.content.Intent
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.mav.marquee.data.Session
import com.mav.marquee.image.MarqueeImages
import com.mav.marquee.ui.home.HomeController
import com.mav.marquee.ui.home.HomePerf
import com.mav.marquee.ui.components.CardCaptionLayouts
import com.mav.marquee.ui.nav.LocalNavigator
import com.mav.marquee.ui.nav.MarqueeNavHost
import com.mav.marquee.ui.nav.Navigator
import com.mav.marquee.ui.nav.Route

class MainActivity : ComponentActivity() {
    private val drawListener = ViewTreeObserver.OnDrawListener { HomePerf.windowDrawn() }
    private lateinit var navigator: Navigator
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MarqueeImages.setHardwareBitmaps(intent.getBooleanExtra("hardwareBitmaps", false))
        CardCaptionLayouts.setEnabled(intent.getBooleanExtra("captionLayouts", true))
        HomeController.setNestedPrefetchCount(intent.getIntExtra("nestedPrefetch", 2))
        HomeController.horizontalParentAnchor = intent.getBooleanExtra("parentAnchor", false)
        HomeController.setLazyPrefetchEnabled(intent.getBooleanExtra("lazyPrefetch", true))
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        HomePerf.begin(warm = HomePerf.hasStarted)
        HomePerf.onReady = { runCatching { reportFullyDrawn() } }
        window.decorView.viewTreeObserver.addOnDrawListener(drawListener)
        HomeController.setContextArtEnabled(intent.getBooleanExtra("contextBackdrop", true))
        navigator = Navigator(if (Session.isLoggedIn) Route.Home else Route.Login)
        openDebugDetail(intent)
        setContent {
            val nav = navigator
            CompositionLocalProvider(LocalNavigator provides nav) {
                Box(Modifier.fillMaxSize()) { MarqueeNavHost(nav) }
            }
        }
        // Every screen paints its own opaque background; skip the window's extra full-screen fill.
        window.setBackgroundDrawable(null)
    }

    // TV remotes deliver Back as a key event (no predictive back gesture); it is routed through the
    // OnBackPressedDispatcher. RestrictedApi is a lint false positive on overriding the public
    // Activity.dispatchKeyEvent through androidx's ComponentActivity.
    @android.annotation.SuppressLint("RestrictedApi", "GestureBackNavigation")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra("contextBackdrop")) HomeController.setContextArtEnabled(intent.getBooleanExtra("contextBackdrop", true))
        openDebugDetail(intent)
    }

    private fun openDebugDetail(intent: Intent) {
        if (BuildConfig.DEBUG && Session.isLoggedIn && intent.getBooleanExtra("home", false)) navigator.home()
        if (BuildConfig.DEBUG) {
            val route = when (intent.getStringExtra("debugScreen")) {
                "search" -> Route.Search
                "settings" -> Route.Settings
                "login" -> Route.Login
                "person" -> intent.getStringExtra("personId")?.let(Route::Person)
                "library" -> intent.getStringExtra("viewId")?.let {
                    Route.Library(it, intent.getStringExtra("title").orEmpty(), intent.getStringExtra("collectionType"))
                }
                else -> null
            }
            route?.let { navigator.home(); navigator.push(it) }
        }
        if (BuildConfig.DEBUG && Session.isLoggedIn) intent.getStringExtra("detailItemId")?.let {
            navigator.home()
            navigator.push(Route.Detail(it))
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the player (or the app was backgrounded): revalidate Continue Watching / Next Up.
        HomeController.refresh(this, minAgeMs = 15_000)
    }

    override fun onRestart() {
        super.onRestart()
        HomePerf.begin(warm = true)
        window.decorView.postInvalidate()
    }

    override fun onDestroy() {
        window.decorView.viewTreeObserver.removeOnDrawListener(drawListener)
        HomePerf.onReady = null
        super.onDestroy()
    }
}
