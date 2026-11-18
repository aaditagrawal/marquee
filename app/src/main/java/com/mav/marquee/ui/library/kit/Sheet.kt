package com.mav.marquee.ui.library.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.mav.marquee.ui.theme.MarqueeColors
import com.mav.marquee.ui.theme.MarqueeDims
import com.mav.marquee.ui.theme.MarqueeType

/** Right-side option sheet (360 dp, SurfaceRaised). Focus is trapped inside; Back dismisses. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OptionSheet(
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    subtitles: List<String?>? = null,
    /** Option focused when the sheet opens; defaults to the selected one. */
    initialFocus: Int = selected,
) {
    BackHandler(onBack = onDismiss)
    val start = initialFocus.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    val fr = remember { FocusRequester() }
    val focus = remember { FocusMemory() }
    val focusedIndex = remember { intArrayOf(start) }
    Box(Modifier.fillMaxSize().instantDpad().background(MarqueeColors.ScrimMedium)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides InstantBringIntoView) {
            LazyColumn(
                Modifier.align(Alignment.CenterEnd).width(360.dp).fillMaxHeight()
                    .background(MarqueeColors.SurfaceRaised)
                    .onInstantPreviewKeyEvent { event ->
                        if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false else {
                            val delta = when (event.key) {
                                androidx.compose.ui.input.key.Key.DirectionUp -> -1
                                androidx.compose.ui.input.key.Key.DirectionDown -> 1
                                androidx.compose.ui.input.key.Key.DirectionLeft, androidx.compose.ui.input.key.Key.DirectionRight -> return@onInstantPreviewKeyEvent true
                                else -> 0
                            }
                            if (delta == 0 || options.isEmpty()) false else {
                                val next = (focusedIndex[0] + delta).coerceIn(0, options.lastIndex)
                                focusedIndex[0] = next
                                focus.focus("option_$next")
                                if (focus.pending != null) listState.requestScrollToItem(next + 1)
                                true
                            }
                        }
                    }
                    .focusGroup(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = MarqueeDims.SafeV),
            ) {
                item(contentType = "text") { KText(title, MarqueeType.Title, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp)) }
                items(options.size, key = { it }, contentType = { "option" }) { i ->
                    TvListRow(
                        options[i], onClick = { onSelect(i); onDismiss() }, selected = i == selected,
                        subtitle = subtitles?.getOrNull(i),
                        modifier = Modifier.fillMaxWidth().restorable(focus, "option_$i")
                            .onFocusChanged { if (it.hasFocus) focusedIndex[0] = i }
                            .snapListFocus(listState, i, i + 1).then(if (i == start) Modifier.focusRequester(fr) else Modifier),
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

/** Outline badge ("4K", "HDR10", "PG-13"). */
@Composable
internal fun OutlineBadge(text: String, modifier: Modifier = Modifier) {
    Box(modifier.border(1.dp, MarqueeColors.Outline, MarqueeDims.ShapeSmall).padding(horizontal = 6.dp, vertical = 2.dp)) {
        KText(text, MarqueeType.Eyebrow, color = MarqueeColors.TextSecondary, maxLines = 1)
    }
}

/**
 * Remembers which focusable (by string key) had focus, so a screen recreated by Back can restore it.
 * Use [Modifier.restorable]; call [arm] on screen entry to request focus on [last].
 */
@Stable
internal class FocusMemory {
    var last: String? = null
    var pending by mutableStateOf<String?>(null)
    fun arm(fallback: String) { pending = last ?: fallback }
    private val targets = HashMap<String, FocusRequester>()
    fun register(key: String, requester: FocusRequester) { targets[key] = requester }
    fun unregister(key: String, requester: FocusRequester) { if (targets[key] === requester) targets.remove(key) }
    private fun request(key: String): Boolean = targets[key]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true
    fun focus(key: String) { if (request(key)) pending = null else pending = key }
    fun placed(key: String) { if (pending == key && request(key)) pending = null }
}

@Composable
internal fun Modifier.restorable(mem: FocusMemory, key: String): Modifier {
    val fr = remember { FocusRequester() }
    androidx.compose.runtime.DisposableEffect(mem, key, fr) {
        mem.register(key, fr)
        onDispose { mem.unregister(key, fr) }
    }
    LaunchedEffect(mem.pending, key) {
        if (mem.pending == key) mem.placed(key)
    }
    return this.focusRequester(fr)
        .onGloballyPositioned { mem.placed(key) }
        .onFocusChanged { if (it.hasFocus) mem.last = key }
}

/** Keeps the focused child at a fixed leading offset (left pivot for rows, upper-third for columns). */
@OptIn(ExperimentalFoundationApi::class)
internal class PivotSpec(private val pivotPx: Float, private val fraction: Float = 0f, private val manual: Boolean = false) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        if (manual) 0f else offset - (pivotPx + containerSize * fraction)
}

@Composable
internal fun BadgeRow(badges: List<String>, modifier: Modifier = Modifier) {
    if (badges.isEmpty()) return
    Row(modifier) {
        badges.forEachIndexed { i, b ->
            if (i > 0) androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            OutlineBadge(b)
        }
    }
}
