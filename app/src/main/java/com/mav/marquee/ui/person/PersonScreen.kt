package com.mav.marquee.ui.person

import com.mav.marquee.ui.library.kit.instantDpad
import com.mav.marquee.ui.library.kit.onInstantPreviewKeyEvent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mav.marquee.data.ImageUrls
import com.mav.marquee.data.Item
import com.mav.marquee.data.JellyfinRepository
import com.mav.marquee.data.Session
import com.mav.marquee.ui.library.kit.FocusMemory
import com.mav.marquee.ui.library.kit.KText
import com.mav.marquee.ui.library.kit.NetImage
import com.mav.marquee.ui.library.kit.PivotSpec
import com.mav.marquee.ui.library.kit.PosterCard
import com.mav.marquee.ui.library.kit.SkeletonBox
import com.mav.marquee.ui.library.kit.TvChip
import com.mav.marquee.ui.library.kit.initials
import com.mav.marquee.ui.library.kit.px
import com.mav.marquee.ui.library.kit.restorable
import com.mav.marquee.ui.nav.LocalNavigator
import com.mav.marquee.ui.theme.MarqueeColors
import com.mav.marquee.ui.theme.MarqueeDims
import com.mav.marquee.ui.theme.MarqueeType
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@Stable
internal class PersonModel(val id: String) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var person by mutableStateOf<Item?>(com.mav.marquee.ui.library.kit.ItemCache.get(id))
    var credits by mutableStateOf<List<Item>?>(null)
    var error by mutableStateOf<String?>(null)
    var bioExpanded by mutableStateOf(false)
    var headerFocused = true
    val grid = LazyGridState()
    val focus = FocusMemory()
    private var loaded = false
    var loading by mutableStateOf(false)
        private set
    var testPolicy: com.mav.marquee.ui.library.kit.ScreenTestPolicy? = null

    fun load(force: Boolean = false) {
        if (loaded && !force) return
        loaded = true; error = null; loading = true
        if (credits?.isEmpty() == true) credits = null
        scope.launch {
            try {
                testPolicy?.before("person")
                kotlinx.coroutines.coroutineScope {
                    val p = async { JellyfinRepository.person(id) }
                    val c = async { JellyfinRepository.itemsWithPerson(id, 200) }
                    person = p.await().also(com.mav.marquee.ui.library.kit.ItemCache::put); credits = c.await()
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { error = "Couldn't load this person. Check the connection and try again."; if (credits == null) credits = emptyList() }
            finally { loading = false }
        }
    }

    companion object {
        private val cache = LinkedHashMap<String, PersonModel>()
        fun clear() { cache.values.forEach { it.scope.cancel() }; cache.clear() }
        fun get(id: String) = cache.getOrPut(Session.userId + ":" + id) { PersonModel(id) }.also {
            while (cache.size > 6) cache.remove(cache.keys.first())?.scope?.cancel()
        }
    }
}

/** Person page: photo, name, birth info, biography (expandable), filmography grid (newest first). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonScreen(personId: String) {
    val nav = LocalNavigator.current
    val model = remember(personId) { PersonModel.get(personId) }
    val context = androidx.compose.ui.platform.LocalContext.current
    if (model.testPolicy == null) model.testPolicy = com.mav.marquee.ui.library.kit.ScreenTestPolicy.from(context)
    LaunchedEffect(model) { model.load() }
    if (com.mav.marquee.BuildConfig.DEBUG) com.mav.marquee.ui.library.kit.ScreenStateMarker("person", when {
        model.loading -> if (model.person == null) "skeleton" else "seeded"
        model.error != null -> "error"
        model.credits?.isEmpty() == true -> "empty"
        else -> "content"
    })
    val density = LocalDensity.current
    val scroll = remember(model) { com.mav.marquee.ui.library.kit.ScreenScroll() }
    val scope = rememberCoroutineScope()
    val spec = remember {
        object : BringIntoViewSpec { override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f }
    }

    Box(Modifier.fillMaxSize().instantDpad().background(MarqueeColors.Background)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
            LazyVerticalGrid(
                columns = GridCells.FixedSize(MarqueeDims.PosterWidth),
                state = model.grid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = MarqueeDims.SafeH, end = MarqueeDims.SafeH, top = MarqueeDims.SafeV, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(MarqueeDims.CardGap),
                verticalArrangement = Arrangement.spacedBy(MarqueeDims.RowGap),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header", contentType = "header") { Header(model, onBack = { nav.pop() }) }
                if (model.error != null && !model.credits.isNullOrEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "offline", contentType = "message") {
                    Column {
                        KText(model.error!!, MarqueeType.Body, color = MarqueeColors.TextSecondary)
                        TvChip("Retry", { model.load(true) }, Modifier.restorable(model.focus, "retry"))
                    }
                }
                val credits = model.credits
                if (credits == null) {
                    items(12, key = { "sk$it" }, contentType = { "poster" }) { PosterCard(null, {}) }
                } else if (credits.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, contentType = "message") { Column {
                        KText(model.error ?: "No titles in your library.", MarqueeType.Body, color = MarqueeColors.TextSecondary)
                        Spacer(Modifier.height(12.dp)); TvChip("Retry", { model.load(true) }, Modifier.restorable(model.focus, "retry"))
                    } }
                } else {
                    itemsIndexed(credits, key = { _, it -> it.id }, contentType = { _, _ -> "poster" }) { i, it ->
                        PosterCard(it, onClick = { nav.open(it) }, modifier = Modifier.restorable(model.focus, "c_" + it.id),
                            onFocused = { scroll.grid(scope, model.grid, i + 1 + if (model.error != null) 1 else 0, with(density) { 48.dp.toPx() }.toInt()) })
                    }
                }
            }
        }
    }
    if (model.bioExpanded) com.mav.marquee.ui.library.kit.TextSheet(
        model.person?.name.orEmpty(), model.person?.overview.orEmpty(), onDismiss = {
            model.bioExpanded = false; model.focus.focus("bio")
        })
    LaunchedEffect(Unit) { model.focus.arm("back") }
    LaunchedEffect(model.credits != null, model.error) {
        if (model.error != null) model.focus.focus("retry")
        else if (model.credits != null) model.focus.arm(model.credits?.firstOrNull()?.let { "c_" + it.id } ?: "back")
    }
}

@Composable
private fun Header(model: PersonModel, onBack: () -> Unit) {
    val p = model.person
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(Modifier.onFocusChanged {
        model.headerFocused = it.hasFocus
        if (it.hasFocus) model.grid.requestScrollToItem(0)
    }.focusGroup()) {
    Row(Modifier.padding(bottom = 16.dp)) {
        TvChip("Back", onBack, Modifier.restorable(model.focus, "back"))
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        val w = MarqueeDims.PosterWidth; val h = MarqueeDims.PosterHeight
        Box(Modifier.size(w, h).clip(MarqueeDims.ShapeCard), contentAlignment = Alignment.Center) {
            val url = p?.imageTags?.get("Primary")?.let { ImageUrls.build(p.id, ImageUrls.Type.Primary, it, w.px(), h.px()) }
            NetImage(url, Modifier.fillMaxSize(), blurHash = p?.imageTags?.get("Primary")?.let { t -> p.imageBlurHashes?.get("Primary")?.get(t) }, fallbackText = p?.name)
            if (p != null && url == null) KText(initials(p.name), MarqueeType.Display, color = MarqueeColors.TextSecondary)
        }
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            if (p == null) {
                SkeletonBox(Modifier.size(280.dp, 36.dp)); Spacer(Modifier.height(12.dp)); SkeletonBox(Modifier.size(200.dp, 14.dp))
                return@Column
            }
            KText(p.name, MarqueeType.Display, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            val born = p.premiereDate?.take(10)?.let { formatDate(it) }
            val died = p.endDate?.take(10)?.let { formatDate(it) }
            val meta = listOfNotNull(born?.let { "Born $it" }, died?.let { "Died $it" },
                model.credits?.size?.takeIf { it > 0 }?.let { "$it titles in your library" }).joinToString("  ·  ")
            if (meta.isNotEmpty()) KText(meta, MarqueeType.Meta, color = MarqueeColors.TextSecondary, maxLines = 1)
            val bio = p.overview
            if (!bio.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                KText(bio, MarqueeType.Body, color = MarqueeColors.TextSecondary, maxLines = 5)
                if (bio.length > 300) {
                    Spacer(Modifier.height(16.dp))
                    TvChip("Read more", { model.bioExpanded = true },
                        modifier = Modifier.restorable(model.focus, "bio"))
                }
            }
            Spacer(Modifier.height(12.dp))
            KText("FILMOGRAPHY", MarqueeType.Eyebrow, color = MarqueeColors.TextTertiary)
        }
    }
}

}

private fun formatDate(iso: String): String = runCatching {
    val d = java.time.LocalDate.parse(iso)
    d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH))
}.getOrDefault(iso)
