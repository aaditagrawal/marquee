package com.mav.marquee.ui.library

import com.mav.marquee.ui.library.kit.instantDpad
import com.mav.marquee.ui.library.kit.restorable
import androidx.compose.ui.input.key.type
import com.mav.marquee.ui.library.kit.onInstantPreviewKeyEvent

import com.mav.marquee.ui.library.kit.InstantBringIntoView
import com.mav.marquee.ui.library.kit.snapListFocus

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import com.mav.marquee.ui.components.traceDraw
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import com.mav.marquee.ui.library.kit.traceScreenMeasure
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mav.marquee.data.Session
import com.mav.marquee.image.MarqueeImages
import com.mav.marquee.ui.components.CardPx
import com.mav.marquee.data.ImageUrls
import com.mav.marquee.data.Item
import com.mav.marquee.data.ItemQuery
import com.mav.marquee.data.JellyfinRepository
import com.mav.marquee.data.SortBy
import com.mav.marquee.ui.library.kit.KText
import com.mav.marquee.ui.library.kit.KitIcons
import com.mav.marquee.ui.library.kit.PosterCard
import com.mav.marquee.ui.library.kit.ScreenApi
import com.mav.marquee.ui.library.kit.TvChip
import com.mav.marquee.ui.library.kit.TvListRow
import com.mav.marquee.ui.library.kit.formatRuntime
import com.mav.marquee.ui.library.kit.rememberFocusFlag
import com.mav.marquee.ui.library.kit.trackFocus
import com.mav.marquee.ui.library.kit.yearRange
import com.mav.marquee.ui.nav.LocalNavigator
import com.mav.marquee.ui.theme.MarqueeColors
import com.mav.marquee.ui.theme.MarqueeDims
import com.mav.marquee.ui.theme.MarqueeType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.text.BasicText
import com.mav.marquee.ui.components.FocusAnim
import com.mav.marquee.ui.components.focusFill
import com.mav.marquee.ui.components.lerpColor

internal const val PAGE = 100

internal enum class LibSort(val label: String, val key: String, val defaultDesc: Boolean) {
    Name("Name", SortBy.Name, false),
    Added("Date added", SortBy.DateAdded, true),
    Released("Release date", SortBy.Premiere, true),
    Rating("Rating", SortBy.Rating, true),
    Runtime("Runtime", SortBy.Runtime, false),
    Random("Random", SortBy.Random, false),
}

@Stable
internal class LibFilters {
    var sort by mutableStateOf(LibSort.Name)
    var descending by mutableStateOf(false)
    var unwatched by mutableStateOf(false)
    var favorites by mutableStateOf(false)
    var genre by mutableStateOf<String?>(null)
    var decade by mutableStateOf<Int?>(null)
    val activeCount: Int get() = listOf(unwatched, favorites, genre != null, decade != null).count { it }
    fun signature() = "${sort.key}|$descending|$unwatched|$favorites|$genre|$decade"
}

/** Process-lifetime state per library so Back from a detail page restores data, scroll and focus instantly. */
@Stable
internal class LibraryModel(val viewId: String, val collectionType: String?) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val filters = LibFilters()
    var total by mutableIntStateOf(-1)
    val pages = mutableStateMapOf<Int, List<Item>>()
    val pageErrors = mutableStateMapOf<Int, String>()
    private val inflight = HashMap<Int, Job>()
    private var prefetchJob: Job? = null
    private var warmUrls = emptyList<String>()
    private var viewportFirst = 0
    private var viewportLast = 11
    private var randomIds: List<String>? = null
    var signature = ""
    var error by mutableStateOf<String?>(null)
    var focused by mutableStateOf<Item?>(null)
    var lastFocusedIndex = 0
    var scrollIndex = 0
    var scrollOffset = 0
    var jumpTo by mutableIntStateOf(-1)
    var jumpBusy by mutableStateOf(false)
        private set
    var jumpError by mutableStateOf<String?>(null)
    private var jumpJob: Job? = null
    var testPolicy: com.mav.marquee.ui.library.kit.ScreenTestPolicy? = null
    var genres by mutableStateOf<List<String>>(emptyList())
    var decades by mutableStateOf<List<Int>>(emptyList())

    val types: List<String>? = when (collectionType) {
        "movies" -> listOf("Movie"); "tvshows" -> listOf("Series"); "boxsets" -> listOf("BoxSet")
        "playlists" -> listOf("Playlist"); "music" -> listOf("MusicAlbum"); "musicvideos" -> listOf("MusicVideo")
        "homevideos" -> listOf("Video", "Photo"); else -> null
    }
    val square: Boolean get() = collectionType == "music" || collectionType == "playlists"
    val noun: String = when (collectionType) {
        "movies" -> "movies"; "tvshows" -> "shows"; "boxsets" -> "collections"; "playlists" -> "playlists"
        "music" -> "albums"; else -> "items"
    }

    fun itemAt(i: Int): Item? = pages[i / PAGE]?.getOrNull(i % PAGE)

    private fun query(start: Int, limit: Int) = ItemQuery(
        parentId = viewId, includeItemTypes = types, recursive = types != null,
        sortBy = if (filters.sort in listOf(LibSort.Name, LibSort.Random)) listOf(SortBy.Name) else listOf(filters.sort.key, SortBy.Name),
        descending = filters.sort != LibSort.Random && filters.descending, startIndex = start, limit = limit,
        filters = buildList { if (filters.unwatched) add("IsUnplayed"); if (filters.favorites) add("IsFavorite") }.ifEmpty { null },
        genres = filters.genre?.let { listOf(it) },
        years = filters.decade?.let { d -> (d..d + 9).toList() },
        fields = listOf("PrimaryImageAspectRatio"),
        enableTotalRecordCount = start == 0,
    )

    /** (Re)load from page 0 if the filter signature changed. */
    fun reloadIfNeeded(): Boolean {
        val sig = filters.signature()
        if (sig == signature && (total >= 0 || inflight.isNotEmpty())) return false
        signature = sig
        inflight.values.forEach { it.cancel() }; inflight.clear()
        randomIds = null
        cancelPrefetch()
        pages.clear(); pageErrors.clear(); total = -1; error = null; focused = null
        lastFocusedIndex = 0; scrollIndex = 0; scrollOffset = 0
        load(0)
        return true
    }

    fun ensure(firstVisible: Int, lastVisible: Int) {
        if (total <= 0) return
        viewportFirst = firstVisible; viewportLast = lastVisible
        val from = firstVisible / PAGE
        val to = ((lastVisible + 12).coerceAtMost(total - 1)) / PAGE
        for (p in from..to) load(p)
        // Visible cells keep their metadata and dimensions while nearby pages arrive or are evicted.
        pages.keys.toList().filter { it < from - 1 || it > to + 1 }.forEach { pages.remove(it) }
        schedulePrefetch()
    }

    fun cancelPrefetch() { prefetchJob?.cancel(); prefetchJob = null; warmUrls = emptyList() }

    private fun schedulePrefetch() {
        // Only the next two rows. Cancel the dwell/queue during fast travel; shared visible loads survive.
        val urls = ((viewportLast + 1)..(viewportLast + 12).coerceAtMost(total - 1)).mapNotNull { i ->
            itemAt(i)?.let { ImageUrls.poster(it, CardPx.PosterW, CardPx.PosterH) }
        }
        if (urls == warmUrls) return
        prefetchJob?.cancel(); warmUrls = urls
        prefetchJob = scope.launch {
            delay(150)
            for (pair in urls.chunked(2)) coroutineScope {
                pair.map { url -> async(Dispatchers.Default) {
                    if (MarqueeImages.cached(url) == null) {
                        try { MarqueeImages.load(url) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { /* A visible node supplies its designed fallback. */ }
                    }
                } }.awaitAll()
            }
        }
    }

    private fun randomSnapshot() {
        if (inflight.containsKey(-1)) return
        val sig = signature
        val base = query(0, PAGE).copy(sortBy = listOf(SortBy.Name), descending = false)
        inflight[-1] = scope.launch {
            try {
                val ids = ArrayList<String>()
                var start = 0
                var count = Int.MAX_VALUE
                while (start < count) {
                    val result = JellyfinRepository.itemIds(base.copy(startIndex = start, enableTotalRecordCount = start == 0))
                    if (sig != signature) return@launch
                    if (start == 0) count = result.totalRecordCount
                    if (result.items.isEmpty()) break
                    ids.addAll(result.items.map { it.id })
                    start += result.items.size
                }
                val shuffled = kotlinx.coroutines.withContext(Dispatchers.Default) { ids.distinct().shuffled() }
                if (sig != signature) return@launch
                randomIds = shuffled
                total = shuffled.size
                load(0)
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: Exception) { if (sig == signature) error = "Couldn't shuffle this library. Try again." }
            finally { if (sig == signature) inflight.remove(-1) }
        }
    }

    fun load(page: Int) {
        if (filters.sort == LibSort.Random && randomIds == null) { randomSnapshot(); return }
        if (pages.containsKey(page) || inflight.containsKey(page)) return
        val sig = signature
        inflight[page] = scope.launch {
            try {
                testPolicy?.before(if (page == 0) "library" else "paging")
                val shuffledPage = randomIds?.drop(page * PAGE)?.take(PAGE)
                val r = if (shuffledPage == null) JellyfinRepository.items(query(page * PAGE, PAGE))
                    else if (shuffledPage.isEmpty()) com.mav.marquee.data.ItemsResult()
                    else JellyfinRepository.items(ItemQuery(ids = shuffledPage, limit = PAGE,
                        enableTotalRecordCount = false, fields = listOf("PrimaryImageAspectRatio")))
                if (sig != signature) return@launch
                if (page == 0 && shuffledPage == null) total = r.totalRecordCount
                val loaded = if (shuffledPage == null) r.items else r.items.associateBy { it.id }.let { byId -> shuffledPage.mapNotNull(byId::get) }
                pages[page] = loaded
                scope.launch { LibraryFocusLayouts.warm(loaded) }
                pageErrors.remove(page)
                schedulePrefetch()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (sig == signature) {
                    pageErrors[page] = "Press OK to retry"
                    if (page == 0) error = "Couldn't load this library. Check the connection and try again."
                }
            } finally {
                if (sig == signature) inflight.remove(page)
            }
        }
    }

    fun retry() { signature = ""; reloadIfNeeded(); jumpTo = 0 }

    fun loadFacets() {
        if (genres.isNotEmpty()) return
        scope.launch {
            runCatching { JellyfinRepository.genres(viewId, types) }.onSuccess { genres = it.map { g -> g.name } }
            runCatching { ScreenApi.yearsInLibrary(viewId, types) }.onSuccess { ys ->
                decades = ys.map { it / 10 * 10 }.distinct().sortedDescending()
            }
        }
    }

    fun jump(letter: Char) {
        if (total <= 0) return
        jumpJob?.cancel(); jumpBusy = true; jumpError = null
        jumpJob = scope.launch {
            try {
                testPolicy?.before("jump")
                val idx = if (letter == '#') 0 else ScreenApi.countBefore(viewId, types, letter.lowercaseChar().toString(),
                    buildList { if (filters.unwatched) add("IsUnplayed"); if (filters.favorites) add("IsFavorite") }.ifEmpty { null },
                    filters.genre?.let { listOf(it) }, filters.decade?.let { d -> (d..d + 9).toList() })
                jumpTo = idx.coerceIn(0, total - 1)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { jumpError = "Couldn't jump to $letter. Press OK to retry." }
            finally { jumpBusy = false }
        }
    }

    companion object {
        private val models = LinkedHashMap<String, LibraryModel>()
        fun clear() {
            models.values.forEach { it.scope.coroutineContext[Job]?.cancel() }
            models.clear()
        }
        fun get(viewId: String, collectionType: String?): LibraryModel {
            val key = Session.userId + ":" + viewId
            val model = models.remove(key) ?: LibraryModel(viewId, collectionType)
            models[key] = model
            while (models.size > 4) models.remove(models.keys.first())?.scope?.coroutineContext?.get(Job)?.cancel()
            return model
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private class MarginBringIntoView(private val margin: Float, private val caption: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val lead = offset - margin
        val trail = offset + size + margin + caption - containerSize
        return when {
            lead < 0 -> lead
            trail > 0 -> if (size + 2 * margin + caption > containerSize) lead else trail
            else -> 0f
        }
    }
}

/**
 * Virtualized poster grid for a library: sort / filter panel, A–Z rail (name sort), item count, paging (100) with prefetch.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(viewId: String, title: String, collectionType: String?) {
    val nav = LocalNavigator.current
    val model = remember(viewId) { LibraryModel.get(viewId, collectionType) }
    val f = model.filters
    val context = LocalContext.current
    LibraryFocusLayouts.init(context)
    if (model.testPolicy == null) model.testPolicy = com.mav.marquee.ui.library.kit.ScreenTestPolicy.from(context)
    DisposableEffect(model) { onDispose { model.cancelPrefetch() } }

    LaunchedEffect(model) {
        model.focused?.let { previous ->
            runCatching { JellyfinRepository.item(previous.id, listOf("PrimaryImageAspectRatio")) }.onSuccess { fresh ->
                val page = model.lastFocusedIndex / PAGE
                model.pages[page]?.let { entries -> model.pages[page] = entries.map { if (it.id == fresh.id) fresh else it } }
                model.focused = fresh
            }
        }
    }
    var sheetOpen by remember { mutableStateOf(false) }
    val filterFr = remember { FocusRequester() }
    val gridFr = remember { FocusRequester() }
    val retryFr = remember { FocusRequester() }

    val lazyPrefetch = remember(context) {
        (context as? android.app.Activity)?.intent?.getBooleanExtra("lazyPrefetch", true) ?: true
    }
    LaunchedEffect(lazyPrefetch) { android.util.Log.i("Marquee", "MARQUEE_GRID_PREFETCH enabled=$lazyPrefetch") }
    val rowGrid = remember(context) {
        ((context as? android.app.Activity)?.intent?.getBooleanExtra("rowGrid", collectionType == "movies") ?: (collectionType == "movies")) && !model.square
    }
    LaunchedEffect(rowGrid) { android.util.Log.i("Marquee", "MARQUEE_ROW_GRID enabled=$rowGrid") }
    val rowState = remember {
        androidx.compose.foundation.lazy.LazyListState(model.scrollIndex / 6, 0)
    }
    val gridState = remember {
        if (lazyPrefetch) LazyGridState(model.scrollIndex, model.scrollOffset)
        else LazyGridState(model.scrollIndex, model.scrollOffset, NoopGridPrefetch)
    }
    LaunchedEffect(f.signature()) { if (model.reloadIfNeeded()) { if (rowGrid) rowState.requestScrollToItem(0) else gridState.scrollToItem(0) } }
    LaunchedEffect(gridState, rowGrid) {
        if (rowGrid) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.let { v -> (v.firstOrNull()?.index ?: 0) to (v.lastOrNull()?.index ?: 0) } }
            .distinctUntilChanged()
            .collect { (a, b) -> model.ensure(a, b); model.scrollIndex = gridState.firstVisibleItemIndex; model.scrollOffset = gridState.firstVisibleItemScrollOffset }
    }

    LaunchedEffect(model.jumpTo) {
        val target = model.jumpTo
        if (target >= 0 && model.total > 0) {
            model.load(target / PAGE)
            if (rowGrid) rowState.requestScrollToItem(target / 6) else gridState.scrollToItem(target)
        }
    }
    if (com.mav.marquee.BuildConfig.DEBUG) com.mav.marquee.ui.library.kit.ScreenStateMarker("library", when {
        model.jumpBusy -> "jump"
        model.error != null -> "error"
        model.total < 0 -> "skeleton"
        model.total == 0 -> "empty"
        (if (rowGrid) (rowState.firstVisibleItemIndex * 6 until minOf(model.total, (rowState.firstVisibleItemIndex + 2) * 6)).any { model.itemAt(it) == null }
         else gridState.layoutInfo.visibleItemsInfo.any { model.itemAt(it.index) == null }) -> "paging"
        else -> "content"
    })
    val columns = 6
    val cardW = MarqueeDims.PosterWidth
    val aspect = if (model.square) 1f else 2f / 3f

    Box(Modifier.fillMaxSize().instantDpad().background(MarqueeColors.Background)) {
        Column(Modifier.fillMaxSize().padding(top = MarqueeDims.SafeV)) {
            // Header: title + count, sort/filter chips.
            Row(Modifier.fillMaxWidth().padding(horizontal = MarqueeDims.SafeH), verticalAlignment = Alignment.CenterVertically) {
                KText(title.ifEmpty { "Library" }, MarqueeType.Headline, maxLines = 1)
                Spacer(Modifier.width(12.dp))
                val t = model.total
                KText(if (t >= 0) "$t ${model.noun}" else " ", MarqueeType.Meta, color = MarqueeColors.TextTertiary)
                Spacer(Modifier.weight(1f))
                TvChip(
                    "Sort: ${f.sort.label}" + if (f.sort != LibSort.Random) (if (f.descending) " ↓" else " ↑") else "",
                    onClick = { sheetOpen = true }, icon = KitIcons.Tune,
                    modifier = Modifier.focusRequester(filterFr).focusProperties { down = if (model.total != 0 && model.error == null) gridFr else FocusRequester.Default },
                )
                if (f.unwatched) { Spacer(Modifier.width(8.dp)); TvChip("Unwatched", { f.unwatched = false }, selected = true) }
                if (f.favorites) { Spacer(Modifier.width(8.dp)); TvChip("Favorites", { f.favorites = false }, selected = true) }
                f.genre?.let { g -> Spacer(Modifier.width(8.dp)); TvChip(g, { f.genre = null }, selected = true) }
                f.decade?.let { d -> Spacer(Modifier.width(8.dp)); TvChip("${d}s", { f.decade = null }, selected = true) }
            }
            // Focused-item caption (fixed height: no layout jump).
            FocusedCaption(model, Modifier.padding(horizontal = MarqueeDims.SafeH).padding(top = 8.dp).height(24.dp))

            Box(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val spec = remember { object : BringIntoViewSpec { override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f } }
                val scroll = remember(gridState) { com.mav.marquee.ui.library.kit.ScreenScroll() }
                val scrollScope = androidx.compose.runtime.rememberCoroutineScope()
                Box(Modifier.fillMaxSize()) {
                    val total = model.total
                    when {
                        model.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                KText(model.error!!, MarqueeType.Body, color = MarqueeColors.TextSecondary)
                                Spacer(Modifier.height(16.dp)); TvChip("Retry", { model.retry() }, Modifier.focusRequester(retryFr))
                            }
                        }
                        total == 0 -> Message(if (f.activeCount > 0) "Nothing matches these filters." else "This library is empty.", onRetry = { model.retry() })
                        else -> androidx.compose.runtime.CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                            if (rowGrid) RowPosterGrid(model, rowState, if (total < 0) columns * 3 else total, cardW, aspect,
                                Modifier.focusRequester(gridFr), onOpen = { nav.open(it) })
                            else LazyVerticalGrid(
                                columns = GridCells.FixedSize(cardW),
                                state = gridState,
                                modifier = Modifier.fillMaxSize().focusRequester(gridFr),
                                contentPadding = PaddingValues(start = MarqueeDims.SafeH, end = MarqueeDims.SafeH, top = 16.dp, bottom = 48.dp),
                                horizontalArrangement = Arrangement.spacedBy(MarqueeDims.CardGap),
                                verticalArrangement = Arrangement.spacedBy(MarqueeDims.RowGap),
                            ) {
                                val count = if (total < 0) columns * 3 else total
                                items(count, key = { it }, contentType = { 0 }) { index ->
                                    GridCell(model, index, cardW, aspect, onOpen = { nav.open(it) },
                                        onFocusedScroll = { scroll.grid(scrollScope, gridState, index, with(density) { 48.dp.toPx() }.toInt()) })
                                }
                            }
                        }
                    }
                }
                if (f.sort == LibSort.Name && !f.descending && model.total > 30) AlphaRail(model, Modifier.align(Alignment.CenterEnd).padding(end = MarqueeDims.SafeH, top = 8.dp, bottom = MarqueeDims.SafeV))
            }
        }

        if (sheetOpen) {
            LaunchedEffect(Unit) { model.loadFacets() }
            BackHandler { sheetOpen = false }
            FilterSheet(model, onClose = { sheetOpen = false })
        }
    }

    // Initial / restored focus: the last focused cell (or the first); the sort chip when empty or after the sheet.
    var restored by remember { mutableStateOf(false) }
    var sheetWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(model.total, model.error, sheetOpen) {
        if (sheetOpen) { sheetWasOpen = true; return@LaunchedEffect }
        if (model.error != null) {
            runCatching { retryFr.requestFocus() }
            return@LaunchedEffect
        }
        if (sheetWasOpen || model.total == 0) {
            sheetWasOpen = false
            runCatching { filterFr.requestFocus() }
            return@LaunchedEffect
        }
        if (model.total != 0 && !restored) {
            restored = true
            model.jumpTo = if (model.total > 0) model.lastFocusedIndex.coerceIn(0, model.total - 1) else 0
        }
    }
}

@Composable
internal fun GridCell(model: LibraryModel, index: Int, w: androidx.compose.ui.unit.Dp, aspect: Float, onOpen: (Item) -> Unit, onFocusedScroll: () -> Unit, modifier: Modifier = Modifier, resolveIndex: () -> Int = { index }) {
    com.mav.marquee.ui.components.NavWorkTrace.composition("LibraryCell")
    val item = model.itemAt(index)
    val fr = remember { FocusRequester() }
    val cellFocused = remember { booleanArrayOf(false) }
    LaunchedEffect(item) {
        if (cellFocused[0] && model.lastFocusedIndex == index) model.focused = item
    }
    Box(modifier.traceDraw("Marquee:MoviesCell:draw").testTag("library/$index").traceScreenMeasure("LibraryCell")) {
    PosterCard(
        item = item,
        onClick = { val current = resolveIndex(); model.itemAt(current)?.let(onOpen) ?: model.load(current / PAGE) },
        width = w,
        aspect = aspect,
        modifier = Modifier.focusRequester(fr)
            .onFocusChanged { cellFocused[0] = it.hasFocus }
            .onGloballyPositioned {
                if (model.jumpTo == index && runCatching { fr.requestFocus() }.getOrDefault(false)) model.jumpTo = -1
            },
        onFocused = { val current = resolveIndex(); onFocusedScroll(); model.lastFocusedIndex = current; model.focused = model.itemAt(current); if (model.focused == null) model.load(current / PAGE) },
    )
    if (item == null && model.pageErrors.containsKey(index / PAGE)) {
        KText("Couldn't load\nPress OK to retry", MarqueeType.Meta,
            Modifier.align(Alignment.Center).padding(12.dp), color = MarqueeColors.TextSecondary)
    }
    }
}

@Composable
private fun FocusedCaption(model: LibraryModel, modifier: Modifier) {
    com.mav.marquee.ui.components.NavWorkTrace.composition("LibraryFocusCaption")
    val it = model.focused
    // A changing wrap-content width otherwise remeasures/repositions the library column.
    Row(modifier.fillMaxWidth().traceDraw("Marquee:MoviesCaption:draw"), verticalAlignment = Alignment.CenterVertically) {
        if (model.jumpBusy || it == null && model.total < 0) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), color = MarqueeColors.Sky, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            KText(if (model.jumpBusy) "Finding this letter…" else "Loading library…", MarqueeType.Meta, color = MarqueeColors.TextSecondary)
        }
        if (model.jumpError != null) {
            KText(model.jumpError!!, MarqueeType.Meta, color = MarqueeColors.TextSecondary)
        } else if (it != null && !model.jumpBusy) {
            val entry = remember(it) { LibraryFocusLayouts.get(it) }
            CachedFocusText(entry.title, Modifier.alignByBaseline())
            entry.meta?.let { meta -> Spacer(Modifier.width(12.dp)); CachedFocusText(meta, Modifier.alignByBaseline()) }
        }
    }
}

internal fun libraryFocusMeta(item: Item) = listOfNotNull(
    yearRange(item) ?: item.albumArtist,
    item.officialRating,
    item.communityRating?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) },
    if (item.type == "Movie") formatRuntime(item.runtimeMs) else item.childCount?.let {
        if (item.type == "Series") { if (it == 1) "1 Season" else "$it Seasons" } else null
    },
).joinToString("  ·  ")

@Composable
private fun Message(text: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(MarqueeDims.SafeH), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            KText(text, MarqueeType.Title, color = MarqueeColors.TextSecondary)
            Spacer(Modifier.height(16.dp)); TvChip("Retry", onRetry)
        }
    }
}

private const val LETTERS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlphaRail(model: LibraryModel, modifier: Modifier) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.CompositionLocalProvider(LocalBringIntoViewSpec provides InstantBringIntoView) {
        LazyColumn(modifier.width(28.dp).fillMaxHeight(), state = state,
            contentPadding = PaddingValues(2.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            items(LETTERS.length, key = { LETTERS[it] }, contentType = { "letter" }) { i -> RailLetter(LETTERS[i], Modifier.snapListFocus(state, LETTERS[i], i)) { model.jump(LETTERS[i]) } }
        }
    }

}

@Composable
private fun RailLetter(c: Char, modifier: Modifier, onClick: () -> Unit) {
    val anim = remember { FocusAnim() }
    Box(modifier.size(24.dp).focusFill(anim, Color.Transparent,
        shapeRadius = MarqueeDims.RadiusSmall, scale = 1f, onClick = onClick),
        contentAlignment = Alignment.Center) {
        BasicText(c.toString(), style = MarqueeType.Eyebrow.copy(textAlign = TextAlign.Center),
            color = { lerpColor(MarqueeColors.TextTertiary, Color.Black, anim.value.value) })
    }
}

/** Right-side sheet: sort, order, show, genre, decade. Focus is trapped inside while open. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilterSheet(model: LibraryModel, onClose: () -> Unit) {
    val f = model.filters
    val context = LocalContext.current
    if (model.testPolicy == null) model.testPolicy = com.mav.marquee.ui.library.kit.ScreenTestPolicy.from(context)
    val first = remember { FocusRequester() }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val focus = remember { com.mav.marquee.ui.library.kit.FocusMemory() }
    val entries = remember(model.decades, model.genres) { buildList {
        add("sort-heading")
        LibSort.entries.indices.forEach { add("sort$it") }
        add("show-heading"); add("unwatched"); add("favorites")
        if (model.decades.isNotEmpty()) {
            add("decade-heading"); add("any-decade"); model.decades.forEach { add("d$it") }
        }
        if (model.genres.isNotEmpty()) {
            add("genre-heading"); add("any-genre"); model.genres.forEach { add("g$it") }
        }
        add("done")
    } }
    val controls = remember(entries) { entries.filterNot { it.endsWith("-heading") } }
    Box(Modifier.fillMaxSize().instantDpad().background(MarqueeColors.ScrimMedium)) {
        androidx.compose.runtime.CompositionLocalProvider(LocalBringIntoViewSpec provides InstantBringIntoView) {
            LazyColumn(
                Modifier.align(Alignment.CenterEnd).width(360.dp).fillMaxHeight()
                    .background(MarqueeColors.SurfaceRaised)
                    .onInstantPreviewKeyEvent { event ->
                        if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false else {
                            val delta = when (event.nativeKeyEvent.keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_UP -> -1
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 1
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> return@onInstantPreviewKeyEvent true
                                else -> 0
                            }
                            if (delta == 0) false else {
                                val index = controls.indexOf(focus.pending ?: focus.last).coerceAtLeast(0)
                                val next = controls[(index + delta).coerceIn(0, controls.lastIndex)]
                                focus.focus(next)
                                if (focus.pending != null) state.requestScrollToItem(entries.indexOf(next))
                                true
                            }
                        }
                    }.focusGroup(),
                state = state,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = MarqueeDims.SafeV),
            ) {
                item(key = "sort-heading", contentType = "text") { SheetHeader("Sort by") }
                items(LibSort.entries.size, key = { "sort$it" }, contentType = { "option" }) { i ->
                    val s = LibSort.entries[i]
                    TvListRow(s.label, onClick = {
                        if (f.sort == s && s == LibSort.Random) model.retry()
                        else if (f.sort == s) f.descending = !f.descending
                        else { f.sort = s; f.descending = s.defaultDesc }
                    }, selected = f.sort == s,
                        value = if (f.sort == s && s != LibSort.Random) (if (f.descending) "Descending" else "Ascending") else null,
                        modifier = Modifier.fillMaxWidth().restorable(focus, "sort$i").snapListFocus(state, "sort$i", entries.indexOf("sort$i")).then(if (i == 0) Modifier.focusRequester(first) else Modifier))
                }
                item(key = "show-heading", contentType = "text") { SheetHeader("Show") }
                item(key = "unwatched", contentType = "text") { TvListRow("Unwatched only", { f.unwatched = !f.unwatched }, selected = f.unwatched, modifier = Modifier.fillMaxWidth().restorable(focus, "unwatched").snapListFocus(state, "unwatched", entries.indexOf("unwatched"))) }
                item(key = "favorites", contentType = "text") { TvListRow("Favorites only", { f.favorites = !f.favorites }, selected = f.favorites, modifier = Modifier.fillMaxWidth().restorable(focus, "favorites").snapListFocus(state, "favorites", entries.indexOf("favorites"))) }
                if (model.decades.isNotEmpty()) {
                    item(key = "decade-heading", contentType = "text") { SheetHeader("Decade") }
                    item(key = "any-decade", contentType = "text") { TvListRow("Any decade", { f.decade = null }, selected = f.decade == null, modifier = Modifier.fillMaxWidth().restorable(focus, "any-decade").snapListFocus(state, "any-decade", entries.indexOf("any-decade"))) }
                    items(model.decades, key = { "d$it" }, contentType = { "option" }) { d ->
                        TvListRow("${d}s", { f.decade = if (f.decade == d) null else d }, selected = f.decade == d, modifier = Modifier.fillMaxWidth().restorable(focus, "d$d").snapListFocus(state, "d$d", entries.indexOf("d$d")))
                    }
                }
                if (model.genres.isNotEmpty()) {
                    item(key = "genre-heading", contentType = "text") { SheetHeader("Genre") }
                    item(key = "any-genre", contentType = "text") { TvListRow("Any genre", { f.genre = null }, selected = f.genre == null, modifier = Modifier.fillMaxWidth().restorable(focus, "any-genre").snapListFocus(state, "any-genre", entries.indexOf("any-genre"))) }
                    items(model.genres, key = { "g$it" }, contentType = { "option" }) { g ->
                        TvListRow(g, { f.genre = if (f.genre == g) null else g }, selected = f.genre == g, modifier = Modifier.fillMaxWidth().restorable(focus, "g$g").snapListFocus(state, "g$g", entries.indexOf("g$g")))
                    }
                }
                item(key = "done", contentType = "text") { Spacer(Modifier.height(12.dp)); TvListRow("Done", onClose, modifier = Modifier.fillMaxWidth().restorable(focus, "done").snapListFocus(state, "done", entries.indexOf("done"))) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun SheetHeader(text: String) {
    KText(text.uppercase(), MarqueeType.Eyebrow, color = MarqueeColors.TextTertiary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
}
