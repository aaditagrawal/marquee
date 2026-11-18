package com.mav.marquee.ui.nav

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.mav.marquee.data.Item

/** Every screen in the browse UI. Playback is a separate Activity (see docs/architecture/PLAYER_CONTRACT.md). */
@Immutable
sealed interface Route {
    data object Login : Route
    data object Home : Route
    /** A library view; [collectionType] is movies | tvshows | boxsets | playlists | music | null. */
    data class Library(val viewId: String, val title: String = "", val collectionType: String? = null) : Route
    /** Movie / Series / Season / Episode / BoxSet detail. */
    data class Detail(val itemId: String) : Route
    data class Person(val personId: String) : Route
    data object Search : Route
    data object Settings : Route
}

/**
 * Tiny back stack (no navigation library: fewer classes to load at cold start).
 * Get it with `LocalNavigator.current`. Back is handled by MarqueeNavHost.
 */
@Stable
class Navigator(start: Route) {
    val stack = mutableStateListOf(start)
    val current: Route get() = stack.last()

    fun push(route: Route) { if (stack.lastOrNull() != route) stack.add(route) }
    fun pop(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    /** Replace the whole stack (e.g. after login / logout). */
    fun reset(route: Route) { stack.clear(); stack.add(route) }
    /** Pop back to Home if present, else reset to it. */
    fun home() { while (stack.size > 1 && stack.last() != Route.Home) stack.removeAt(stack.lastIndex); if (stack.last() != Route.Home) reset(Route.Home) }

    /** Convenience: route for opening an item card. Library folders open a Library, everything else Detail. */
    fun open(item: Item) {
        val cached = com.mav.marquee.ui.library.kit.ItemCache.get(item.id)
        if (cached == null || item.mediaSources != null || item.mediaStreams != null)
            com.mav.marquee.ui.library.kit.ItemCache.put(item)
        when (item.type) {
            "CollectionFolder", "UserView" -> push(Route.Library(item.id, item.name, item.collectionType))
            "Person" -> push(Route.Person(item.id))
            else -> push(Route.Detail(item.id))
        }
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }
