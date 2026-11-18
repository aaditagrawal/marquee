package com.mav.marquee.ui.search

import com.mav.marquee.ui.library.kit.instantDpad
import com.mav.marquee.ui.library.kit.onInstantPreviewKeyEvent

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mav.marquee.data.*
import com.mav.marquee.image.MarqueeImages
import com.mav.marquee.ui.components.*
import com.mav.marquee.ui.library.kit.*
import com.mav.marquee.ui.nav.LocalNavigator
import com.mav.marquee.ui.theme.*
import kotlinx.coroutines.*
import org.json.JSONArray

@Stable
private class SearchState {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Item>>(emptyList())
    var people by mutableStateOf<List<Item>>(emptyList())
    var loading by mutableStateOf(false)
    var retry by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
    val list = screenListState(true)
    val sectionScroll = ScreenScroll()
    val rows = HashMap<String, LazyListState>()
    val focus = FocusMemory()
    var completedQuery = ""
}

private object SearchMemory {
    var userId = ""
    var state = SearchState()
    fun get(): SearchState {
        if (userId != Session.userId) { userId = Session.userId; state = SearchState() }
        return state
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen() {
    val ctx = LocalContext.current
    val nav = LocalNavigator.current
    val model = remember { SearchMemory.get() }
    val testPolicy = remember { com.mav.marquee.ui.library.kit.ScreenTestPolicy.from(ctx) }
    val prefs = remember { ctx.getSharedPreferences("marquee_search", Context.MODE_PRIVATE) }
    val recentKey = "recent_${Session.userId}"
    var recent by remember { mutableStateOf(readRecent(prefs.getString(recentKey, "[]"))) }
    val density = LocalDensity.current
    val rowSpec = remember(density) { PivotSpec(with(density) { MarqueeDims.SafeH.toPx() }, manual = true) }
    val scope = rememberCoroutineScope()
    val input = remember { FocusRequester() }
    val resultFocus = remember { FocusRequester() }
    var resultsPending by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(resultsPending, model.loading, model.results.isEmpty(), model.people.isEmpty()) {
        if (resultsPending && !model.loading && (model.completedQuery == model.query.trim() || model.error != null)) {
            if (model.results.isNotEmpty() || model.people.isNotEmpty() || recent.isNotEmpty() || model.error != null) {
                runCatching { resultFocus.requestFocus() }
                model.list.requestScrollToItem(0)
            }
            resultsPending = false
        }
    }
    LaunchedEffect(model.loading, model.completedQuery, model.error) {
        if (!model.loading && model.focus.last?.startsWith("search_loading_") == true) {
            val key = model.results.firstOrNull()?.let { "result_${it.id}" }
                ?: model.people.firstOrNull()?.let { "person_${it.id}" }
            if (key != null) model.focus.focus(key)
            else runCatching { resultFocus.requestFocus() }
        }
    }
    fun moveToResults(): Boolean {
        keyboard?.hide()
        if ((model.query.isNotBlank() && model.completedQuery != model.query.trim()) || model.loading || model.results.isNotEmpty() || model.people.isNotEmpty() || recent.isNotEmpty() || model.error != null) {
            // Existing result/skeleton focus is handed off in the key handler, before scrolling.
            if (runCatching { resultFocus.requestFocus() }.getOrDefault(false)) resultsPending = false
            else resultsPending = true
            model.list.requestScrollToItem(0)
            return true
        }
        return false
    }
    fun saveRecent(term: String) {
        if (term.isBlank()) return
        recent = (listOf(term) + recent.filterNot { it.equals(term, true) }).take(8)
        prefs.edit().putString(recentKey, JSONArray(recent).toString()).apply()
    }

    LaunchedEffect(model.query.trim(), model.retry) {
        val term = model.query.trim()
        val attempt = model.retry
        if (term == model.completedQuery) return@LaunchedEffect
        model.results = emptyList(); model.people = emptyList(); model.error = null
        if (term.isEmpty()) { model.loading = false; model.completedQuery = ""; return@LaunchedEffect }
        model.loading = true
        delay(250)
        try {
            testPolicy.before("search")
            val found = coroutineScope {
                val movies = async { JellyfinRepository.search(term, 32, listOf("Movie")) }
                val shows = async { JellyfinRepository.search(term, 24, listOf("Series")) }
                val episodes = async { JellyfinRepository.search(term, 24, listOf("Episode")) }
                val people = async { JellyfinRepository.searchPeople(term, 20) }
                (movies.await() + shows.await() + episodes.await()) to people.await()
            }
            model.results = found.first; model.people = found.second; model.completedQuery = term
            MarqueeImages.prefetch(found.first.take(12).map { if (it.type == "Episode") landscapeUrl(it) else posterUrl(it) })
            model.list.scrollToItem(0)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { model.error = "Search couldn't reach the server. Try again." }
        finally { if (model.query.trim() == term && model.retry == attempt) model.loading = false }
    }
    LaunchedEffect(model.completedQuery) {
        val term = model.completedQuery
        if (term.isNotEmpty()) {
            delay(1200)
            if (model.query.trim() == term) saveRecent(term)
        }
    }
    LaunchedEffect(Unit) { model.focus.arm("input") }

    if (com.mav.marquee.BuildConfig.DEBUG) ScreenStateMarker("search", when {
        model.loading -> "loading"
        model.error != null -> "error"
        model.query.isBlank() -> "recent"
        model.results.isEmpty() && model.people.isEmpty() -> "empty"
        else -> "content"
    })
    Column(Modifier.fillMaxSize().instantDpad().background(MarqueeColors.Background).padding(top = MarqueeDims.SafeV)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = MarqueeDims.SafeH), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            KText("Search", MarqueeType.Headline)
            Spacer(Modifier.width(32.dp))
            TvField(model.query, { resultsPending = false; model.query = it; model.loading = it.isNotBlank() && it.trim() != model.completedQuery }, "Movies, shows, episodes and people",
                Modifier.weight(1f).restorable(model.focus, "input").focusRequester(input), onDone = {
                    saveRecent(model.query.trim()); moveToResults()
                }, onMoveDown = { moveToResults() })
            Spacer(Modifier.width(16.dp))
            Chip("Clear", { resultsPending = false; model.query = ""; input.requestFocus() })
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = MarqueeDims.SafeH).height(24.dp)) {
            if (model.loading) androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = MarqueeDims.FocusOuterRingWidth).height(2.dp), color = MarqueeColors.Sky,
                trackColor = MarqueeColors.SurfaceHigh)
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
        LazyColumn(state = model.list, modifier = Modifier.fillMaxSize().focusRequester(resultFocus), contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(MarqueeDims.RowGap)) {
            if (model.query.isBlank()) {
                item(key = "recent", contentType = "section") {
                    Column(Modifier.padding(horizontal = MarqueeDims.SafeH)) {
                        KText("Recent searches", MarqueeType.RowTitle)
                        Spacer(Modifier.height(12.dp))
                        if (recent.isEmpty()) KText("Find something for tonight.", MarqueeType.BodyLarge, color = MarqueeColors.TextSecondary)
                        recent.forEach { term ->
                            TvListRow(term, { model.query = term; keyboard?.hide() }, Modifier.restorable(model.focus, "recent_$term"))
                        }
                        if (recent.isNotEmpty()) Chip("Clear history", {
                            recent = emptyList(); prefs.edit().remove(recentKey).apply(); model.focus.focus("input")
                        })
                    }
                }
            } else if (model.loading) {
                item(key = "loading", contentType = "section") {
                    Row(Modifier.padding(horizontal = MarqueeDims.SafeH), horizontalArrangement = Arrangement.spacedBy(MarqueeDims.CardGap)) {
                        repeat(6) { index -> com.mav.marquee.ui.library.kit.PosterCard(null, {},
                            Modifier.restorable(model.focus, "search_loading_$index")) }
                    }
                }
                item(key = "loading2", contentType = "section") { SkeletonRow(wide = true) }
            } else if (model.error != null) {
                item(contentType = "text") { Column(Modifier.padding(horizontal = MarqueeDims.SafeH)) {
                    KText(model.error!!, MarqueeType.BodyLarge, color = MarqueeColors.TextSecondary)
                    Spacer(Modifier.height(12.dp)); Chip("Retry", { model.completedQuery = ""; model.retry++ })
                } }
            } else {
                val groups = listOf("Movie" to "Movies", "Series" to "TV shows", "Episode" to "Episodes")
                groups.forEach { (type, title) ->
                    val items = model.results.filter { it.type == type }
                    val sectionIndex = groups.takeWhile { it.first != type }.count { (kind, _) -> model.results.any { it.type == kind } }
                    if (items.isNotEmpty()) item(key = type, contentType = "row") {
                        val rowState = model.rows.getOrPut(type) { screenListState(true) }
                        val rowScroll = remember(rowState) { ScreenScroll() }
                        Column(Modifier.onFocusChanged { if (it.hasFocus) model.sectionScroll.row(scope, model.list, sectionIndex, -with(density) { 48.dp.toPx() }.toInt()) }) {
                            RowHeader("$title  ·  ${items.size}"); Spacer(Modifier.height(MarqueeDims.RowTitleGap))
                            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                                LazyRow(state = rowState,
                                    contentPadding = PaddingValues(horizontal = MarqueeDims.SafeH, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(MarqueeDims.CardGap)) {
                                    itemsIndexed(items, key = { _, it -> it.id }, contentType = { _, it -> it.type }) { index, item ->
                                        val mod = Modifier.restorable(model.focus, "result_${item.id}")
                                            .onFocusChanged { if (it.hasFocus) rowScroll.row(scope, rowState, index) }
                                        val open = { saveRecent(model.query.trim()); keyboard?.hide(); ItemCache.put(item); nav.open(item) }
                                        if (type == "Episode") com.mav.marquee.ui.library.kit.LandscapeCard(item, open, mod)
                                        else com.mav.marquee.ui.library.kit.PosterCard(item, open, mod)
                                    }
                                }
                            }
                        }
                    }
                }
                if (model.people.isNotEmpty()) item(key = "people", contentType = "section") {
                    val rowState = model.rows.getOrPut("people") { screenListState(true) }
                    val rowScroll = remember(rowState) { ScreenScroll() }
                    val index = groups.count { (kind, _) -> model.results.any { it.type == kind } }
                    Column(Modifier.onFocusChanged { if (it.hasFocus) model.sectionScroll.row(scope, model.list, index, -with(density) { 48.dp.toPx() }.toInt()) }) {
                        RowHeader("People  ·  ${model.people.size}"); Spacer(Modifier.height(MarqueeDims.RowTitleGap))
                        LazyRow(state = rowState,
                            contentPadding = PaddingValues(horizontal = MarqueeDims.SafeH, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(MarqueeDims.CardGap)) {
                            itemsIndexed(model.people, key = { _, it -> it.id }, contentType = { _, _ -> "person" }) { index, p ->
                                PersonCard(p.name, null, ImageUrls.person(p.id, p.imageTags?.get("Primary"), 192),
                                    p.imageTags?.get("Primary")?.let { ImageUrls.blurHash(p, ImageUrls.Type.Primary, it) },
                                    { saveRecent(model.query.trim()); nav.open(p) }, Modifier.restorable(model.focus, "person_${p.id}").onFocusChanged { if (it.hasFocus) rowScroll.row(scope, rowState, index) })
                            }
                        }
                    }
                }
                if (model.results.isEmpty() && model.people.isEmpty()) item(contentType = "text") {
                    Column(Modifier.padding(horizontal = MarqueeDims.SafeH)) {
                    KText("No matches for “${model.query.trim()}”. Try a title or person’s name.", MarqueeType.BodyLarge,
                        color = MarqueeColors.TextSecondary)
                    Spacer(Modifier.height(16.dp)); Chip("Retry", { model.completedQuery = ""; model.retry++ })
                    }
                }
            }
        }
        }
    }
}

private fun readRecent(value: String?): List<String> = runCatching {
    val json = JSONArray(value ?: "[]")
    List(json.length()) { json.getString(it) }
}.getOrDefault(emptyList())
