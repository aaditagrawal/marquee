package com.mav.marquee.ui.detail

import com.mav.marquee.ui.library.kit.snapNestedListFocus
import com.mav.marquee.ui.library.kit.instantDpad
import com.mav.marquee.ui.library.kit.onInstantPreviewKeyEvent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import android.view.KeyEvent as AKeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import com.mav.marquee.image.HeroArt
import com.mav.marquee.image.ImageFit
import com.mav.marquee.image.drawFitted
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mav.marquee.data.ImageUrls
import com.mav.marquee.data.Item
import com.mav.marquee.ui.library.kit.BadgeRow
import com.mav.marquee.ui.library.kit.KText
import com.mav.marquee.ui.library.kit.KitIcons
import com.mav.marquee.ui.library.kit.LandscapeCard
import com.mav.marquee.ui.library.kit.NetImage
import com.mav.marquee.ui.library.kit.OptionSheet
import com.mav.marquee.ui.library.kit.PersonCard
import com.mav.marquee.ui.library.kit.PivotSpec
import com.mav.marquee.ui.library.kit.ScreenScroll
import com.mav.marquee.ui.library.kit.ScreenCaption
import com.mav.marquee.ui.library.kit.ScreenItems
import com.mav.marquee.ui.library.kit.Player
import com.mav.marquee.ui.library.kit.PosterCard
import com.mav.marquee.ui.library.kit.SkeletonBox
import com.mav.marquee.ui.library.kit.TvButton
import com.mav.marquee.ui.library.kit.TvChip
import com.mav.marquee.ui.library.kit.TvIconButton
import com.mav.marquee.ui.library.kit.WatchedBadges
import com.mav.marquee.ui.library.kit.backdropBlurHash
import com.mav.marquee.ui.library.kit.cardOverlays
import com.mav.marquee.ui.library.kit.traceScreenMeasure
import com.mav.marquee.ui.library.kit.endsAt
import com.mav.marquee.ui.library.kit.formatRuntime
import com.mav.marquee.ui.library.kit.landscapeBlurHash
import com.mav.marquee.ui.library.kit.mediaBadges
import com.mav.marquee.ui.library.kit.px
import com.mav.marquee.ui.library.kit.restorable
import com.mav.marquee.ui.library.kit.sourceLabel
import com.mav.marquee.ui.library.kit.streamLabel
import com.mav.marquee.ui.components.LayerRail
import com.mav.marquee.ui.components.LayerSections
import androidx.compose.ui.graphics.graphicsLayer
import com.mav.marquee.ui.components.focusCard
import com.mav.marquee.ui.library.kit.yearRange
import com.mav.marquee.ui.nav.LocalNavigator
import com.mav.marquee.ui.nav.Navigator
import com.mav.marquee.ui.nav.Route
import com.mav.marquee.ui.theme.MarqueeColors
import com.mav.marquee.ui.theme.MarqueeDims
import com.mav.marquee.ui.theme.MarqueeType
import kotlinx.coroutines.launch
import java.util.Locale

private enum class Sheet { None, Synopsis, Version, Audio, Subtitle, Trailer }

/**
 * Detail page for Movie / Series (seasons + episodes) / Season (opens its series) / Episode / BoxSet / Playlist /
 * MusicAlbum. Launches PlayerActivity itself (docs/architecture/PLAYER_CONTRACT.md + docs/architecture/PREFS.md extras).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(itemId: String) {
    val ctx = LocalContext.current
    val nav = LocalNavigator.current
    val prefetchEnabled = remember(ctx) {
        (ctx as? android.app.Activity)?.intent?.getBooleanExtra("lazyPrefetch", true) ?: true
    }
    val model = remember(itemId, prefetchEnabled) { DetailModel.get(itemId, prefetchEnabled) }
    LaunchedEffect(model) { android.util.Log.i("Marquee", "MARQUEE_DETAIL_PREFETCH enabled=$prefetchEnabled") }
    var sheet by remember { mutableStateOf(Sheet.None) }
    val density = LocalDensity.current
    val colSpec = remember {
        object : BringIntoViewSpec {
            // Section groups own vertical movement. Per-card requests otherwise fight the section scroll.
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
        }
    }

    LaunchedEffect(model) { model.load(ctx) }
    LaunchedEffect(model.mutationError) {
        model.mutationError?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show() }
    }
    var resumedOnce by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.playPending = false; if (resumedOnce) model.refresh(ctx); resumedOnce = true }

    if (com.mav.marquee.BuildConfig.DEBUG) com.mav.marquee.ui.library.kit.ScreenStateMarker("detail", when {
        model.mutationBusy -> "mutation"
        model.playPending -> "play"
        model.loading -> if (model.item == null) "skeleton" else "seeded"
        model.error != null -> "error"
        else -> "content"
    })
    Box(Modifier.fillMaxSize().instantDpad().background(MarqueeColors.Background)) {
        val item = model.item
        Backdrop(item, model.list)
        when {
            item == null && model.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    KText(model.error!!, MarqueeType.Title, color = MarqueeColors.TextSecondary)
                    Spacer(Modifier.height(16.dp))
                    TvButton("Retry", { model.load(ctx, force = true) }, primary = true, busy = model.loading, modifier = Modifier.restorable(model.focus, "retry"))
                }
                LaunchedEffect(Unit) { model.focus.focus("retry") }
            }
            item == null -> HeroSkeleton()
            else -> CompositionLocalProvider(LocalBringIntoViewSpec provides colSpec) {
                val people = remember(item.people) { ScreenItems.copyOf(item.people.orEmpty().filter { it.type in PERSON_TYPES }) }
                val sectionContent: @Composable (String) -> Unit = { section ->
                    when (section) {
                        "hero" -> Hero(model, item, onSheet = { sheet = it })
                        "seasons_loading" -> SeasonsLoading(model, ctx)
                        "seasons" -> SeasonTabs(model)
                        "episodes" -> EpisodesRow(model)
                        "children" -> if (item.type == "MusicAlbum") TrackList(model)
                            else PosterRow(if (item.type == "BoxSet") "In this collection" else "Items", remember(model.children) { ScreenItems.copyOf(model.children) }, model.childrenRow, model, "child")
                        "cast" -> CastRow(model, people)
                        "similar" -> PosterRow("More like this", remember(model.similar) { ScreenItems.copyOf(model.similar) }, model.similarRow, model, "sim")
                        "extras" -> ExtrasRow(model)
                    }
                }
                val sections = detailSections(model)
                if (item.type == "MusicAlbum") {
                    // Tracks can exceed a screen inside one section; their existing nested
                    // line-scrolling adapter requires an attached list with visible-item bounds.
                    LazyColumn(Modifier.fillMaxSize(), state = model.list, contentPadding = PaddingValues(bottom = 48.dp)) {
                        itemsIndexed(sections, key = { _, key -> key }) { _, key -> sectionContent(key) }
                    }
                } else LayerSections(model.list, sections, sectionContent)
            }
        }

        LaunchedEffect(sheet) { if (sheet == Sheet.Synopsis) model.loadSynopsisTracks() }
        val src = model.source
        when (sheet) {
            Sheet.None -> Unit
            Sheet.Synopsis -> if (item != null) SynopsisSheet(item, model.source ?: model.synopsisSource, onDismiss = {
                sheet = Sheet.None
                model.focus.focus("more")
            })
            Sheet.Version -> {
                val sources = item?.mediaSources.orEmpty()
                OptionSheet("Version", sources.mapIndexed { i, s -> sourceLabel(s, i) }, model.sourceIndex,
                    onSelect = { model.sourceIndex = it; model.applyDefaults(ctx) }, onDismiss = { sheet = Sheet.None; model.focus.arm("play") })
            }
            Sheet.Audio -> {
                val audio = src?.mediaStreams.orEmpty().filter { it.type == "Audio" }
                OptionSheet("Audio", audio.map(::streamLabel), audio.indexOfFirst { it.index == model.audioIndex },
                    onSelect = { model.audioIndex = audio[it].index }, onDismiss = { sheet = Sheet.None; model.focus.arm("play") })
            }
            Sheet.Subtitle -> {
                val subs = src?.mediaStreams.orEmpty().filter { it.type == "Subtitle" }
                OptionSheet("Subtitles", listOf("Off") + subs.map(::streamLabel),
                    if (model.subtitleIndex == -1 || model.subtitleIndex == null) 0 else subs.indexOfFirst { it.index == model.subtitleIndex } + 1,
                    onSelect = { model.subtitleIndex = if (it == 0) -1 else subs[it - 1].index }, onDismiss = { sheet = Sheet.None; model.focus.arm("play") })
            }
            Sheet.Trailer -> {
                val local = model.trailers
                val remote = item?.remoteTrailers.orEmpty().filter { !it.url.isNullOrBlank() }
                OptionSheet("Trailers", local.map { it.name } + remote.map { it.name ?: "Trailer" }, -1,
                    subtitles = local.map { "Local" } + remote.map { "YouTube" },
                    onSelect = { i -> if (i < local.size) Player.play(ctx, local[i].id) else Player.openUrl(ctx, remote[i - local.size].url!!) },
                    onDismiss = { sheet = Sheet.None; model.focus.arm("play") })
            }
        }
    }

    DisposableEffect(model) { onDispose { DetailPrewarm.leave() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { DetailPrewarm.leave() }

    // Initial focus = Play; after Back = whatever was focused last.
    val warmItem = model.playableItem
    LaunchedEffect(warmItem?.id, model.sourceIndex, model.audioIndex, model.subtitleIndex) { warmItem?.let { DetailPrewarm.prewarm(ctx, model) } }
    LaunchedEffect(model.item != null) {
        model.item?.let { model.focus.arm(if (it.isPlayable || it.type == "Series") "play" else "watched") }
    }
}

private val PERSON_TYPES = setOf("Actor", "Director", "Writer", "GuestStar", "Producer", "Composer")

// ---------------------------------------------------------------------------------------------- backdrop

@Composable
private fun Backdrop(item: Item?, list: LazyListState) {
    val url = item?.let { ImageUrls.backdrop(it, 1280, 720) }
    val art = remember(url) { mutableStateOf(url?.let { HeroArt.cached(it)?.asImageBitmap() }) }
    LaunchedEffect(url) {
        if (url != null) art.value = runCatching { HeroArt.load(url)?.asImageBitmap() }.getOrNull()
    }
    val headerPx = with(LocalDensity.current) { (if (item?.type == "Series") 352.dp else 540.dp).toPx() }
    // The full-resolution hero is already scrimmed on a worker, matching Home.
    // Bitmap state and scroll dimming are read only in draw; focus never redraws the hero.
    Box(Modifier.fillMaxSize().graphicsLayer()) {
        if (art.value == null && url != null) NetImage(url, Modifier.fillMaxSize(),
            blurHash = item?.let(::backdropBlurHash), placeholder = MarqueeColors.Background)
        Spacer(Modifier.fillMaxSize().drawBehind {
            val bitmap = art.value
            if (list.firstVisibleItemIndex > 1 || (list.firstVisibleItemIndex == 1 && list.firstVisibleItemScrollOffset >= 0)) {
                drawRect(MarqueeColors.Background)
            } else {
                if (bitmap != null) drawFitted(bitmap, ImageFit.Fill, 1f)
                else { drawRect(MarqueeColors.HeroScrimHorizontal); drawRect(MarqueeColors.HeroScrimVertical) }
                val offset = list.firstVisibleItemScrollOffset + if (list.firstVisibleItemIndex == 1) headerPx else 0f
                val dim = (offset / 600f).coerceIn(0f, 0.85f)
                if (dim > 0f) drawRect(MarqueeColors.Background.copy(alpha = dim))
            }
        })
    }
}

@Composable
private fun HeroSkeleton() {
    Column(Modifier.padding(start = MarqueeDims.SafeH, top = 150.dp)) {
        SkeletonBox(Modifier.size(320.dp, 56.dp))
        Spacer(Modifier.height(16.dp))
        SkeletonBox(Modifier.size(260.dp, 16.dp))
        Spacer(Modifier.height(16.dp))
        repeat(4) { SkeletonBox(Modifier.size(440.dp, 14.dp)); Spacer(Modifier.height(10.dp)) }
        Spacer(Modifier.height(16.dp))
        Row { SkeletonBox(Modifier.size(120.dp, 44.dp)); Spacer(Modifier.width(12.dp)); SkeletonBox(Modifier.size(44.dp, 44.dp), CircleShape) }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), color = MarqueeColors.Sky, strokeWidth = 2.dp)
            KText("Loading title…", MarqueeType.Meta, color = MarqueeColors.TextSecondary)
        }
    }
}

// ---------------------------------------------------------------------------------------------- hero

@Composable
private fun Hero(model: DetailModel, item: Item, onSheet: (Sheet) -> Unit) {
    val density = LocalDensity.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Box(
        Modifier.fillMaxWidth().height(if (item.type == "Series") (352.dp + if (model.error != null) 48.dp else 0.dp) else 540.dp)
            .drawWithContent {
                // Keep remnants of the action row out of the season/episode reading area.
                val offset = model.list.firstVisibleItemScrollOffset
                if (model.headerFocused || (model.list.firstVisibleItemIndex == 0 && offset == 0)) drawContent()
            }
            .onFocusChanged {
                model.headerFocused = it.hasFocus
                if (it.hasFocus) { model.sectionScroll.cancel(); model.list.requestScrollToItem(0) }
            }.focusGroup()
            .onInstantPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key.nativeKeyCode == AKeyEvent.KEYCODE_DPAD_DOWN && item.type == "Series" && model.currentSeason != null) {
                    if (model.seasons.size == 1) focusEpisodes(model)
                    else model.focus.focus("season_" + model.currentSeason!!.id)
                    model.sectionScroll.row(scope, model.list, 1, -with(density) { 48.dp.toPx() }.toInt())
                    true
                } else false
            },
    ) {
        // A fixed first-screen section: even four overview lines and all track controls fit at 540 dp.
        Column(Modifier.padding(start = MarqueeDims.SafeH, top = MarqueeDims.SafeV).width(864.dp)) {
            TitleArt(item)
            Spacer(Modifier.height(8.dp))
            MetaLine(model, item)
            val badges = mediaBadges(model.source, item.mediaStreams)
            if (badges.isNotEmpty()) { Spacer(Modifier.height(8.dp)); BadgeRow(badges) }
            item.overview?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(16.dp))
                KText(it, MarqueeType.BodyLarge, Modifier.width(480.dp), color = MarqueeColors.TextSecondary, maxLines = 4)
            }
            Spacer(Modifier.height(16.dp))
            Actions(model, item, onSheet)
            if (model.error != null) {
                Spacer(Modifier.height(8.dp))
                TvButton("Server unreachable · Retry", { model.load(ctx, force = true) }, busy = model.loading)
            }
            if (item.isPlayable && item.type != "Audio") TrackChips(model, item, onSheet)
        }
    }
}

@Composable
private fun TitleArt(item: Item) {
    val logo = ImageUrls.logo(item, com.mav.marquee.ui.components.CardPx.LogoW, com.mav.marquee.ui.components.CardPx.LogoH)
    val title = item.seriesName.takeIf { item.type == "Episode" } ?: item.name
    // Reserve the same bounded title slot before/after logo loading, so focus never triggers reflow.
    Column(Modifier.size(420.dp, 96.dp), verticalArrangement = Arrangement.Bottom) {
        Box(Modifier.fillMaxWidth().height(if (item.type == "Episode") 64.dp else 96.dp)) {
            val ready = remember(logo) { mutableStateOf(logo != null && com.mav.marquee.image.MarqueeImages.cached(logo) != null) }
            KText(title, if (title.length > 32) MarqueeType.Headline else MarqueeType.Display,
                Modifier.align(Alignment.BottomStart).drawWithContent { if (!ready.value) drawContent() }, maxLines = 2)
            if (logo != null) com.mav.marquee.image.RemoteImage(logo, Modifier.fillMaxSize(), opaque = false,
                fit = ImageFit.FitStart, placeholderColor = Color.Transparent,
                onDrawn = { if (!ready.value) ready.value = true })
        }
        if (item.type == "Episode") {
            Spacer(Modifier.height(8.dp))
            KText(listOfNotNull(item.episodeLabel, item.name).joinToString("  ·  "), MarqueeType.Title, maxLines = 1)
        }
    }
}

@Composable
private fun MetaLine(model: DetailModel, item: Item) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val parts = ArrayList<String>(4)
        yearRange(item)?.let { parts += it }
        if (item.type == "Series") item.childCount?.let { parts += if (it == 1) "1 Season" else "$it Seasons" }
        if (item.type == "MusicAlbum") model.children.size.takeIf { it > 0 }?.let { parts += "$it tracks" }
        formatRuntime(item.runtimeMs)?.takeIf { item.type != "Series" }?.let { parts += it }
        KText(parts.joinToString("  ·  "), MarqueeType.Meta, color = MarqueeColors.TextSecondary, maxLines = 1)
        item.officialRating?.let { Spacer(Modifier.width(8.dp)); com.mav.marquee.ui.library.kit.OutlineBadge(it) }
        item.communityRating?.let {
            Spacer(Modifier.width(8.dp))
            Image(KitIcons.Star, null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(MarqueeColors.Yellow))
            Spacer(Modifier.width(4.dp))
            KText(String.format(Locale.US, "%.1f", it), MarqueeType.Meta, color = MarqueeColors.TextPrimary)
        }
        item.criticRating?.let {
            Spacer(Modifier.width(8.dp))
            KText("$it%", MarqueeType.Meta, color = if (it >= 60) MarqueeColors.Amber else MarqueeColors.TextTertiary)
        }
        if (item.isPlayable && item.runtimeMs > 0) {
            val remaining = item.runtimeMs - item.resumeMs
            endsAt(remaining)?.let { Spacer(Modifier.width(8.dp)); KText(it, MarqueeType.Meta, color = MarqueeColors.TextTertiary, maxLines = 1) }
        }
    }
}

@Composable
private fun Actions(model: DetailModel, item: Item, onSheet: (Sheet) -> Unit) {
    val ctx = LocalContext.current
    val nav = LocalNavigator.current
    val ud = item.userData
    val scope = rememberCoroutineScope()
    val playFocused = remember { booleanArrayOf(false) }
    LaunchedEffect(model.playableItem?.id, model.sourceIndex, model.audioIndex, model.subtitleIndex) {
        if (playFocused[0]) DetailPrewarm.prewarm(ctx, model, true)
    }
    fun launchPlayer(action: () -> Unit) {
        if (model.playPending) return
        model.playPending = true
        DetailPrewarm.starting()
        scope.launch {
            androidx.compose.runtime.withFrameNanos { }
            try { action() }
            catch (_: Exception) { DetailPrewarm.failed(); model.playPending = false; model.mutationError = "Couldn't start playback. Try again." }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            item.isPlayable -> {
                val resume = item.resumeMs > 0 && ud?.played != true
                TvButton(if (resume) "Resume " + (formatRuntime(item.resumeMs) ?: "") else "Play", primary = true, icon = KitIcons.Play,
                    busy = model.playPending, onClick = { launchPlayer { playItem(ctx, model, item, if (resume) item.resumeMs else 0) } },
                    modifier = Modifier.restorable(model.focus, "play").onFocusChanged { playFocused[0] = it.hasFocus; DetailPrewarm.prewarm(ctx, model, it.hasFocus) })
                if (resume) TvButton("Start over", icon = KitIcons.Replay, busy = model.playPending, onClick = { launchPlayer { playItem(ctx, model, item, 0) } },
                    modifier = Modifier.restorable(model.focus, "restart"))
            }
            item.type == "Series" -> {
                val nu = model.nextEpisode
                val label = when {
                    nu == null -> "Play"
                    nu.resumeMs > 0 -> "Resume ${nu.episodeLabel ?: ""}"
                    else -> "Play ${nu.episodeLabel ?: ""}"
                }
                TvButton(label.trim(), busy = model.playPending || nu == null && model.seriesLoading, primary = true, icon = KitIcons.Play, modifier = Modifier.restorable(model.focus, "play").onFocusChanged { playFocused[0] = it.hasFocus; DetailPrewarm.prewarm(ctx, model, it.hasFocus) }, onClick = {
                    val ep = nu ?: model.currentEpisodes?.firstOrNull()
                    if (ep != null) launchPlayer { Player.play(ctx, ep.id, if (ep.userData?.played == true) 0 else ep.resumeMs) }
                })
                if (nu != null && nu.resumeMs > 0) TvButton("Start over", icon = KitIcons.Replay,
                    busy = model.playPending, onClick = { launchPlayer { Player.play(ctx, nu.id, 0) } }, modifier = Modifier.restorable(model.focus, "restart"))
            }
            item.type == "MusicAlbum" || item.type == "Playlist" || item.type == "BoxSet" -> {
                val first = model.children.firstOrNull { it.isPlayable }
                if (first != null) TvButton("Play", primary = true, icon = KitIcons.Play, modifier = Modifier.restorable(model.focus, "play"),
                    busy = model.playPending, onClick = { launchPlayer { Player.play(ctx, first.id, first.resumeMs) } })
                else Spacer(Modifier.size(1.dp).restorable(model.focus, "play").onFocusChanged { playFocused[0] = it.hasFocus; DetailPrewarm.prewarm(ctx, model, it.hasFocus) })
            }
        }
        if (!item.overview.isNullOrBlank()) TvButton("More", { onSheet(Sheet.Synopsis) },
            modifier = Modifier.restorable(model.focus, "more"))
        if (model.trailers.isNotEmpty() || !item.remoteTrailers.isNullOrEmpty()) {
            TvButton("Trailer", icon = KitIcons.Film, modifier = Modifier.restorable(model.focus, "trailer"), onClick = {
                val local = model.trailers
                val remote = item.remoteTrailers.orEmpty().filter { !it.url.isNullOrBlank() }
                when {
                    local.size == 1 && remote.isEmpty() -> Player.play(ctx, local[0].id)
                    local.isEmpty() && remote.size == 1 -> Player.openUrl(ctx, remote[0].url!!)
                    else -> onSheet(Sheet.Trailer)
                }
            })
        }
        if (item.type != "Person") {
            TvIconButton(KitIcons.Check, { model.togglePlayed() }, tint = if (ud?.played == true) MarqueeColors.Sage else MarqueeColors.TextPrimary,
                contentDescription = "Watched", busy = model.mutationBusy, modifier = Modifier.restorable(model.focus, "watched"))
            TvIconButton(if (ud?.isFavorite == true) KitIcons.Heart else KitIcons.HeartOutline, { model.toggleFavorite() },
                tint = if (ud?.isFavorite == true) MarqueeColors.Red else MarqueeColors.TextPrimary,
                contentDescription = "Favorite", busy = model.mutationBusy, modifier = Modifier.restorable(model.focus, "fav"))
        }
        if (item.type == "Episode" && item.seriesId != null) {
            TvButton("Go to series", onClick = { nav.push(Route.Detail(item.seriesId)) }, modifier = Modifier.restorable(model.focus, "series"))
        }
    }
}

private fun playItem(ctx: Context, model: DetailModel, item: Item, startMs: Long) {
    val src = model.source
    val multi = (item.mediaSources?.size ?: 0) > 1
    Player.play(ctx, item.id, startMs,
        mediaSourceId = if (multi) src?.id else null,
        audioIndex = model.audioIndex,
        subtitleIndex = model.subtitleIndex)
}

@Composable
private fun TrackChips(model: DetailModel, item: Item, onSheet: (Sheet) -> Unit) {
    val src = model.source ?: return
    val audio = src.mediaStreams.filter { it.type == "Audio" }
    val subs = src.mediaStreams.filter { it.type == "Subtitle" }
    val multiSource = (item.mediaSources?.size ?: 0) > 1
    if (!multiSource && audio.size <= 1 && subs.isEmpty()) return
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (multiSource) TvChip("Version: " + sourceLabel(src, model.sourceIndex).take(28), { onSheet(Sheet.Version) },
            modifier = Modifier.restorable(model.focus, "chip_ver"))
        if (audio.isNotEmpty()) {
            val a = audio.firstOrNull { it.index == model.audioIndex } ?: audio.first()
            TvChip("Audio: " + shortStream(a), { onSheet(Sheet.Audio) }, modifier = Modifier.restorable(model.focus, "chip_audio"))
        }
        if (subs.isNotEmpty()) {
            val s = subs.firstOrNull { it.index == model.subtitleIndex }
            TvChip("Subtitles: " + (s?.let(::shortStream) ?: "Off"), { onSheet(Sheet.Subtitle) }, modifier = Modifier.restorable(model.focus, "chip_sub"))
        }
    }
}

private fun shortStream(s: com.mav.marquee.data.MediaStream): String {
    val lang = s.language?.let { l -> runCatching { Locale.forLanguageTag(l).getDisplayLanguage(Locale.ENGLISH) }.getOrNull()?.takeIf { it.isNotBlank() && it != l } ?: l.uppercase() }
    val extra = when (s.type) {
        "Audio" -> listOfNotNull(
            when (s.codec?.lowercase()) { "eac3" -> "DD+"; "ac3" -> "DD"; "truehd" -> "TrueHD"; "dts" -> "DTS"; "aac" -> "AAC"; "flac" -> "FLAC"; "opus" -> "Opus"; else -> s.codec?.uppercase() },
            s.channelLayout?.substringBefore('(') ?: s.channels?.let { "${it}ch" },
        ).joinToString(" ")
        else -> if (isForced(s)) "Forced" else (s.title?.takeIf { it.length <= 14 && !it.equals(lang, true) } ?: "")
    }
    return listOfNotNull(lang ?: s.title, extra.takeIf { it.isNotBlank() }).joinToString(" ").ifBlank { s.displayTitle ?: "Track ${s.index}" }.take(26)
}

// ---------------------------------------------------------------------------------------------- series

@Composable
private fun SeasonsLoading(model: DetailModel, ctx: Context) {
    Column(Modifier.padding(horizontal = MarqueeDims.SafeH, vertical = 24.dp)) {
        KText("Episodes", MarqueeType.RowTitle)
        Spacer(Modifier.height(12.dp))
        if (model.seriesLoading) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.width(208.dp), color = MarqueeColors.Sky, trackColor = MarqueeColors.SurfaceHigh)
            Spacer(Modifier.height(12.dp)); KText("Loading seasons…", MarqueeType.Meta, color = MarqueeColors.TextSecondary)
        } else {
            KText(model.episodesError ?: "No episodes listed.", MarqueeType.Body, color = MarqueeColors.TextSecondary)
            Spacer(Modifier.height(16.dp)); TvButton("Retry", { model.load(ctx, force = true) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeasonTabs(model: DetailModel) {
    com.mav.marquee.ui.components.NavWorkTrace.composition("SeasonTabs")
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val spec = remember(density) { PivotSpec(with(density) { MarqueeDims.SafeH.toPx() }, manual = true) }
    val rowScroll = remember { ScreenScroll() }
    Column(Modifier.sectionFocus(model, "seasons").padding(bottom = 24.dp)
        .onInstantPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) false else when (e.key.nativeKeyCode) {
                AKeyEvent.KEYCODE_DPAD_UP -> { model.focus.focus("play"); model.sectionScroll.row(scope, model.list, 0); true }
                AKeyEvent.KEYCODE_DPAD_DOWN -> {
                    val snap = model.list.isScrollInProgress
                    val eps = model.currentEpisodes
                    if (eps == null) {
                        model.focus.focus(if (model.episodesError == null) "ep_loading_0" else "ep_retry")
                        model.sectionScroll.row(scope, model.list, 1, -with(density) { 48.dp.toPx() }.toInt(), snap = snap)
                        model.episodeRow.requestScrollToItem(0)
                        true
                    } else if (eps.isEmpty()) false else {
                        val target = nextEpisodeIndex(model, eps)
                        model.focus.focus("ep_" + eps[target].id)
                        model.sectionScroll.row(scope, model.list, 1, -with(density) { 48.dp.toPx() }.toInt(), snap = snap)
                        // Next Up is prepositioned when episodes arrive. A distant pending target
                        // requests placement immediately; it never waits for a scroll animation.
                        if (model.focus.pending != null) model.episodeRow.requestScrollToItem(target)
                        true
                    }
                }
                else -> false
            }
        }) {
        KText("Seasons", MarqueeType.RowTitle, modifier = Modifier.padding(start = MarqueeDims.SafeH, bottom = MarqueeDims.RowTitleGap))
        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
            LazyRow(state = model.seasonRow,
                contentPadding = PaddingValues(horizontal = MarqueeDims.SafeH, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(MarqueeDims.CardGap)) {
                itemsIndexed(model.seasons, key = { _, s -> s.id }, contentType = { _, _ -> "season" }) { i, s ->
                    TvChip(s.name, { model.selectSeason(i, immediate = true) }, selected = i == model.seasonIndex,
                        modifier = Modifier.testTag("detail/season/${s.id}").restorable(model.focus, "season_" + s.id)
                            .onFocusChanged { if (it.isFocused) { model.selectSeason(i); rowScroll.row(scope, model.seasonRow, i) } })
                }
            }
        }
    }
}

private fun nextEpisodeIndex(model: DetailModel, eps: List<Item>): Int =
    eps.indexOfFirst { it.id == model.nextEpisode?.id }.takeIf { it >= 0 }
        ?: eps.indexOfFirst { it.userData?.played != true }.takeIf { it >= 0 } ?: 0

private fun focusEpisodes(model: DetailModel): Boolean {
    val eps = model.currentEpisodes
    if (eps == null) {
        model.focus.focus(if (model.episodesError == null) "ep_loading_0" else "ep_retry")
        model.episodeRow.requestScrollToItem(0)
    } else {
        if (eps.isEmpty()) return false
        val target = nextEpisodeIndex(model, eps)
        model.focus.focus("ep_" + eps[target].id)
        if (model.focus.pending != null) model.episodeRow.requestScrollToItem(target)
    }
    return true
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodesRow(model: DetailModel) {
    val ctx = LocalContext.current
    com.mav.marquee.ui.components.NavWorkTrace.composition("EpisodesRow")
    val eps = model.currentEpisodes
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val spec = remember(density) { PivotSpec(with(density) { MarqueeDims.SafeH.toPx() }, manual = true) }
    val rowScroll = remember(model.currentSeason?.id) { ScreenScroll() }
    val focused = remember(model.currentSeason?.id) { mutableStateOf<Item?>(null) }
    // Capture before LazyRow removes its focused placeholder and Android chooses a fallback.
    val restoreLoadingFocus = remember(model.currentSeason?.id, eps) {
        model.focus.last?.startsWith("ep_loading_") == true
    }
    LaunchedEffect(model.currentSeason?.id, eps) {
        if (!eps.isNullOrEmpty()) {
            val target = nextEpisodeIndex(model, eps)
            // Place Next Up at the start padding, clamped at the last card/end padding.
            // Finish placement before restoring focus so visibility scrolling cannot race it.
            rowScroll.cancel()
            model.episodeRow.requestScrollToItem(target)
            if (restoreLoadingFocus) model.focus.focus("ep_" + eps[target].id)
            focused.value = eps[target]
        }
    }
    val singleSeason = model.seasons.size == 1
    val sections = detailSections(model)
    val episodeSection = sections.indexOf("episodes")
    val downIndex = episodeSection + 1
    val downSection = sections.getOrNull(downIndex)
    val sectionOffset = -with(density) { 48.dp.toPx() }.toInt()
    Column(Modifier.sectionFocus(model, if (singleSeason) "episodes" else "seasons").padding(bottom = MarqueeDims.RowGap)
        .onInstantPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key.nativeKeyCode == AKeyEvent.KEYCODE_DPAD_DOWN && downSection != null) {
                focusSection(model, downSection, downIndex, sectionOffset)
            } else if (e.type == KeyEventType.KeyDown && e.key.nativeKeyCode == AKeyEvent.KEYCODE_DPAD_UP) {
                val snap = model.list.isScrollInProgress
                if (singleSeason) {
                    model.focus.focus("play")
                    model.sectionScroll.row(scope, model.list, 0, snap = snap)
                } else {
                    model.focus.focus("season_" + model.currentSeason!!.id)
                    model.sectionScroll.row(scope, model.list, 1, -with(density) { 48.dp.toPx() }.toInt(), snap = snap)
                }
                true
            } else false
        }) {
        KText(if (singleSeason) "Episodes · Season ${model.currentSeason?.indexNumber ?: 1}" else "Episodes",
            MarqueeType.RowTitle, Modifier.padding(start = MarqueeDims.SafeH, bottom = MarqueeDims.RowTitleGap))
        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
            LayerRail(if (eps == null && model.episodesError == null) 4 else eps.orEmpty().size,
                model.episodeRow, MarqueeDims.LandscapeWidth, 159.dp, MarqueeDims.CardGap, MarqueeDims.SafeH,
                modifier = Modifier.padding(vertical = 8.dp), label = "EpisodesRail") { i, slot, reveal ->
                if (eps == null) {
                    Column(slot.size(208.dp, 159.dp).testTag("detail/episode-loading/$i")
                        .restorable(model.focus, "ep_loading_$i")) {
                        SkeletonBox(Modifier.size(208.dp, 117.dp).focusCard(
                            onFocused = reveal, onClick = { model.selectSeason(model.seasonIndex, immediate = true) }))
                        Spacer(Modifier.height(8.dp)); SkeletonBox(Modifier.size(160.dp, 32.dp))
                    }
                } else {
                    val ep = eps[i]
                    EpisodeCard(ep, ep.id == model.nextEpisode?.id,
                        modifier = slot.testTag("detail/episode/${ep.id}").restorable(model.focus, "ep_" + ep.id),
                        onFocused = { focused.value = ep; reveal() },
                        onPlay = { Player.play(ctx, ep.id, if (ep.userData?.played == true) 0 else ep.resumeMs) })
                }
            }
        }

        model.episodesError?.let {
            Column(Modifier.padding(horizontal = MarqueeDims.SafeH, vertical = 8.dp)) {
                KText(it, MarqueeType.Body, color = MarqueeColors.TextSecondary)
                Spacer(Modifier.height(16.dp)); TvButton("Retry", {
                    model.selectSeason(model.seasonIndex, immediate = true)
                    model.focus.focus("ep_loading_0")
                }, Modifier.restorable(model.focus, "ep_retry"))
            }
        }
        // Fixed synopsis slot. Focus moves only change this small text block, never the section height.
        EpisodeSynopsis(focused)
    }
}

@Composable
private fun EpisodeSynopsis(focused: androidx.compose.runtime.State<Item?>) {
    com.mav.marquee.ui.components.NavWorkTrace.composition("EpisodeSynopsis")
    Box(Modifier.graphicsLayer().traceScreenMeasure("Marquee:EpisodeSynopsis:measure").padding(horizontal = MarqueeDims.SafeH).padding(top = 8.dp).size(640.dp, 40.dp)) {
        KText(focused.value?.overview.orEmpty(), MarqueeType.Body, color = MarqueeColors.TextSecondary, maxLines = 2)
    }
}

@Composable
private fun EpisodeCard(ep: Item, nextUp: Boolean, modifier: Modifier, onFocused: () -> Unit, onPlay: () -> Unit) {
    com.mav.marquee.ui.components.NavWorkTrace.composition("EpisodeCard")
    val caption = remember(ep.episodeLabel, ep.runtimeMs) {
        listOfNotNull(ep.episodeLabel, formatRuntime(ep.runtimeMs)).joinToString("  ·  ")
    }
    val url = remember(ep) { ImageUrls.landscape(ep, com.mav.marquee.ui.components.CardPx.LandW, com.mav.marquee.ui.components.CardPx.LandH) }
    Column(modifier.traceScreenMeasure("Marquee:EpisodeCard:measure").size(MarqueeDims.LandscapeWidth, MarqueeDims.LandscapeHeight + 42.dp)) {
        Box(Modifier.size(MarqueeDims.LandscapeWidth, MarqueeDims.LandscapeHeight)
            .focusCard(onFocused = onFocused, onClick = onPlay)) {
            NetImage(url,
                Modifier.fillMaxSize().clip(MarqueeDims.ShapeCard).cardOverlays(ep), blurHash = landscapeBlurHash(ep), fallbackText = ep.name)
            WatchedBadges(ep, Modifier.align(Alignment.TopEnd))
            if (nextUp) Box(Modifier.align(Alignment.BottomStart).padding(8.dp)
                .background(MarqueeColors.Background.copy(alpha = .8f), MarqueeDims.ShapeSmall).padding(horizontal = 8.dp, vertical = 2.dp)) {
                KText("NEXT UP", MarqueeType.Eyebrow)
            }
        }
        Spacer(Modifier.height(8.dp))
        ScreenCaption(caption, ep.name, MarqueeDims.LandscapeWidth)
    }
}

private fun detailSections(model: DetailModel): List<String> = buildList {
    val item = model.item ?: return@buildList
    add("hero")
    if (item.type == "Series") {
        if (model.seasons.isEmpty()) add("seasons_loading") else {
            if (model.seasons.size > 1) add("seasons")
            add("episodes")
        }
    }
    if (model.children.isNotEmpty()) add("children")
    if (item.people.orEmpty().any { it.type in PERSON_TYPES }) add("cast")
    if (model.similar.isNotEmpty()) add("similar")
    if (model.extras.isNotEmpty()) add("extras")
}

/** Groups own vertical positioning; focus searches must not compose distant sections during a key. */
@Composable
private fun Modifier.sectionFocus(model: DetailModel, key: String): Modifier {
    val density = LocalDensity.current
    val sections = detailSections(model)
    val index = sections.indexOf(key)
    val offset = -with(density) { 48.dp.toPx() }.toInt()
    val sectionScope = rememberCoroutineScope()
    val sectionScroll = remember(model) { ScreenScroll() }
    val hasRowNavigation = key == "cast" || (key == "children" && model.item?.type != "MusicAlbum") || key == "similar" || key == "extras"
    return this.onFocusChanged {
        if (it.hasFocus) {
            model.headerFocused = false
            if (index >= 0) sectionScroll.row(sectionScope, model.list, index, offset)
        }
    }.focusGroup().onInstantPreviewKeyEvent { event ->
        if (!hasRowNavigation || event.type != KeyEventType.KeyDown) false else {
            val next = when (event.key.nativeKeyCode) {
                AKeyEvent.KEYCODE_DPAD_UP -> index - 1
                AKeyEvent.KEYCODE_DPAD_DOWN -> index + 1
                else -> -1
            }
            sections.getOrNull(next)?.let { focusSection(model, it, next, offset) } ?: false
        }
    }
}

private fun focusSection(model: DetailModel, section: String, index: Int, offset: Int): Boolean {
    val key = when (section) {
        "hero" -> "play"
        "seasons" -> model.currentSeason?.let { "season_" + it.id }
        "episodes" -> { if (!focusEpisodes(model)) return false; null }
        "cast" -> { model.castRow.requestScrollToItem(0); "cast_0" }
        "similar" -> { model.similarRow.requestScrollToItem(0); model.similar.firstOrNull()?.let { "sim_" + it.id } }
        "extras" -> { model.extrasRow.requestScrollToItem(0); model.extras.firstOrNull()?.let { "extra_" + it.id } }
        "children" -> {
            model.childrenRow.requestScrollToItem(0)
            if (model.item?.type == "MusicAlbum") "track_0" else model.children.firstOrNull()?.let { "child_" + it.id }
        }
        else -> return false
    }
    if (key != null) model.focus.focus(key)
    model.list.requestScrollToItem(index, if (index == 0) 0 else offset)
    return true
}

// ---------------------------------------------------------------------------------------------- rows

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowShell(title: String, state: LazyListState, model: DetailModel, section: String, content: androidx.compose.foundation.lazy.LazyListScope.( (Int) -> Modifier ) -> Unit) {
    com.mav.marquee.ui.components.NavWorkTrace.composition("RowShell")
    val scope = rememberCoroutineScope()
    val rowScroll = remember(state) { ScreenScroll() }
    val density = LocalDensity.current
    val spec = remember(density) { PivotSpec(with(density) { MarqueeDims.SafeH.toPx() }, manual = true) }
    Column(Modifier.sectionFocus(model, section).padding(bottom = MarqueeDims.RowGap)) {
        KText(title, MarqueeType.RowTitle, modifier = Modifier.padding(start = MarqueeDims.SafeH, bottom = MarqueeDims.RowTitleGap))
        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
            LazyRow(state = state, contentPadding = PaddingValues(horizontal = MarqueeDims.SafeH, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(MarqueeDims.CardGap)) {
                content { index -> Modifier.onFocusChanged { if (it.hasFocus) rowScroll.row(scope, state, index) } }
            }
        }
    }
}

@Composable
private fun PosterRow(title: String, items: ScreenItems<Item>, state: LazyListState, model: DetailModel, keyPrefix: String) {
    val nav = LocalNavigator.current
    com.mav.marquee.ui.components.NavWorkTrace.composition("PosterRow")
    RowShell(title, state, model, if (keyPrefix == "sim") "similar" else "children") { scrollFocus ->
        itemsIndexed(items, key = { _, it -> it.id }, contentType = { _, _ -> "poster" }) { i, it ->
            val square = it.type == "MusicAlbum" || it.type == "Audio"
            PosterCard(it, onClick = { nav.open(it) }, aspect = if (square) 1f else 2f / 3f,
                modifier = scrollFocus(i).restorable(model.focus, keyPrefix + "_" + it.id))
        }
    }
}

@Composable
private fun CastRow(model: DetailModel, people: ScreenItems<com.mav.marquee.data.Person>) {
    val nav = LocalNavigator.current
    com.mav.marquee.ui.components.NavWorkTrace.composition("CastRow")
    RowShell("Cast", model.castRow, model, "cast") { scrollFocus ->
        itemsIndexed(people, key = { i, p -> p.id + i }, contentType = { _, _ -> "person" }) { i, p ->
            PersonCard(
                name = p.name,
                role = if (p.type == "Actor" || p.type == "GuestStar") p.role else p.type,
                imageUrl = ImageUrls.person(p.id, p.primaryImageTag, 96.dp.px()),
                blurHash = p.primaryImageTag?.let { t -> p.imageBlurHashes?.get("Primary")?.get(t) },
                onClick = { com.mav.marquee.ui.library.kit.ItemCache.put(Item(p.id, p.name, type = "Person", imageTags = p.primaryImageTag?.let { mapOf("Primary" to it) }, imageBlurHashes = p.imageBlurHashes)); nav.push(Route.Person(p.id)) },
                modifier = scrollFocus(i).restorable(model.focus, "cast_$i"),
            )
        }
    }
}

@Composable
private fun ExtrasRow(model: DetailModel) {
    val ctx = LocalContext.current
    RowShell("Extras", model.extrasRow, model, "extras") { scrollFocus ->
        itemsIndexed(model.extras, key = { _, it -> it.id }, contentType = { _, _ -> "landscape" }) { i, it ->
            LandscapeCard(it, onClick = { Player.play(ctx, it.id) }, eyebrow = formatRuntime(it.runtimeMs),
                modifier = scrollFocus(i).restorable(model.focus, "extra_" + it.id))
        }
    }
}

@Composable
private fun TrackList(model: DetailModel) {
    val ctx = LocalContext.current
    Column(Modifier.sectionFocus(model, "children").padding(horizontal = MarqueeDims.SafeH - 8.dp, vertical = 8.dp)) {
        KText("Tracks", MarqueeType.RowTitle, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
        model.children.forEachIndexed { i, t ->
            com.mav.marquee.ui.library.kit.TvListRow(
                title = listOfNotNull(t.indexNumber?.toString(), t.name).joinToString(".  "),
                value = formatRuntime(t.runtimeMs) ?: "",
                onClick = { Player.play(ctx, t.id) },
                modifier = Modifier.fillMaxWidth().snapNestedListFocus(model.list, "children").restorable(model.focus, "track_$i"),
            )
        }
    }
}
