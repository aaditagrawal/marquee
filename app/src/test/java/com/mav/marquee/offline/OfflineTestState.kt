package com.mav.marquee.offline

import androidx.compose.runtime.MutableState
import com.mav.marquee.data.*
import com.mav.marquee.ui.detail.DetailModel
import com.mav.marquee.ui.library.LibraryModel
import com.mav.marquee.image.MarqueeImages
import com.mav.marquee.image.HeroArt
import com.mav.marquee.ui.components.CardPx
import kotlinx.coroutines.runBlocking

/** Test-only state preparation. Production owners do not need preview branches in their UI. */
internal object OfflineTestState {
    fun field(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
    @Suppress("UNCHECKED_CAST")
    fun state(target: Any, name: String, value: Any?) {
        val state = target.javaClass.getDeclaredField(name + "\$delegate").apply { isAccessible = true }.get(target) as MutableState<Any?>
        state.value = value
    }
    fun detail(item: Item): DetailModel {
        val model = DetailModel.get(item.id)
        model.item = item
        field(model, "loaded", true)
        if (item.type == "Series") {
            model.seasons = OfflineFixtures.result("series-seasons").items
            model.nextUp = OfflineFixtures.nextEpisode
            model.seasonIndex = model.seasons.indexOfFirst { it.id == model.nextUp?.seasonId }.coerceAtLeast(0)
            model.episodes[model.currentSeason!!.id] = OfflineFixtures.episodes
            model.synopsisSource = OfflineFixtures.nextEpisode.mediaSources?.firstOrNull()
        }
        model.similar = OfflineFixtures.catalog.filter { it.type == item.type && it.id != item.id }.take(8)
        return model
    }
    fun library(viewId: String): LibraryModel {
        val model = LibraryModel.get(viewId, "movies")
        val items = OfflineFixtures.catalog.filter { it.type == "Movie" }
        model.total = items.size
        items.chunked(100).forEachIndexed { i, page -> model.pages[i] = page }
        model.signature = model.filters.signature()
        return model
    }
    /** Decode local placeholder images before capture; identical production Coil/scrim code. */
    fun warm(items: List<Item>, backdrop: Item? = null, logos: List<Item> = emptyList()) = runBlocking {
        items.distinctBy { it.id }.forEach { item ->
            val card = if (item.type == "Episode") ImageUrls.landscape(item, CardPx.LandW, CardPx.LandH) else ImageUrls.poster(item, CardPx.PosterW, CardPx.PosterH)
            if (card != null) requireNotNull(MarqueeImages.load(card))
            ImageUrls.landscape(item, CardPx.LandW, CardPx.LandH)?.let { requireNotNull(MarqueeImages.load(it)) }
        }
        items.flatMap { it.people.orEmpty() }.distinctBy { it.id }.take(10).forEach { person ->
            ImageUrls.person(person.id, person.primaryImageTag)?.let { requireNotNull(MarqueeImages.load(it)) }
        }
        logos.forEach { item -> ImageUrls.logo(item, CardPx.LogoW, CardPx.LogoH)?.let { requireNotNull(MarqueeImages.load(it, opaque = false)) } }
        backdrop?.let { item -> ImageUrls.backdrop(item, CardPx.BackdropW, CardPx.BackdropH)?.let { requireNotNull(HeroArt.load(it)) } }
    }
}
