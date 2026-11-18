package com.mav.marquee.ui.home

import com.mav.marquee.ui.components.NavWorkTrace
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.testTag
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.view.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.graphicsLayer
import com.mav.marquee.ui.components.LayerSlots
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import kotlinx.collections.immutable.toImmutableList
import com.mav.marquee.data.ImageUrls
import com.mav.marquee.data.Item
import com.mav.marquee.data.Session
import com.mav.marquee.image.ImageFit
import com.mav.marquee.image.HeroArt
import com.mav.marquee.image.MarqueeImages
import com.mav.marquee.image.RemoteImage
import com.mav.marquee.image.drawFitted
import com.mav.marquee.ui.components.NativeCatalogCard
import com.mav.marquee.ui.components.CardPx
import com.mav.marquee.ui.components.CardCaptionGap
import com.mav.marquee.ui.components.CardCaptionHeight
import com.mav.marquee.ui.components.traceDraw
import com.mav.marquee.ui.components.traceMeasure
import com.mav.marquee.ui.components.Glyph
import com.mav.marquee.ui.components.GlyphIcon
import com.mav.marquee.ui.components.IconCircleButton
import com.mav.marquee.ui.components.LandscapeCard
import com.mav.marquee.ui.components.PillButton
import com.mav.marquee.ui.components.PosterCard
import com.mav.marquee.ui.components.RowHeader
import com.mav.marquee.ui.components.SkeletonRow
import com.mav.marquee.ui.components.SkeletonBlock
import com.mav.marquee.ui.components.FocusAnim
import com.mav.marquee.ui.components.focusFill
import com.mav.marquee.ui.components.formatRuntime
import com.mav.marquee.ui.components.landscapeUrl
import com.mav.marquee.ui.components.lerpColor
import com.mav.marquee.ui.components.posterUrl
import com.mav.marquee.ui.nav.LocalNavigator
import com.mav.marquee.ui.nav.Playback
import com.mav.marquee.ui.nav.Route
import com.mav.marquee.ui.theme.MarqueeColors
import com.mav.marquee.ui.theme.MarqueeDims
import com.mav.marquee.ui.theme.MarqueeType
import kotlinx.coroutines.launch

// Vertical layout of the 960x540 dp canvas (see design/DESIGN.md "Hero / Home header").
private val NavTop = 27.dp
private val HeroTop = 72.dp
internal val LogoH = 88.dp // Two 44sp display lines; starts below the avatar/nav.
internal val HeroSynopsisHeight = 48.dp
private val LogoArtH = 56.dp
private val RowsTop = 318.dp
private val ScrolledRowsTop = 140.dp
private val CardTopInRow = 36.dp // RowTitle 24 + gap 12; cards anchor at 176/540 = 32.6%

@Composable
fun HomeScreen() {
    NavWorkTrace.composition("HomeScreen")
    val ctx = LocalContext.current
    ContextTextLayouts.init(ctx)
    HeroTextLayouts.init(ctx)
    DisposableEffect(Unit) {
        HomePerf.homeVisible = true
        onDispose { HomePerf.homeVisible = false; HomeController.cancelContextArt() }
    }
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val firstFocus = remember { FocusRequester() }
    val navFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        HomePerf.stage("home-composed")
        keyboard?.hide()
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
        HomeController.ensureUser(ctx)
        if (HomeController.initialFocusPending) {
            HomeController.initialFocusPending = false
            runCatching { navFocus.requestFocus() }
        }
    }
    BackHandler {
        val focus = HomeController.restore
        val first = HomeController.snapshot.value?.rows?.firstOrNull()
        if (focus != null && first != null && focus.first != first.key) {
            HomeController.collapsed.value = false
            HomeController.cancelRowScroll()
            HomeController.restore = first.key to first.items.first().id
            HomeController.restoreHero = false
            HomeController.onFocus(first.items.first())
            HomeController.revealRows?.invoke(0)
            HomeController.revealCards[first.key]?.invoke(0)
            HomeController.column.requestScrollToItem(0)
            HomeController.rowState(first.key).requestScrollToItem(0)
            // Attached targets focus now; an offscreen target's placement effect handles the
            // next frame. Focus never awaits the scroll coroutine or billboard transition.
            if (!HomeController.focusRestored()) runCatching { firstFocus.requestFocus() }
        } else if (focus != null || HomeController.restoreControl == "play" || HomeController.restoreControl == "info") {
            runCatching { navFocus.requestFocus() }
        }
        // At the top nav Back is consumed without showing the system exit prompt.
    }
    Box(Modifier.fillMaxSize().traceDraw("Marquee:Home:draw").drawBehind {
        if (HomeController.collapsed.value) drawRect(MarqueeColors.Background)
    }.onPreviewKeyEvent {
        val event = it.nativeKeyEvent
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val keyStart = NavWorkTrace.keyStart()
                try {
                    HomeController.onDpadEvent(event)
                    event.action == KeyEvent.ACTION_DOWN && HomeController.moveRowFocus(event.keyCode)
                } finally { NavWorkTrace.keyEnd(keyStart) }
            }
            else -> false
        }
    }) {
        ContextBackdrop(Modifier.align(Alignment.TopEnd).size(576.dp, 297.dp).graphicsLayer {
            alpha = if (HomeController.collapsed.value) 1f else 0f
        })
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = if (HomeController.collapsed.value) 0f else 1f
        }.focusProperties { canFocus = !HomeController.collapsed.value }) {
            HeroBackdrop()
            HeroInfo(Modifier.padding(start = MarqueeDims.SafeH, top = HeroTop).width(520.dp))
        }
        HomeRows(Modifier.fillMaxSize().clipToBounds().graphicsLayer {
            translationY = if (HomeController.collapsed.value) 0f else with(density) { (RowsTop - ScrolledRowsTop).toPx() }
        }.padding(top = ScrolledRowsTop), firstFocus)
        ContextStrip(Modifier.padding(start = MarqueeDims.SafeH, end = MarqueeDims.SafeH, top = MarqueeDims.SafeV).fillMaxWidth())
        // Keep nav focus requesters attached while collapsed, without drawing the hidden nav.
        TopNav(Modifier.fillMaxWidth().padding(top = NavTop).drawWithContent {
            if (!HomeController.collapsed.value) drawContent()
        }.focusProperties { canFocus = !HomeController.collapsed.value }, navFocus)
        HomeController.error.value?.takeIf { HomeController.snapshot.value != null }?.let { error ->
            Column(Modifier.align(Alignment.TopEnd).padding(top = 80.dp, end = MarqueeDims.SafeH)
                .width(264.dp).background(MarqueeColors.SurfaceRaised, MarqueeDims.ShapePanel).padding(16.dp)) {
                BasicText(error, style = MarqueeType.Body.copy(color = MarqueeColors.TextSecondary))
                Spacer(Modifier.height(8.dp))
                PillButton("Retry", onClick = { HomeController.refresh(ctx) })
            }
        }
    }
}

// ------------------------------------------------------------------------------------------- backdrop

@Stable
private class Layers(initial: ImageBitmap?) {
    val cur = mutableStateOf(initial)
}

/** Full-bleed worker-baked backdrop. Completed artwork replaces the current bitmap in one draw. */
@Composable
private fun HeroBackdrop() {
    NavWorkTrace.composition("HeroBackdrop")
    val item = HomeController.heroArtwork.value
    val url = item?.let { ImageUrls.backdrop(it, CardPx.BackdropW, CardPx.BackdropH) }
    val layers = remember(url) { Layers(url?.let { HeroArt.cached(it)?.asImageBitmap() }) }
    LaunchedEffect(url) {
        if (HomeController.collapsed.value || item?.id != HomeController.hero.value?.id) return@LaunchedEffect
        if (url == null) { if (item != null) HomePerf.heroDrawn(); return@LaunchedEffect }
        val bmp = HeroArt.cached(url) ?: runCatching { HeroArt.load(url) }.getOrNull() ?: return@LaunchedEffect
        layers.cur.value = bmp.asImageBitmap()
    }
    Spacer(Modifier.fillMaxSize().graphicsLayer().drawWithCache {
        val bg = MarqueeColors.Background
        onDrawBehind {
            val cur = layers.cur.value
            if (cur == null) { drawRect(bg); if (item != null && url == null) HomePerf.heroDrawn() }
            else {
                drawFitted(cur, ImageFit.Fill)
                HomePerf.heroDrawn()
            }
        }
    })
}

// ------------------------------------------------------------------------------------------- hero text

@Composable
private fun HeroInfo(modifier: Modifier) {
    NavWorkTrace.composition("HeroInfo")
    val item = if (HomeController.collapsed.value) HomeController.billboard.value ?: HomeController.snapshot.value?.rows?.firstOrNull()?.items?.firstOrNull() else HomeController.hero.value
    if (item == null) {
        Column(modifier) {
            SkeletonBlock(Modifier.size(340.dp, LogoH))
            Spacer(Modifier.height(8.dp))
            BasicText(if (HomeController.error.value == null) "Loading your library" else "Your server is unavailable",
                style = MarqueeType.Meta.copy(color = MarqueeColors.TextSecondary))
            Spacer(Modifier.height(16.dp))
            SkeletonBlock(Modifier.size(480.dp, 16.dp))
            Spacer(Modifier.height(8.dp))
            SkeletonBlock(Modifier.size(400.dp, 16.dp))
            Spacer(Modifier.height(24.dp))
            SkeletonBlock(Modifier.size(176.dp, MarqueeDims.ButtonHeight))
        }
        return
    }
    val ctx = LocalContext.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }
    val infoFocus = remember { FocusRequester() }
    val playing = remember { mutableStateOf(false) }
    Column(modifier.traceDraw("Marquee:Hero:draw").testTag("home-billboard")) {
        HeroLogo(item)
        Spacer(Modifier.height(8.dp))
        CachedHeroText(item, 1)
        Spacer(Modifier.height(8.dp))
        CachedHeroText(item, 2, Modifier.width(MarqueeDims.HeroTextMaxWidth).height(HeroSynopsisHeight))
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val resume = item.resumeMs > 0
            val label = if (resume) "Continue" + (item.runtimeMs - item.resumeMs).takeIf { it > 60_000 }?.let { " · ${formatRuntime(it)} left" }.orEmpty() else "Play"
            PillButton(label, primary = true, glyph = Glyph.Play, modifier = Modifier.focusRequester(playFocus),
                busy = playing.value,
                onFocused = { HomeController.onControlFocus("play") },
                onClick = {
                    HomeController.onControlFocus("play")
                    playing.value = true
                    scope.launch {
                        try { if (!Playback.playOrResume(ctx, item)) nav.push(Route.Detail(item.id)) }
                        finally { playing.value = false }
                    }
                })
            IconCircleButton(Glyph.Info, modifier = Modifier.focusRequester(infoFocus),
                onFocused = { HomeController.onControlFocus("info") }, onClick = {
                HomeController.onControlFocus("info")
                nav.push(Route.Detail(item.id))
            })
        }
    }
    LaunchedEffect(Unit) {
        if (HomeController.restoreHero && HomeController.restore == null) runCatching {
            when (HomeController.restoreControl) { "play" -> playFocus.requestFocus(); "info" -> infoFocus.requestFocus() }
        }
    }
}

/** Art dwell invalidates this fixed logo slot, without recomposing synopsis/buttons/metadata. */
@Composable
private fun HeroLogo(item: Item) {
    val logo = if (HomeController.heroArtwork.value?.id == item.id) ImageUrls.logo(item, CardPx.LogoW, CardPx.LogoH) else null
    HomePerf.logoNeeds(logo)
    Box(Modifier.height(LogoH).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
        if (logo != null) RemoteImage(logo, Modifier.size(420.dp, LogoArtH), opaque = false, fit = ImageFit.FitStart,
            onDrawn = remember(logo) { { HomePerf.logoDrawn(logo) } })
        else CachedHeroText(item, 0)
    }
}

internal fun metaLine(item: Item) = buildAnnotatedString {
    val parts = ArrayList<String>()
    if (item.type == "Episode") {
        item.episodeLabel?.let(parts::add)
        if (!item.name.matches(Regex("Episode \\d+", RegexOption.IGNORE_CASE))) parts.add(item.name)
    } else {
        item.productionYear?.let { parts.add(it.toString()) }
    }
    var first = true
    fun sep() { if (!first) append("   ·   "); first = false }
    for (p in parts) { sep(); append(p) }
    item.communityRating?.takeIf { it > 0 }?.let { sep(); withStyle(SpanStyle(color = MarqueeColors.Yellow)) { append("★ ") }; append(String.format("%.1f", it)) }
    item.officialRating?.let { sep(); append(it) }
    if (item.type == "Series") item.childCount?.let { sep(); append(if (it == 1) "1 Season" else "$it Seasons") }
    else item.runtimeMs.takeIf { it > 0 }?.let { sep(); append(formatRuntime(it)) }
    item.genres?.take(3)?.takeIf { it.isNotEmpty() }?.let { sep(); append(it.joinToString(", ")) }
}

// ------------------------------------------------------------------------------------------- rows

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeRows(modifier: Modifier, firstFocus: FocusRequester) {
    NavWorkTrace.composition("HomeRows")
    val snap = HomeController.snapshot.value
    val err = HomeController.error.value
    if (snap == null) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(MarqueeDims.RowGap)) {
            if (err != null) {
                val ctx = LocalContext.current
                val focus = remember { FocusRequester() }
                BasicText(err, Modifier.padding(start = MarqueeDims.SafeH), style = MarqueeType.Body.copy(color = MarqueeColors.Red))
                PillButton("Try again", modifier = Modifier.padding(start = MarqueeDims.SafeH).focusRequester(focus),
                    onClick = { HomeController.error.value = null; HomeController.refresh(ctx) })
                LaunchedEffect(Unit) { focus.requestFocus() }
            } else SkeletonRow(wide = true)
        }
        return
    }
    if (snap.rows.isEmpty()) {
        Column(modifier.padding(horizontal = MarqueeDims.SafeH), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val nav = LocalNavigator.current
            val focus = remember { FocusRequester() }
            BasicText("Your library is ready for its first title", style = MarqueeType.Title.copy(color = MarqueeColors.TextPrimary))
            BasicText("Add movies or shows in Jellyfin, then refresh Home.", style = MarqueeType.Body.copy(color = MarqueeColors.TextSecondary))
            val ctx = LocalContext.current
            PillButton("Refresh", modifier = Modifier.focusRequester(focus), onClick = { HomeController.refresh(ctx) })
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        return
    }
    val rows = remember(snap) { snap.rows.map(::HomeRowUi).toImmutableList() }
    val density = LocalDensity.current
    val heights = remember(rows, density) {
        rows.map { with(density) {
            (CardTopInRow + (if (it.wide) MarqueeDims.LandscapeHeight else MarqueeDims.PosterHeight) +
                CardCaptionGap + CardCaptionHeight + MarqueeDims.RowGap).toPx()
        } }
    }
    val positions = remember(heights) { FloatArray(rows.size + 1).also { a -> heights.forEachIndexed { i, h -> a[i + 1] = a[i] + h } } }
    if (com.mav.marquee.ui.components.CardCaptionLayouts.enabled) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoVerticalBringIntoView) {
            NativeHomeRows(rows, positions, modifier.clipToBounds())
        }
        return
    }
    val slots = remember(rows) { androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { LayerSlots(5, (HomeController.column.firstVisibleItemIndex - 2).coerceAtLeast(0)) } }
    DisposableEffect(slots) {
        val reveal: (Int) -> Unit = { i -> slots.ensure((i - 2).coerceAtLeast(0), minOf(rows.lastIndex, i + 2), HomeController.physicalFocusRow) }
        HomeController.revealRows = reveal
        onDispose { if (HomeController.revealRows === reveal) HomeController.revealRows = null }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoVerticalBringIntoView) {
        Box(modifier.clipToBounds().traceDraw("Marquee:HomeRows:draw").traceMeasure("Marquee:HomeContainer:measure")) {
            repeat(5) { slot -> HomeRowSlot(rows, slots, slot, positions, firstFocus) }

        }
    }
}

@Composable
private fun HomeRowSlot(rows: kotlinx.collections.immutable.ImmutableList<HomeRowUi>, slots: LayerSlots, slot: Int,
    positions: FloatArray, firstFocus: FocusRequester) {
    val binding = slots.bindings[slot]
    val rowIndex = binding.intValue
    rows.getOrNull(rowIndex)?.let { row ->
        Column(Modifier.fillMaxWidth().height(CardTopInRow + MarqueeDims.PosterHeight + CardCaptionGap + CardCaptionHeight)
            .graphicsLayer {
                translationY = positions[binding.intValue] - positions[HomeController.column.firstVisibleItemIndex.coerceIn(rows.indices)]
            }.traceMeasure("Marquee:HomeRow:measure")) {
            RowHeader(row.title)
            Spacer(Modifier.height(MarqueeDims.RowTitleGap - MarqueeDims.FocusOuterRingWidth))
            HomeRowItems(row, rowIndex, firstFocus)
        }
    }
}

@Composable
private fun HomeRowItems(row: HomeRowUi, rowIndex: Int, firstFocus: FocusRequester) {
    NavWorkTrace.composition("HomeRowItems")
    val isFirst = rowIndex == 0
    val scope = rememberCoroutineScope()
    val nav = LocalNavigator.current
    val state = HomeController.rowState(row.key)
    val visible = if (row.wide) 5 else 7
    val slots = remember(row) { androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { LayerSlots(visible + 4, state.firstVisibleItemIndex - 2) } }
    val pitch = with(LocalDensity.current) { ((if (row.wide) MarqueeDims.LandscapeWidth else MarqueeDims.PosterWidth) + MarqueeDims.CardGap).toPx() }
    val left = with(LocalDensity.current) { MarqueeDims.SafeH.toPx() }
    DisposableEffect(slots, row.key) {
        val reveal: (Int) -> Unit = { i -> slots.ensure((i - 2).coerceAtLeast(0), minOf(row.items.lastIndex, i + visible + 1)) }
        HomeController.revealCards[row.key] = reveal
        onDispose { if (HomeController.revealCards[row.key] === reveal) HomeController.revealCards.remove(row.key) }
    }
    val needed = remember(row, isFirst) { if (isFirst) row.items.take(if (row.wide) 4 else 6).map { it.id }.toSet() else emptySet() }
    if (isFirst) HomePerf.firstRowNeeds(needed)
    Box(Modifier.fillMaxWidth().height((if (row.wide) MarqueeDims.LandscapeHeight else MarqueeDims.PosterHeight) + CardCaptionGap + CardCaptionHeight + MarqueeDims.FocusOuterRingWidth)
        .padding(top = MarqueeDims.FocusOuterRingWidth).traceMeasure("Marquee:HomeRail:measure")) {
        repeat(visible + 4) { slot ->
            HomeCardSlot(row, rowIndex, firstFocus, slots, slot, needed, pitch, left, scope, nav)
        }
    }
}

@Composable
private fun HomeCardSlot(row: HomeRowUi, rowIndex: Int, firstFocus: FocusRequester, slots: LayerSlots, slot: Int,
    needed: Set<String>, pitch: Float, left: Float, scope: kotlinx.coroutines.CoroutineScope,
    nav: com.mav.marquee.ui.nav.Navigator) {
    val isFirst = rowIndex == 0
    val state = HomeController.rowState(row.key)
    val binding = slots.bindings[slot]
    val index = binding.intValue
    val height = (if (row.wide) MarqueeDims.LandscapeHeight else MarqueeDims.PosterHeight) + CardCaptionGap + CardCaptionHeight
    val width = if (row.wide) MarqueeDims.LandscapeWidth else MarqueeDims.PosterWidth
    Box(Modifier.size(width, height).traceDraw("Marquee:HomeCard:draw").graphicsLayer {
        translationX = left + (binding.intValue - state.firstVisibleItemIndex) * pitch
    }) {
    // A physical slot keeps its nodes when rebound. Image/caption effects already key
    // themselves by content; an item key here tears down the retained subtree.
    row.items.getOrNull(index)?.let { item ->
                    val fr = if (isFirst && index == 0) firstFocus else remember { FocusRequester() }
                    DisposableEffect(row.key, item.id, fr) {
                        HomeController.attachFocus(row.key, item.id, fr)
                        onDispose { HomeController.detachFocus(row.key, item.id, fr) }
                    }
                    LaunchedEffect(row.key, item.id) {
                        if (!HomeController.restoreHero && HomeController.restore == row.key to item.id) runCatching { fr.requestFocus() }
                    }
                    val onFocused: () -> Unit = remember(row, rowIndex, index, item, scope) { {
                        val keyStart = NavWorkTrace.keyStart()
                        HomeController.physicalFocusRow = rowIndex
                        if (HomeController.restore != row.key to item.id || HomeController.context.value?.id != item.id) {
                            HomeController.collapsed.value = rowIndex > 0
                            HomeController.onFocus(item)
                            HomeController.focusRow(rowIndex, scope)
                            HomeController.focusCard(row.key, index, scope)
                            HomeController.restore = row.key to item.id
                            HomeController.restoreHero = false
                        }
                        HomeController.prefetchFocused(row, index)
                        NavWorkTrace.keyEnd(keyStart)
                    } }
                    val onClick: () -> Unit = remember(row.key, item, nav) { { HomeController.restore = row.key to item.id; HomeController.restoreHero = false; nav.open(item) } }
                    val drawn: (() -> Unit)? = remember(item.id, needed) { if (item.id in needed) ({ HomePerf.cardDrawn(item.id) }) else null }
                    val m = Modifier.testTag("home/${row.key}/${item.id}").focusRequester(fr).onPlaced {
                        if (!HomeController.restoreHero && HomeController.restore == row.key to item.id)
                            runCatching { fr.requestFocus() }
                    }
                    if (com.mav.marquee.ui.components.CardCaptionLayouts.enabled)
                        NativeCatalogCard(item, row.wide, m, onClick, onFocused, drawn)
                    else if (row.wide) LandscapeCard(item, onClick, m, onFocused = onFocused, onImageDrawn = drawn)
                    else PosterCard(item, onClick, m, onFocused = onFocused, onImageDrawn = drawn)
    }

    }
}

// ------------------------------------------------------------------------------------------- top nav

@Composable
private fun TopNav(modifier: Modifier, navFocus: FocusRequester) {
    NavWorkTrace.composition("TopNav")
    val nav = LocalNavigator.current
    val views = HomeController.snapshot.value?.views.orEmpty()
    Box(modifier.padding(horizontal = MarqueeDims.SafeH)) {
        Avatar(Modifier.align(Alignment.CenterStart).restoreControl("avatar")) { nav.push(Route.Settings) }
        Row(
            Modifier.align(Alignment.Center).background(Color(0xCC1A1918), MarqueeDims.ShapePill).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            NavPill("Home", "nav_home", selected = true, modifier = Modifier.focusRequester(navFocus)) { nav.home() }
            views.firstOrNull { it.collectionType == "movies" }?.let { v -> NavPill("All Movies", "nav_movies") { nav.push(Route.Library(v.id, v.name, v.collectionType)) } }
            views.firstOrNull { it.collectionType == "tvshows" }?.let { v -> NavPill("All Shows", "nav_shows") { nav.push(Route.Library(v.id, v.name, v.collectionType)) } }
            NavIcon(Glyph.Search, "nav_search") { nav.push(Route.Search) }
        }
    }
}

@Composable
private fun NavPill(text: String, key: String, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val anim = remember { FocusAnim() }
    val rest = if (selected) Color(0x33FFFFFF) else Color.Transparent
    val fg = { lerpColor(if (selected) MarqueeColors.TextPrimary else MarqueeColors.TextSecondary, Color.Black, anim.value.value) }
    Box(modifier.restoreControl(key).height(30.dp).focusFill(anim, rest,
        onFocused = { HomeController.onControlFocus(key) }, onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = MarqueeType.Label, color = fg)
    }
}

@Composable
private fun NavIcon(glyph: Glyph, key: String, onClick: () -> Unit) {
    val anim = remember { FocusAnim() }
    val fg = { lerpColor(MarqueeColors.TextSecondary, Color.Black, anim.value.value) }
    Box(Modifier.restoreControl(key).size(30.dp).focusFill(anim, Color.Transparent,
        onFocused = { HomeController.onControlFocus(key) }, onClick = onClick), contentAlignment = Alignment.Center) {
        GlyphIcon(glyph, fg, Modifier.size(16.dp))
    }
}

@Composable
private fun Avatar(modifier: Modifier, onClick: () -> Unit) {
    val u = HomeController.user.value
    val anim = remember { FocusAnim() }
    Box(modifier.size(36.dp).focusFill(anim, MarqueeColors.SurfaceHigh,
        onFocused = { HomeController.onControlFocus("avatar") }, onClick = onClick).padding(2.dp).clip(CircleShape),
        contentAlignment = Alignment.Center) {
        val url = u?.let { ImageUrls.user(it.id, it.primaryImageTag, 64) }
        if (url != null) RemoteImage(url, Modifier.fillMaxSize())
        else BasicText((u?.name ?: Session.userName).take(1).uppercase(), style = MarqueeType.Label,
            color = { lerpColor(MarqueeColors.TextPrimary, Color.Black, anim.value.value) })
    }
}

@Composable
private fun Modifier.restoreControl(key: String): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (HomeController.restoreHero && HomeController.restoreControl == key) runCatching { requester.requestFocus() }
    }
    return focusRequester(requester)
}

@OptIn(ExperimentalFoundationApi::class)
private object NoVerticalBringIntoView : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

/** Text follows focus in the same frame; list geometry never reads the item. */
@Composable
private fun ContextStrip(modifier: Modifier) {
    NavWorkTrace.composition("ContextStrip")
    if (!HomeController.collapsed.value) return
    Box(modifier.height(100.dp).graphicsLayer().testTag("home-context").traceMeasure("Marquee:Context:measure")
        .traceDraw("Marquee:Context:draw").drawBehind {
            val item = HomeController.context.value ?: HomeController.hero.value ?: return@drawBehind
            val lines = ContextTextLayouts.get(item)
            var y = 0f
            for (layout in lines.layouts) {
                drawText(layout, topLeft = Offset(0f, y))
                // BasicText's measured height includes glyph-dependent font padding.
                y += layout.size.height + 8.dp.toPx()
            }
        }) {
        Column {
            ContextTextLine(0)
            Spacer(Modifier.height(8.dp))
            ContextTextLine(1)
            Spacer(Modifier.height(8.dp))
            ContextTextLine(2)
        }
    }
}

/** A static dimmed worker-baked texture; focus dwell is handled by HomeController's 350ms image dwell. */
@Composable
private fun ContextBackdrop(modifier: Modifier) {
    NavWorkTrace.composition("ContextBackdrop")
    val bitmap = HomeController.contextArt.value
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Spacer(modifier.drawWithCache {
        onDrawBehind {
            // The previous bitmap may stay cached during held travel, but must never label a
            // different stable focus item. Missing/replacement art leaves the canvas visible.
            if (HomeController.contextArtItemId.value != HomeController.context.value?.id) return@onDrawBehind
            image?.let { drawFitted(it, ImageFit.Fill) }
        }
    })
}
