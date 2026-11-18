package com.mav.marquee.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.focus.FocusRequester
import com.mav.marquee.ui.components.NavWorkTrace
import com.mav.marquee.ui.components.NavigationScroll
import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import com.mav.marquee.data.HomeRepository
import com.mav.marquee.data.HomeSnapshot
import com.mav.marquee.data.ImageUrls
import com.mav.marquee.data.Item
import com.mav.marquee.data.JellyfinRepository
import com.mav.marquee.data.Session
import com.mav.marquee.data.UserDto
import com.mav.marquee.image.MarqueeImages
import com.mav.marquee.image.HeroArt
import com.mav.marquee.ui.components.CardPx
import com.mav.marquee.ui.components.CardCaptionLayouts
import com.mav.marquee.ui.components.BoundedNestedPrefetch
import com.mav.marquee.ui.theme.MarqueeDims
import com.mav.marquee.ui.components.NoopLazyPrefetch
import androidx.compose.foundation.ExperimentalFoundationApi
import com.mav.marquee.ui.components.landscapeUrl
import com.mav.marquee.ui.components.posterUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Process-lifetime Home state: survives navigation (Home is disposed while Detail is shown), so scroll
 * positions and the focused card come back exactly. Stale-while-revalidate: [preload] shows the disk
 * snapshot first, then [refresh] replaces it with fresh data loaded in parallel.
 */
@OptIn(ExperimentalFoundationApi::class)
object HomeController {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val snapshot = mutableStateOf<HomeSnapshot?>(null)
    val hero = mutableStateOf<Item?>(null)
    val billboard = mutableStateOf<Item?>(null)
    val heroArtwork = mutableStateOf<Item?>(null)
    val context = mutableStateOf<Item?>(null)
    val contextArt = mutableStateOf<Bitmap?>(null)
    val contextArtItemId = mutableStateOf<String?>(null)
    private var contextArtJob: Job? = null
    private var dpadHeld = false
    private var contextArtEnabled = true
    /** Ephemeral launch-only measurement control; normal launches always enable context art. */
    fun setContextArtEnabled(enabled: Boolean) {
        contextArtEnabled = enabled
        Log.i("Marquee", "MARQUEE_CONTEXT_ART enabled=$enabled")
        if (!enabled) { cancelContextArt(); contextArt.value = null; contextArtItemId.value = null }
    }
    val snapNavigation = true
    fun onDpadEvent(event: KeyEvent) { onDpadHeld(event.action == KeyEvent.ACTION_DOWN) }
    fun onDpadHeld(held: Boolean) {
        dpadHeld = held
        contextArtJob?.cancel()
        if (!held) focusFlow.value?.let(::requestContextArt)
    }
    fun cancelContextArt() { contextArtJob?.cancel(); contextArtJob = null }
    private fun requestContextArt(item: Item) {
        cancelContextArt()
        if (!collapsed.value || dpadHeld || !contextArtEnabled) return
        contextArtJob = main.launch {
            delay(350)
            if (dpadHeld || !collapsed.value || focusFlow.value?.id != item.id) return@launch
            val url = ImageUrls.backdrop(item, 640, 360)
            if (url == null) { contextArt.value = null; contextArtItemId.value = null; return@launch }
            try {
                val bitmap = HeroArt.cached(url, context = true) ?: HeroArt.load(url, context = true)
                if (focusFlow.value?.id == item.id && collapsed.value) {
                    contextArt.value = bitmap
                    contextArtItemId.value = if (bitmap != null) item.id else null
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    val user = mutableStateOf<UserDto?>(null)
    val error = mutableStateOf<String?>(null)
    val collapsed = mutableStateOf(false)

    private val focusFlow = MutableStateFlow<Item?>(null)
    var horizontalParentAnchor = false
    private var lazyPrefetchEnabled = true
    private var nestedPrefetchCount = 2
    fun setNestedPrefetchCount(count: Int) {
        val bounded = count.coerceIn(-1, 6)
        if (bounded != nestedPrefetchCount) { cancelRowScroll(); rowStates.clear(); cardScrolls.clear(); nestedPrefetchCount = bounded }
        Log.i("Marquee", "MARQUEE_NESTED_PREFETCH count=$bounded")
    }
    var column = LazyListState()
        private set
    /** Set before Home composition; fresh-process A/B without touching image prefetch. */
    fun setLazyPrefetchEnabled(enabled: Boolean) {
        if (enabled != lazyPrefetchEnabled) {
            cancelRowScroll()
            lazyPrefetchEnabled = enabled
            column = newListState()
            rowScroll = NavigationScroll(column)
            rowStates.clear(); cardScrolls.clear()
        }
        Log.i("Marquee", "MARQUEE_LAZY_PREFETCH enabled=$enabled")
    }
    private fun newListState(row: Boolean = false) = when {
        !lazyPrefetchEnabled -> LazyListState(prefetchStrategy = NoopLazyPrefetch)
        row && nestedPrefetchCount >= 0 -> LazyListState(prefetchStrategy = BoundedNestedPrefetch(nestedPrefetchCount))
        else -> LazyListState()
    }
    private var rowScroll = NavigationScroll(column)
    fun focusRow(index: Int, animationScope: CoroutineScope) {
        revealRows?.invoke(index)
        rowScroll.moveTo(index, snapNavigation, animationScope)
    }
    fun focusCard(key: String, index: Int, animationScope: CoroutineScope) {
        revealCards[key]?.invoke(index)
        cardScrolls.getOrPut(key) { NavigationScroll(rowState(key)) }.moveTo(index, snapNavigation, animationScope)
    }
    var physicalFocusRow = 0
    var revealRows: ((Int) -> Unit)? = null
    val revealCards = HashMap<String, (Int) -> Unit>()
    private val focusTargets = HashMap<String, MutableMap<String, FocusRequester>>()
    fun attachFocus(row: String, item: String, requester: FocusRequester) {
        focusTargets.getOrPut(row) { HashMap() }[item] = requester
    }
    fun focusRestored(): Boolean {
        val selected = restore ?: return false
        return runCatching { focusTargets[selected.first]?.get(selected.second)?.requestFocus() == true }.getOrDefault(false)
    }
    fun detachFocus(row: String, item: String, requester: FocusRequester) {
        val targets = focusTargets[row] ?: return
        if (targets[item] === requester) targets.remove(item)
    }

    /** Update known destination state before layout, even when its cell is not attached yet. */
    fun moveRowFocus(code: Int): Boolean {
        if (restoreHero) return false
        val selected = restore ?: return false
        val rows = snapshot.value?.rows ?: return false
        val rowIndex = rows.indexOfFirst { it.key == selected.first }
        if (rowIndex < 0) return false
        val index = rows[rowIndex].items.indexOfFirst { it.id == selected.second }
        if (index < 0) return false
        if (code == KeyEvent.KEYCODE_DPAD_UP && rowIndex == 0) return false
        val keyStart = NavWorkTrace.keyStart()
        try {
            val nextRow = when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> minOf(rowIndex + 1, rows.lastIndex)
                KeyEvent.KEYCODE_DPAD_UP -> rowIndex - 1
                else -> rowIndex
            }
            val row = rows[nextRow]
            if (row.items.isEmpty()) return true
            val nextIndex = when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> minOf(index + 1, row.items.lastIndex)
                KeyEvent.KEYCODE_DPAD_LEFT -> maxOf(index - 1, 0)
                // Rows keep their remembered horizontal anchor when returning vertically.
                else -> rowState(row.key).firstVisibleItemIndex.coerceAtMost(row.items.lastIndex)
            }
            val item = row.items[nextIndex]
            if (nextRow == rowIndex && nextIndex == index) return true
            restore = row.key to item.id
            restoreHero = false
            collapsed.value = nextRow > 0
            onFocus(item)
            // Horizontal focus only changes the child rail. Re-requesting the parent anchor
            // forces every visible Home row through measureAndLayout on the A55.
            revealRows?.invoke(nextRow)
            revealCards[row.key]?.invoke(nextIndex)
            if (nextRow != rowIndex || horizontalParentAnchor) column.requestScrollToItem(nextRow)
            rowState(row.key).requestScrollToItem(nextIndex)
            focusTargets[row.key]?.get(item.id)?.let { runCatching { it.requestFocus() } }
            return true
        } finally { NavWorkTrace.keyEnd(keyStart) }
    }

    fun cancelRowScroll() { rowScroll.cancel(); cardScrolls.values.forEach { it.cancel() } }
    private val rowStates = HashMap<String, LazyListState>()
    private val cardScrolls = HashMap<String, NavigationScroll>()
    fun rowState(key: String): LazyListState = rowStates.getOrPut(key) { newListState(row = true) }

    /** Focus to restore when Home is composed again: (rowKey, itemId), or the hero Play button. */
    var restore: Pair<String, String>? = null
    var restoreHero = true
    var restoreControl = "play"
    var initialFocusPending = true
    fun onControlFocus(key: String) { restore = null; restoreHero = true; restoreControl = key; collapsed.value = false }

    private var refreshJob: Job? = null
    private var lastFetch = 0L
    private var preloaded = false
    private var preloadJob: Job? = null
    private var account = ""
    private var prefetchJob: Job? = null
    private var focusPrefetchJob: Job? = null
    fun prefetchFocused(row: HomeRowUi, index: Int) {
        focusPrefetchJob?.cancel()
        focusPrefetchJob = main.launch {
            delay(250)
            withContext(Dispatchers.Default) {
                val next = row.items.subList(minOf(index + 1, row.items.size), minOf(index + 6, row.items.size))
                MarqueeImages.prefetch(next.map { if (row.wide) landscapeUrl(it) else posterUrl(it) })
                if (snapshot.value?.rows?.firstOrNull()?.key == row.key) row.items.getOrNull(index + 1)?.let {
                    MarqueeImages.warmDisk(listOf(ImageUrls.backdrop(it, CardPx.BackdropW, CardPx.BackdropH)))
                }
            }
        }
    }

    init {
        @OptIn(FlowPreview::class)
        main.launch { focusFlow.filterNotNull().debounce(200).collect { heroArtwork.value = it } }
        @OptIn(FlowPreview::class)
        main.launch {
            focusFlow.debounce(250).collectLatest { item ->
                if (item == null) return@collectLatest
                val expectedAccount = "${Session.serverUrl}|${Session.userId}"
                val cached = com.mav.marquee.ui.library.kit.ItemCache.get(item.id)
                if (cached?.mediaSources != null || cached?.mediaStreams != null) return@collectLatest
                try {
                    val detail = JellyfinRepository.item(item.id)
                    if (expectedAccount == "${Session.serverUrl}|${Session.userId}") com.mav.marquee.ui.library.kit.ItemCache.put(detail)
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
    }

    fun onFocus(item: Item) {
        // Text is synchronous; only image/metadata work goes through a dwell.
        context.value = item
        hero.value = item
        if (!collapsed.value) billboard.value = item
        focusFlow.value = item
        requestContextArt(item)
    }

    /**
     * Called by HomeScreen on entry. Login/user switch happens in other screens (they just `nav.reset(Route.Home)`),
     * so Home detects a different user itself, drops the old state and loads the new user's snapshot.
     */
    fun ensureUser(ctx: Context) {
        if (!Session.isLoggedIn) return
        val activeAccount = "${Session.serverUrl}|${Session.userId}"
        if (account.isNotEmpty() && account != activeAccount) reset()
        account = activeAccount
        val s = snapshot.value
        if (s != null && s.userId != Session.userId) { reset(); HomeRepository.clear(ctx) }
        if (snapshot.value == null) {
            if (!preloaded) { preload(ctx); return }
            refresh(ctx)
        }
    }

    /** Called from Application.onCreate: reads the disk snapshot off the main thread while the Activity starts. */
    fun preload(ctx: Context) {
        if (preloaded || !Session.isLoggedIn) return
        preloaded = true
        account = "${Session.serverUrl}|${Session.userId}"
        val expectedAccount = account
        preloadJob = main.launch {
            val cached = withContext(Dispatchers.IO) { HomeRepository.cached(ctx) }
            HomePerf.stage("snapshot-read")
            if (expectedAccount == account && cached != null && snapshot.value == null) {
                coroutineScope {
                    val first = cached.rows.firstOrNull()
                    first?.let { r -> async(Dispatchers.Default) {
                        CardCaptionLayouts.warm(r.items.take(if (r.wide) 4 else 6), (if (r.wide) MarqueeDims.LandscapeWidth else MarqueeDims.PosterWidth).value)
                    } }
                    first?.items?.firstOrNull()?.let { h ->
                        async(Dispatchers.Default) { ImageUrls.backdrop(h, CardPx.BackdropW, CardPx.BackdropH)?.let { HeroArt.load(it, diskOnly = true) } }
                        async(Dispatchers.Default) { ImageUrls.logo(h, CardPx.LogoW, CardPx.LogoH)?.let { MarqueeImages.load(it, opaque = false, diskOnly = true) } }
                    }
                    first?.items?.take(if (first.wide) 4 else 6)?.forEach { item ->
                        async(Dispatchers.Default) { (if (first.wide) landscapeUrl(item) else posterUrl(item))?.let { MarqueeImages.load(it, diskOnly = true) } }
                    }
                }
                HomePerf.stage("disk-art-ready")
                if (expectedAccount == account) {
                    HomePerf.fromCache = true; set(cached)
                    val full = withContext(Dispatchers.IO) { HomeRepository.cachedFull(ctx) }
                    if (expectedAccount == account && full != null) set(full)
                }
            }
            preloadJob = null
            refresh(ctx)
        }
    }

    fun refresh(ctx: Context, minAgeMs: Long = 0) {
        if (!Session.isLoggedIn || refreshJob?.isActive == true || preloadJob?.isActive == true) return
        if (SystemClock.elapsedRealtime() - lastFetch < minAgeMs) return
        val app = ctx.applicationContext
        refreshJob = main.launch {
            if (user.value == null) launch {
                try { user.value = JellyfinRepository.currentUser() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
            }
            runCatching { HomeRepository.fetch(app) }
                .onSuccess { lastFetch = SystemClock.elapsedRealtime(); error.value = null; set(it) }
                .onFailure {
                    if (it is CancellationException) throw it
                    error.value = if (snapshot.value == null) "Can't reach your server. Check its connection and try again."
                        else "Server unavailable · Showing your saved library"
                    Log.w("Marquee", "home refresh failed: ${it.javaClass.simpleName}")
                }
        }
    }

    fun reset() {
        cancelRowScroll()
        focusPrefetchJob?.cancel(); focusPrefetchJob = null
        cancelContextArt(); contextArt.value = null; contextArtItemId.value = null; dpadHeld = false
        snapshot.value = null; billboard.value = null; hero.value = null; heroArtwork.value = null; context.value = null; user.value = null; focusFlow.value = null
        physicalFocusRow = 0; revealRows = null; revealCards.clear()
        focusTargets.clear(); rowStates.clear(); cardScrolls.clear(); restore = null; restoreHero = true; restoreControl = "play"; initialFocusPending = true; collapsed.value = false; preloaded = false; lastFetch = 0
        refreshJob?.cancel(); refreshJob = null
        preloadJob?.cancel(); preloadJob = null; prefetchJob?.cancel(); prefetchJob = null
        column.requestScrollToItem(0)
    }

    private fun set(s: HomeSnapshot) {
        if (s.userId != Session.userId) return
        snapshot.value = s
        val cur = if (restore != null) focusFlow.value ?: hero.value else hero.value
        hero.value = cur?.let { h -> s.rows.firstNotNullOfOrNull { r -> r.items.firstOrNull { it.id == h.id } } ?: h }
            ?: s.rows.firstOrNull()?.items?.firstOrNull()
        if (!collapsed.value) billboard.value = hero.value
        if (heroArtwork.value == null) heroArtwork.value = hero.value
        prefetch(s)
    }

    private fun prefetch(s: HomeSnapshot) {
        val first = s.rows.firstOrNull()
        first?.items?.firstOrNull()?.let { h ->
            HeroArt.prefetch(ImageUrls.backdrop(h, CardPx.BackdropW, CardPx.BackdropH))
            MarqueeImages.prefetch(listOf(ImageUrls.logo(h, CardPx.LogoW, CardPx.LogoH)), opaque = false)
            MarqueeImages.pinStartup(listOf(ImageUrls.logo(h, CardPx.LogoW, CardPx.LogoH)), opaque = false)
        }
        first?.let { r ->
            val urls = r.items.take(if (r.wide) 4 else 6).map { if (r.wide) landscapeUrl(it) else posterUrl(it) }
            MarqueeImages.prefetch(urls)
            MarqueeImages.pinStartup(urls)
        }
        // Give the first screen priority over below-fold traffic and background GPU uploads.
        prefetchJob?.cancel()
        prefetchJob = main.launch {
            launch { ContextTextLayouts.warm(s.rows.flatMap { it.items }.distinctBy { it.id }) }
            launch { HeroTextLayouts.warm(s.rows.firstOrNull()?.items.orEmpty()) }
            delay(700)
            s.rows.take(3).forEach { r -> MarqueeImages.prefetch(r.items.take(8).map { if (r.wide) landscapeUrl(it) else posterUrl(it) }) }
            launch(Dispatchers.Default) {
                s.rows.forEach { r -> CardCaptionLayouts.warm(r.items.take(12), (if (r.wide) MarqueeDims.LandscapeWidth else MarqueeDims.PosterWidth).value) }
            }
            val upcoming = first?.items.orEmpty().drop(1).take(4)
            MarqueeImages.prefetch(upcoming.map { ImageUrls.logo(it, CardPx.LogoW, CardPx.LogoH) }, opaque = false)
            MarqueeImages.warmDisk(upcoming.map { ImageUrls.backdrop(it, CardPx.BackdropW, CardPx.BackdropH) })
        }
    }
}

/** Emits `MARQUEE_HOME_READY <ms>` once the hero backdrop and the visible first-row cards have been drawn. */
object HomePerf {
    fun stage(name: String) { Log.i("Marquee", "MARQUEE_STARTUP $name ${SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()}") }
    var fromCache = false
    var homeVisible = false
    var hasStarted = false
        private set
    private var heroDrawn = false
    private var logoNeeded: String? = null
    private var logoDrawn: String? = null
    private val cardsDrawn = HashSet<String>()
    private var cardsNeeded: Set<String>? = null
    private var done = false
    private var startedAt = Process.getStartElapsedRealtime()
    private var launch = "cold"
    var onReady: (() -> Unit)? = null

    fun begin(warm: Boolean) {
        hasStarted = true
        startedAt = if (warm) SystemClock.elapsedRealtime() else Process.getStartElapsedRealtime()
        launch = if (warm) "warm" else "cold"
        if (!warm) { heroDrawn = false; cardsDrawn.clear(); cardsNeeded = null; logoDrawn = null; logoNeeded = null }
        done = false
    }
    fun firstRowNeeds(ids: Set<String>) { cardsNeeded = ids }
    fun cardDrawn(id: String) { if (!done) { cardsDrawn.add(id); check() } }
    fun heroDrawn() { heroDrawn = true; check() }
    fun logoNeeds(url: String?) { logoNeeded = url }
    fun logoDrawn(url: String) { logoDrawn = url; check() }
    /** A resumed window reuses its rendered nodes; its images need not be decoded/redrawn again. */
    fun windowDrawn() { check() }

    private fun check() {
        if (done || !homeVisible || !heroDrawn || cardsNeeded == null || !cardsDrawn.containsAll(cardsNeeded!!)) return
        if (logoNeeded != null && logoDrawn != logoNeeded) return
        done = true
        val ms = SystemClock.elapsedRealtime() - startedAt
        Log.i("Marquee", "MARQUEE_HOME_READY $ms cache=$fromCache launch=$launch cards=${cardsNeeded!!.size}")
        Log.i("Marquee", "MARQUEE_IMAGE_CACHE ${MarqueeImages.stats()}")
        onReady?.invoke()
    }
}
