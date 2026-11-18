package com.mav.marquee.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.mav.marquee.ui.home.HomeScreen

/**
 * Renders the top of [Navigator.stack]. Only one screen is composed at a time (cheap on this TV); Home keeps
 * its scroll/focus position in [HomeController], which outlives the composition.
 * INSTANT: no transition wrapper; known cached data or the destination loading scaffold draws immediately.
 */
@Composable
fun MarqueeNavHost(nav: Navigator) {
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    val route = nav.current
    key(route) {
        when (route) {
            Route.Login -> com.mav.marquee.ui.login.LoginScreen()
            Route.Home -> HomeScreen()
            is Route.Detail -> com.mav.marquee.ui.detail.DetailScreen(route.itemId)
            is Route.Library -> com.mav.marquee.ui.library.LibraryScreen(route.viewId, route.title, route.collectionType)
            is Route.Person -> com.mav.marquee.ui.person.PersonScreen(route.personId)
            Route.Search -> com.mav.marquee.ui.search.SearchScreen()
            Route.Settings -> com.mav.marquee.ui.settings.SettingsScreen()
        }
    }
}
